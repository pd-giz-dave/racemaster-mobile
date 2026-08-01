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
// wired to CP's own columns/mode). CP's own startCpMode now writes the same Clock-marker (plus
// MODE_START boundary marker) pair Bibs' startBibsMode does — see that method's own doc.
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
    fun startCpModeInsertsModeStartThenClockAndSetsStartedAt() = runTest {
        repository.startCpMode(raceId, startedAtMillis = 5_000L)

        // A MODE_START boundary marker (see that action's own doc — purely for the web app's
        // later mode-change-boundary detection) immediately followed by the real Clock marker,
        // same pair Bibs' own startBibsMode writes.
        val all = db.historyLineDao().observeAllForRace(raceId).first().sortedBy { it.lineNumber }
        assertEquals(2, all.size)
        assertEquals(HistoryAction.MODE_START, all[0].action)
        assertNull(all[0].splitNumber)
        assertEquals(HistoryAction.CLOCK, all[1].action)
        assertEquals(0, all[1].splitNumber)
        assertEquals(HistoryMode.CP, all[0].mode)
        assertEquals(5_000L, db.raceDao().getById(raceId)?.cpModeStartedAtMillis)

        // MODE_START never reaches the live screen — only the Clock row does.
        val live = repository.observeCurrentSegmentEntries(raceId).first()
        assertEquals(1, live.size)
        assertEquals(HistoryAction.CLOCK, live.single().action)
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
    fun resetCpModeClearsStartedAt() = runTest {
        // Same as Bibs' own resetBibsMode now — Reset clears the mode's started-at field,
        // returning the screen to its pre-Start state.
        repository.startCpMode(raceId, startedAtMillis = 1_000L)
        repository.recordEntry(raceId, HistoryAction.PASS, 101, note = null)

        repository.resetCpMode(raceId, resetAtMillis = 9_000L)

        val race = db.raceDao().getById(raceId)
        assertEquals(1, race?.cpModeNextSplit)
        assertNull(race?.cpModeStoppedAtMillis)
        assertNull(race?.cpModeStartedAtMillis)

        // Nothing is deleted — the MODE_START/Clock pair from startCpMode, the Pass, and the
        // new Reset marker are all still present.
        val allEntries = db.historyLineDao().observeAllForRace(raceId).first()
        assertEquals(4, allEntries.size)
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

        // CP's own counter is untouched by Bibs (already at 3, having consumed splits 1 and 2)
        // — its own Pass consumes CP's own counter independently, starting fresh from 1.
        val cpEntry = repository.observeCurrentSegmentEntries(raceId).first().single()
        assertEquals(1, cpEntry.splitNumber)
        assertEquals(2, db.raceDao().getById(raceId)?.cpModeNextSplit)
        assertEquals(3, db.raceDao().getById(raceId)?.bibsModeNextSplit)

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
