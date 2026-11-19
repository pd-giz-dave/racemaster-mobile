package mobile.racemaster.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

// Integration-level coverage for the Reset-awareness CountModeRepository gained alongside
// CountModeViewModel.resetCountMode (TODO.md's "the count screen needs a reset option") — the
// pure parse-function tests already in app/src/test's own CountModeRepositoryTest don't exercise
// the Room-backed currentSegment() scoping this needs a real database for.
@RunWith(AndroidJUnit4::class)
class CountModeRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: RacemasterDatabase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var raceRepository: RaceRepository
    private lateinit var repository: CountModeRepository
    private var raceId: Long = 0

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RacemasterDatabase::class.java,
        ).build()
        settingsRepository = SettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()),
                produceFile = { tempFolder.newFile("test.preferences_pb") },
            ),
        )
        raceRepository = RaceRepository(db, db.raceDao(), db.historyLineDao(), db.lineSyncDao(), settingsRepository)
        repository = CountModeRepository(db, db.raceDao(), db.historyLineDao())
        raceId = db.raceDao().insert(RaceEntity(label = "Test Race", createdAtMillis = 0L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    // Mirrors Setup Race's own real write — see TimeModeRepositoryTest's identical helper.
    private suspend fun setupRace() {
        raceRepository.recordModeStart(raceId, AppMode.COUNT, "Pen")
    }

    @Test
    fun recordCountNumbersSequentiallyWithinASegment() = runTest {
        setupRace()
        repository.recordCount(raceId, beaconNumber = 1, bibNumber = 101, totalCount = 3)
        repository.recordCount(raceId, beaconNumber = 2, bibNumber = 102, totalCount = 3)
        repository.recordCount(raceId, beaconNumber = 3, bibNumber = 103, totalCount = 3)

        // observeCounts (like every other mode's own live view — see HistoryFold.currentSegmentRows'
        // own doc) returns newest-first, not insertion order.
        val seen = repository.observeCounts(raceId).first().filter { it.action == HistoryAction.COUNT_SEEN }
        assertEquals(listOf("beacon=3 seen=3 of=3", "beacon=2 seen=2 of=3", "beacon=1 seen=1 of=3"), seen.map { it.note })
        assertTrue(repository.observeCounts(raceId).first().any { it.action == HistoryAction.COUNT_COMPLETE })
    }

    @Test
    fun closeCurrentSegmentThenRecordCountRestartsTheTallyFromOne() = runTest {
        setupRace()
        repository.recordCount(raceId, beaconNumber = 1, bibNumber = 101, totalCount = 2)
        repository.recordCount(raceId, beaconNumber = 2, bibNumber = 102, totalCount = 2)

        // Mirrors CountModeViewModel.resetCountMode's own write — the same generic RESET-row
        // mechanism every other mode's own Reset button already uses.
        raceRepository.closeCurrentSegment(raceId, HistoryMode.COUNT)

        // The live view is empty immediately after Reset — nothing pre-reset leaks into a
        // restarted count, exactly like Bibs/Time's own current-segment view after their Reset.
        assertTrue(repository.observeCounts(raceId).first().isEmpty())

        // Mirrors the real restart path: the screen already exited (CountModeScreen's own Reset
        // dialog calls onHome()), so the only way back into Count mode is through Setup Race
        // again — which, for this same race, writes a fresh LOCATION+MODE_START pair rather than
        // resuming the reset one (recordModeStart never resumes a reset visit's own counter for
        // Count/Close — see that function's own doc).
        setupRace()

        // Beacon 1 was already seen before the reset — recording it again in the new segment
        // must not be treated as a duplicate, and the tally itself must restart at 1, not resume
        // at 3.
        repository.recordCount(raceId, beaconNumber = 1, bibNumber = 101, totalCount = 2)

        val seen = repository.observeCounts(raceId).first().filter { it.action == HistoryAction.COUNT_SEEN }
        assertEquals(listOf("beacon=1 seen=1 of=2"), seen.map { it.note })

        // Nothing is deleted — the 2 pre-reset COUNT_SEEN rows plus the 1 post-reset one are all
        // still in the race's full history, even though observeCounts (above) only surfaced the
        // post-reset one.
        val all = db.historyLineDao().getAllForRaceAndMode(raceId, HistoryMode.COUNT)
        assertEquals(3, all.count { it.action == HistoryAction.COUNT_SEEN })
    }
}
