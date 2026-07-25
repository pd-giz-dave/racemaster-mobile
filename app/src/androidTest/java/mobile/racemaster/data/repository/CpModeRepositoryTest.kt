package mobile.racemaster.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.RaceEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// CP Mode's repository is built on the same EntryLogModeEngine as Bibs Mode's — most of these
// mirror BibsModeRepositoryTest exactly (proving the shared engine behaves identically once
// wired to CP's own columns/mode), with the Clock-marker-specific cases dropped (CP never
// writes one) and CP-specific started-at-timestamp cases added instead (see
// RaceEntity.cpModeStartedAtMillis's own doc for why CP tracks "started" that way, unlike Bibs).
@RunWith(AndroidJUnit4::class)
class CpModeRepositoryTest {

    private lateinit var db: RacemasterDatabase
    private lateinit var repository: CpModeRepository
    private var raceId: Long = 0

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RacemasterDatabase::class.java,
        ).build()
        repository = CpModeRepository(db, db.raceDao(), db.historyLineDao())
        raceId = db.raceDao().insert(RaceEntity(label = "Test Race", createdAtMillis = 0L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun startCpModeSetsStartedAtButWritesNoHistoryRow() = runTest {
        repository.startCpMode(raceId, startedAtMillis = 5_000L)

        assertEquals(0, db.historyLineDao().observeAllForRace(raceId).first().size)
        assertEquals(5_000L, db.raceDao().getById(raceId)?.cpModeStartedAtMillis)
    }

    @Test
    fun passAndRetireConsumeTheSharedCounter() = runTest {
        repository.recordEntry(raceId, HistoryAction.PASS, 101, note = null)
        repository.recordEntry(raceId, HistoryAction.RETIRE, 102, note = null)
        repository.recordEntry(raceId, HistoryAction.PASS, 103, note = null)

        val splitNumbers = db.historyLineDao().observeAllForRace(raceId).first()
            .sortedBy { it.id }
            .map { it.splitNumber }

        assertEquals(listOf(1, 2, 3), splitNumbers)
    }

    @Test
    fun undoAfterPassDecrementsCounter() = runTest {
        repository.recordEntry(raceId, HistoryAction.PASS, 101, note = null)
        repository.undoMostRecent(raceId)
        repository.recordEntry(raceId, HistoryAction.PASS, 102, note = null)

        val entries = repository.observeCurrentSegmentEntries(raceId).first().sortedBy { it.lineNumber }
        assertEquals(1, entries.size)
        assertEquals(1, entries[0].splitNumber)
    }

    @Test
    fun entriesAreStampedWithCpMode() = runTest {
        repository.recordEntry(raceId, HistoryAction.PASS, 101, note = null)

        val entry = db.historyLineDao().observeAllForRace(raceId).first().single()
        assertEquals(HistoryMode.CP, entry.mode)
    }

    @Test
    fun stopCpModeInsertsStopRowAndUndoResumesLogging() = runTest {
        repository.recordEntry(raceId, HistoryAction.PASS, 101, note = null)
        repository.stopCpMode(raceId, stoppedAtMillis = 123L)

        assertEquals(123L, db.raceDao().getById(raceId)?.cpModeStoppedAtMillis)

        repository.undoMostRecent(raceId)

        assertNull(db.raceDao().getById(raceId)?.cpModeStoppedAtMillis)
        assertTrue(repository.observeCurrentSegmentEntries(raceId).first().none { it.action == HistoryAction.STOP })
    }

    @Test
    fun resetCpModeClearsStartedAtUnlikeBibsWhichHasNoSuchField() = runTest {
        // The one genuine behavioral difference from Bibs' own resetBibsMode: CP has no Clock
        // marker to fall back on for "started" detection, so Reset must explicitly clear
        // cpModeStartedAtMillis too, returning the screen to its pre-Start state.
        repository.startCpMode(raceId, startedAtMillis = 1_000L)
        repository.recordEntry(raceId, HistoryAction.PASS, 101, note = null)

        repository.resetCpMode(raceId, resetAtMillis = 9_000L)

        val race = db.raceDao().getById(raceId)
        assertEquals(1, race?.cpModeNextSplit)
        assertNull(race?.cpModeStoppedAtMillis)
        assertNull(race?.cpModeStartedAtMillis)

        // Nothing is deleted — the Pass and the new Reset marker are both still present.
        val allEntries = db.historyLineDao().observeAllForRace(raceId).first()
        assertEquals(2, allEntries.size)
    }

    @Test
    fun cpAndBibsOnTheSameRaceHaveFullyIndependentCounters() = runTest {
        // The whole point of CP getting its own HistoryMode/columns rather than reusing Bibs'
        // (see EntryLogModeEngine's own doc) — a Finish-line Bibs device and a Checkpoint
        // device can both be recording against the very same race without stomping on each
        // other's split sequence, undo stack, or stopped state.
        val bibsRepository = BibsModeRepository(db, db.raceDao(), db.historyLineDao())
        bibsRepository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)
        bibsRepository.recordEntry(raceId, HistoryAction.FINISH, 102, note = null)

        repository.recordEntry(raceId, HistoryAction.PASS, 201, note = null)

        // CP's own counter started fresh at 1, unaffected by Bibs already being at 2.
        val cpEntry = repository.observeCurrentSegmentEntries(raceId).first().single()
        assertEquals(1, cpEntry.splitNumber)

        bibsRepository.stopBibsMode(raceId)

        // Stopping Bibs doesn't touch CP's own stopped state.
        assertNull(db.raceDao().getById(raceId)?.cpModeStoppedAtMillis)
        assertTrue(repository.observeCurrentSegmentEntries(raceId).first().isNotEmpty())
    }

    @Test
    fun getLineNumbersForUuidsResolvesOnlyTheGivenAckedRows() = runTest {
        repository.recordEntry(raceId, HistoryAction.PASS, 101, note = null)
        repository.recordEntry(raceId, HistoryAction.RETIRE, 102, note = null)
        repository.recordEntry(raceId, HistoryAction.PASS, 103, note = null)
        val entries = db.historyLineDao().observeAllForRace(raceId).first().sortedBy { it.lineNumber }

        val lineNumbers = repository.getLineNumbersForUuids(listOf(entries[0].recordUuid, entries[2].recordUuid))

        assertEquals(setOf(entries[0].lineNumber, entries[2].lineNumber), lineNumbers.toSet())
    }
}
