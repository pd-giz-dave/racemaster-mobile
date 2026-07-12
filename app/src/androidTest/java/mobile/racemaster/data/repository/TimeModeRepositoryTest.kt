package mobile.racemaster.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimeModeRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: RacemasterDatabase
    private lateinit var repository: TimeModeRepository
    private var raceId: Long = 0

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RacemasterDatabase::class.java,
        ).build()
        val settingsRepository = SettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()),
                produceFile = { tempFolder.newFile("test.preferences_pb") },
            ),
        )
        repository = TimeModeRepository(db, db.raceDao(), db.finishSplitDao(), settingsRepository)
        raceId = db.raceDao().insert(RaceEntity(label = "Test Race", createdAtMillis = 0L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sequentialFinishesNumberInOrder() = runTest {
        repository.recordSplit(raceId)
        repository.recordSplit(raceId)
        repository.recordSplit(raceId)

        val numbers = db.finishSplitDao().observeForRace(raceId).first().map { it.splitNumber }.sorted()
        assertEquals(listOf(1, 2, 3), numbers)
    }

    @Test
    fun concurrentFinishesProduceNoDuplicatesOrGaps() = runTest {
        val count = 20
        coroutineScope {
            val jobs = (1..count).map { async { repository.recordSplit(raceId) } }
            jobs.awaitAll()
        }

        val numbers = db.finishSplitDao().observeForRace(raceId).first().map { it.splitNumber }.sorted()
        assertEquals((1..count).toList(), numbers)
    }

    @Test
    fun undoReusesSplitNumber() = runTest {
        repository.recordSplit(raceId)
        repository.recordSplit(raceId)
        repository.recordSplit(raceId)
        repository.deleteMostRecent(raceId)
        repository.recordSplit(raceId)

        val numbers = db.finishSplitDao().observeForRace(raceId).first().map { it.splitNumber }.sorted()
        assertEquals(listOf(1, 2, 3), numbers)
    }

    @Test
    fun startStopwatchAddsZeroNumberedStartMarkerWithoutConsumingCounter() = runTest {
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        repository.recordSplit(raceId, timestampMillis = 1_500L)

        val splits = db.finishSplitDao().observeForRace(raceId).first().sortedBy { it.splitNumber }
        assertEquals(listOf(0, 1), splits.map { it.splitNumber })
        assertEquals(TimeModeRepository.START_LABEL, splits.first { it.splitNumber == 0 }.note)
        assertEquals(1_000L, splits.first { it.splitNumber == 0 }.timestampMillis)
    }

    @Test
    fun stopStopwatchAddsStopMarkerConsumingNextNumber() = runTest {
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        repository.recordSplit(raceId)
        repository.recordSplit(raceId)
        repository.stopStopwatch(raceId, stoppedAtMillis = 5_000L)

        val splits = db.finishSplitDao().observeForRace(raceId).first().sortedBy { it.splitNumber }
        assertEquals(listOf(0, 1, 2, 3), splits.map { it.splitNumber })
        val stopSplit = splits.first { it.splitNumber == 3 }
        assertEquals(TimeModeRepository.STOP_LABEL, stopSplit.note)
        assertEquals(5_000L, stopSplit.timestampMillis)
        assertEquals(5_000L, db.raceDao().getById(raceId)?.timeModeStoppedAtMillis)
    }

    @Test
    fun undoingStopMarkerResumesTheRace() = runTest {
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        repository.recordSplit(raceId)
        repository.stopStopwatch(raceId, stoppedAtMillis = 5_000L)

        repository.deleteMostRecent(raceId)

        assertEquals(null, db.raceDao().getById(raceId)?.timeModeStoppedAtMillis)
        // The consumed split number is freed up again, same as undoing a normal split.
        repository.recordSplit(raceId)
        val numbers = db.finishSplitDao().observeForRace(raceId).first().map { it.splitNumber }.sorted()
        assertEquals(listOf(0, 1, 2), numbers)
    }

    @Test
    fun undoingStartMarkerClearsStartedState() = runTest {
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)

        repository.deleteMostRecent(raceId)

        assertEquals(null, db.raceDao().getById(raceId)?.timeModeStartedAtMillis)
        assertEquals(0, db.finishSplitDao().observeForRace(raceId).first().size)
    }

    @Test
    fun resetStopwatchClearsSplitsAndClockState() = runTest {
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        repository.recordSplit(raceId)
        repository.recordSplit(raceId)
        repository.stopStopwatch(raceId, stoppedAtMillis = 5_000L)

        repository.resetStopwatch(raceId)

        assertEquals(0, db.finishSplitDao().observeForRace(raceId).first().size)
        val race = db.raceDao().getById(raceId)
        assertEquals(null, race?.timeModeStartedAtMillis)
        assertEquals(null, race?.timeModeStoppedAtMillis)
        assertEquals(1, race?.timeModeNextSplit)

        // The race can be started fresh afterward, numbering from scratch.
        repository.startStopwatch(raceId, startedAtMillis = 9_000L)
        repository.recordSplit(raceId)
        val numbers = db.finishSplitDao().observeForRace(raceId).first().map { it.splitNumber }.sorted()
        assertEquals(listOf(0, 1), numbers)
    }

    @Test
    fun updateNotePersistsCustomNote() = runTest {
        repository.recordSplit(raceId, timestampMillis = 1_000L)
        val splitId = db.finishSplitDao().observeForRace(raceId).first().single().id

        repository.updateNote(splitId, "Checkpoint 1")

        val updated = db.finishSplitDao().observeForRace(raceId).first().single()
        assertEquals("Checkpoint 1", updated.note)
    }
}