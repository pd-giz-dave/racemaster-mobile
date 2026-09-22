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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

// CP Mode's repository is built on the same EntryLogModeEngine as Bibs Mode's — most of these
// mirror BibsModeRepositoryTest exactly (proving the shared engine behaves identically once
// wired to CP's own columns/mode). CP's own startCpMode now writes the same Clock-marker (plus
// MODE_START boundary marker) pair Bibs' startBibsMode does — see that method's own doc.
@RunWith(AndroidJUnit4::class)
class CpModeRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: RacemasterDatabase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var raceRepository: RaceRepository
    private lateinit var repository: CpModeRepository
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
        repository = CpModeRepository(db, db.raceDao(), db.historyLineDao(), raceRepository)
        raceId = db.raceDao().insert(RaceEntity(label = "Test Race", createdAtMillis = 0L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun setupRace(location: String = "CP1") {
        raceRepository.recordModeStart(raceId, AppMode.CP, location)
    }

    @Test
    fun startCpModeInsertsOnlyTheClockMarkerAndSetsStartedAt() = runTest {
        setupRace()
        val beforeStart = db.historyLineDao().observeAllForRace(raceId).first().size

        repository.startCpMode(raceId, startedAtMillis = 5_000L)

        val all = db.historyLineDao().observeAllForRace(raceId).first()
        assertEquals(beforeStart + 1, all.size)
        val clock = all.single { it.action == HistoryAction.CLOCK }
        assertEquals(0, clock.splitNumber)
        assertEquals(HistoryMode.CP, clock.mode)
        assertEquals(5_000L, db.raceDao().getById(raceId)?.cpModeStartedAtMillis)

        val live = repository.observeCurrentSegmentEntries(raceId).first()
        assertTrue(live.any { it.action == HistoryAction.CLOCK })
    }

    @Test
    fun retireGetsNoSplitNumberButPassDoesAndBothConsumeAccordingly() = runTest {
        repository.recordEntry(raceId, HistoryAction.PASS, 101, note = null)
        repository.recordEntry(raceId, HistoryAction.RETIRE, 102, note = null)
        repository.recordEntry(raceId, HistoryAction.PASS, 103, note = null)

        val splitNumbers = db.historyLineDao().observeAllForRace(raceId).first()
            .sortedBy { it.id }
            .map { it.splitNumber }

        // Unlike Retire (never crosses the timing point at all, so it gets no splitNumber and
        // doesn't consume the counter — see EntryLogModeEngine.NO_SPLIT_ACTIONS), Pass DOES get
        // one: CP Mode wants it as a plain running count of "how many have passed since
        // Start/Reset" even though a checkpoint has no real Time Mode split to align with (see
        // CpModeRepository's own doc).
        assertEquals(listOf(1, null, 2), splitNumbers)
        assertEquals(3, db.raceDao().getById(raceId)?.cpModeNextSplit)
    }

    @Test
    fun undoAfterPassDecrementsCounter() = runTest {
        repository.recordEntry(raceId, HistoryAction.PASS, 101, note = null)
        repository.undoMostRecent(raceId)
        repository.recordEntry(raceId, HistoryAction.PASS, 102, note = null)

        val entries = repository.observeCurrentSegmentEntries(raceId).first().sortedBy { it.lineNumber }
        assertEquals(1, entries.size)
        assertEquals(1, entries[0].splitNumber)
        assertEquals(2, db.raceDao().getById(raceId)?.cpModeNextSplit)
    }

    @Test
    fun entriesAreStampedWithCpMode() = runTest {
        repository.recordEntry(raceId, HistoryAction.PASS, 101, note = null)

        val entry = db.historyLineDao().observeAllForRace(raceId).first().single()
        assertEquals(HistoryMode.CP, entry.mode)
    }

    // TODO.md: "undo last in time mode includes the initial start, in bibs/cp mode it does not,
    // make them consistent - make bibs/cp like time" — undoing the Clock row now clears
    // startedAt exactly like Time's own START branch, rather than being an unreachable no-op.
    @Test
    fun undoingTheClockRowWhenItsTheOnlyRowClearsStartedAt() = runTest {
        setupRace()
        repository.startCpMode(raceId, startedAtMillis = 123L)

        repository.undoMostRecent(raceId)

        assertNull(db.raceDao().getById(raceId)?.cpModeStartedAtMillis)
        assertEquals(
            listOf(HistoryAction.LOCATION),
            repository.observeCurrentSegmentEntries(raceId).first().map { it.action },
        )
    }

    @Test
    fun resetCpModeClearsStartedAt() = runTest {
        // Same as Bibs' own resetBibsMode now — Reset clears the mode's started-at field,
        // returning the screen to its pre-Start state.
        setupRace()
        repository.startCpMode(raceId, startedAtMillis = 1_000L)
        repository.recordEntry(raceId, HistoryAction.PASS, 101, note = null)

        repository.resetCpMode(raceId, resetAtMillis = 9_000L)

        val race = db.raceDao().getById(raceId)
        assertEquals(1, race?.cpModeNextSplit)
        assertNull(race?.cpModeStartedAtMillis)

        // Nothing is deleted — NEW_RACE, LOCATION, MODE_START, Clock, Pass, and the new Reset
        // marker are all still present.
        val allEntries = db.historyLineDao().observeAllForRace(raceId).first()
        assertEquals(6, allEntries.size)
    }

    @Test
    fun cpAndBibsOnTheSameRaceHaveFullyIndependentCounters() = runTest {
        // The whole point of CP getting its own HistoryMode/columns rather than reusing Bibs'
        // (see EntryLogModeEngine's own doc) — a Finish-line Bibs device and a Checkpoint
        // device can both be recording against the very same race without stomping on each
        // other's split sequence or undo stack.
        val bibsRepository = BibsModeRepository(db, db.raceDao(), db.historyLineDao(), raceRepository)
        bibsRepository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)
        bibsRepository.recordEntry(raceId, HistoryAction.FINISH, 102, note = null)

        repository.recordEntry(raceId, HistoryAction.PASS, 201, note = null)

        // CP's own counter is untouched by Bibs (already at 3, having consumed splits 1 and 2)
        // — its own Pass consumes CP's own counter independently, starting fresh from 1.
        val cpEntry = repository.observeCurrentSegmentEntries(raceId).first().single()
        assertEquals(1, cpEntry.splitNumber)
        assertEquals(2, db.raceDao().getById(raceId)?.cpModeNextSplit)
        assertEquals(3, db.raceDao().getById(raceId)?.bibsModeNextSplit)

        assertTrue(repository.observeCurrentSegmentEntries(raceId).first().isNotEmpty())
    }
}
