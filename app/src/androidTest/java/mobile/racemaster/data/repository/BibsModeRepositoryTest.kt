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

@RunWith(AndroidJUnit4::class)
class BibsModeRepositoryTest {

    private lateinit var db: RacemasterDatabase
    private lateinit var repository: BibsModeRepository
    private var raceId: Long = 0

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RacemasterDatabase::class.java,
        ).build()
        repository = BibsModeRepository(db, db.raceDao(), db.historyLineDao())
        raceId = db.raceDao().insert(RaceEntity(label = "Test Race", createdAtMillis = 0L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun allTypesConsumeTheSharedCounterIncludingRetire() = runTest {
        repository.recordEntry(raceId, HistoryAction.START, 101, note = null)
        repository.recordEntry(raceId, HistoryAction.START, 102, note = null)
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)
        repository.recordEntry(raceId, HistoryAction.RETIRE, 103, note = null)
        repository.recordEntry(raceId, HistoryAction.FINISH, 102, note = null)
        repository.recordEntry(raceId, HistoryAction.START, 104, note = null)

        val splitNumbers = db.historyLineDao().observeAllForRace(raceId).first()
            .sortedBy { it.id }
            .map { it.splitNumber }

        assertEquals(listOf(1, 2, 3, 4, 5, 6), splitNumbers)
    }

    @Test
    fun undoAfterRetireDecrementsCounter() = runTest {
        repository.recordEntry(raceId, HistoryAction.START, 101, note = null)
        repository.recordEntry(raceId, HistoryAction.RETIRE, 103, note = null)
        repository.undoMostRecent(raceId)
        repository.recordEntry(raceId, HistoryAction.START, 102, note = null)

        val entries = repository.observeCurrentSegmentEntries(raceId).first().sortedBy { it.lineNumber }
        assertEquals(2, entries.size)
        assertEquals(1, entries[0].splitNumber)
        assertEquals(2, entries[1].splitNumber)
    }

    @Test
    fun undoAfterStartDecrementsCounter() = runTest {
        repository.recordEntry(raceId, HistoryAction.START, 101, note = null)
        repository.recordEntry(raceId, HistoryAction.START, 102, note = null)
        repository.undoMostRecent(raceId)
        repository.recordEntry(raceId, HistoryAction.START, 103, note = null)

        val entries = repository.observeCurrentSegmentEntries(raceId).first().sortedBy { it.lineNumber }
        val splitNumbers = entries.map { it.splitNumber }
        assertEquals(listOf(1, 2), splitNumbers)
    }

    @Test
    fun undoMarkerCarriesTheUndoneEntrysBibNumber() = runTest {
        // So the marker itself can display which bib got undone (see HistoryLineRow) — the
        // bib # is copied from the root row it hides, purely for display; UNDO isn't in
        // BIB_REQUIRED_ACTIONS so this never affects duplicate/outstanding-bib bookkeeping.
        repository.recordEntry(raceId, HistoryAction.START, 101, note = null)
        repository.undoMostRecent(raceId)

        val undoRow = db.historyLineDao().observeAllForRace(raceId).first().single { it.action == HistoryAction.UNDO }
        assertEquals(101, undoRow.bibNumber)
    }

    @Test
    fun retireGetsASplitNumber() = runTest {
        repository.recordEntry(raceId, HistoryAction.RETIRE, 105, note = null)

        val entry = db.historyLineDao().observeAllForRace(raceId).first().single()
        assertEquals(1, entry.splitNumber)
    }

    @Test
    fun noBibTypesHaveBibNumberForcedToNull() = runTest {
        repository.recordEntry(raceId, HistoryAction.IGNORE, bibNumber = 999, note = null)

        val entry = db.historyLineDao().observeAllForRace(raceId).first().single()
        assertNull(entry.bibNumber)
    }

    @Test
    fun startBibsModeInsertsClockAndDoesNotConsumeCounter() = runTest {
        repository.startBibsMode(raceId)

        val entries = db.historyLineDao().observeAllForRace(raceId).first()
        assertEquals(1, entries.size)
        assertEquals(HistoryAction.CLOCK, entries.single().action)
        assertEquals(0, entries.single().splitNumber)

        // The counter is untouched by Clock — the next real entry still gets split 1.
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)
        val finishEntry = repository.observeCurrentSegmentEntries(raceId).first().single { it.action == HistoryAction.FINISH }
        assertEquals(1, finishEntry.splitNumber)
    }

    @Test
    fun undoIsNoOpWhenClockIsTheOnlyRow() = runTest {
        repository.startBibsMode(raceId)

        repository.undoMostRecent(raceId)

        val entries = db.historyLineDao().observeAllForRace(raceId).first()
        assertEquals(1, entries.size)
        assertEquals(HistoryAction.CLOCK, entries.single().action)
        assertEquals(1, db.raceDao().getById(raceId)?.bibsModeNextSplit)
    }

    @Test
    fun updateEntryNeverMutatesSplitNumber() = runTest {
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)
        val original = db.historyLineDao().observeAllForRace(raceId).first().single()

        repository.updateEntry(original.id, bibNumber = 202, action = HistoryAction.START, note = "corrected")

        // The original row is untouched — a new echo carries the edited fields.
        val all = db.historyLineDao().observeAllForRace(raceId).first()
        assertEquals(2, all.size)
        val originalStill = all.single { it.id == original.id }
        assertEquals(101, originalStill.bibNumber)
        assertEquals(HistoryAction.FINISH, originalStill.action)
        val echo = all.single { it.id != original.id }
        assertEquals(original.splitNumber, echo.splitNumber)
        assertEquals(202, echo.bibNumber)
        assertEquals(HistoryAction.START, echo.action)
        assertEquals("corrected", echo.note)
        assertEquals(original.lineNumber, echo.refLineNumber)
    }

    @Test
    fun updateEntryNullsBibNumberForNoBibTypes() = runTest {
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)
        val original = db.historyLineDao().observeAllForRace(raceId).first().single()

        repository.updateEntry(original.id, bibNumber = 101, action = HistoryAction.SENIORS, note = null)

        val echo = db.historyLineDao().observeAllForRace(raceId).first().single { it.id != original.id }
        assertNull(echo.bibNumber)
        assertEquals(HistoryAction.SENIORS, echo.action)
    }

    @Test
    fun editEchoPreservesOriginalTimestampAndSplitNumber() = runTest {
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null, timestampMillis = 5_000L)
        val original = db.historyLineDao().observeAllForRace(raceId).first().single()

        repository.updateEntry(original.id, bibNumber = 202, action = HistoryAction.START, note = "corrected")

        val echo = db.historyLineDao().observeAllForRace(raceId).first().single { it.id != original.id }
        assertEquals(original.timestampMillis, echo.timestampMillis)
        assertEquals(original.splitNumber, echo.splitNumber)
    }

    @Test
    fun undoOfEditedEntryHidesTheWholeLogicalEntry() = runTest {
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)
        val original = db.historyLineDao().observeAllForRace(raceId).first().single()
        repository.updateEntry(original.id, bibNumber = 202, action = HistoryAction.START, note = "corrected")

        repository.undoMostRecent(raceId)

        // The whole logical entry (both the original and its edit) disappears from the live
        // view — Undo never partially reverts an edit, only hides the entry entirely.
        val visible = repository.observeCurrentSegmentEntries(raceId).first()
        assertTrue(visible.none { it.bibNumber == 101 || it.bibNumber == 202 })

        // A second Undo is a no-op — nothing precedes it in the current segment.
        repository.undoMostRecent(raceId)
        assertEquals(3, db.historyLineDao().observeAllForRace(raceId).first().size)
    }

    @Test
    fun editingStopOrResetRowIsRejected() = runTest {
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)
        repository.stopBibsMode(raceId, stoppedAtMillis = 123L)
        val stopRow = db.historyLineDao().observeAllForRace(raceId).first().single { it.action == HistoryAction.STOP }

        repository.updateEntry(stopRow.id, bibNumber = 999, action = HistoryAction.FINISH, note = "hacked")

        // No echo was inserted — the repository-level root-guard refuses to edit a Stop row.
        assertEquals(2, db.historyLineDao().observeAllForRace(raceId).first().size)
    }

    @Test
    fun updateEntryOnClockRowPinsTypeToClockRegardlessOfRequestedType() = runTest {
        repository.startBibsMode(raceId)
        val clockRow = db.historyLineDao().observeAllForRace(raceId).first().single()

        // Bypasses the UI's own guard (which never offers a Clock row to the generic type
        // picker, only its dedicated time-only panel) — proves the repository-level
        // defense-in-depth holds independently of the UI's cooperation.
        repository.updateEntry(clockRow.id, bibNumber = 101, action = HistoryAction.FINISH, note = "5:30")

        val echo = db.historyLineDao().observeAllForRace(raceId).first().single { it.id != clockRow.id }
        assertEquals(HistoryAction.CLOCK, echo.action)
        assertNull(echo.bibNumber)
        assertEquals("5:30", echo.note)
    }

    @Test
    fun stopBibsModeInsertsStopRowAndUndoResumesLogging() = runTest {
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)
        repository.stopBibsMode(raceId, stoppedAtMillis = 123L)

        val afterStop = db.historyLineDao().observeAllForRace(raceId).first().sortedBy { it.id }
        assertEquals(2, afterStop.size)
        assertEquals(HistoryAction.STOP, afterStop.last().action)
        assertEquals(2, afterStop.last().splitNumber)
        assertEquals(123L, db.raceDao().getById(raceId)?.bibsModeStoppedAtMillis)

        repository.undoMostRecent(raceId)

        // Nothing deleted — the Stop row stays in the permanent log, hidden by an
        // undo-marker instead.
        assertEquals(3, db.historyLineDao().observeAllForRace(raceId).first().size)
        assertNull(db.raceDao().getById(raceId)?.bibsModeStoppedAtMillis)
        assertEquals(2, db.raceDao().getById(raceId)?.bibsModeNextSplit)
        assertTrue(repository.observeCurrentSegmentEntries(raceId).first().none { it.action == HistoryAction.STOP })
    }

    @Test
    fun resetBibsModeInsertsMarkerAndLeavesPriorEntriesIntact() = runTest {
        repository.recordEntry(raceId, HistoryAction.START, 101, note = null)
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)
        repository.stopBibsMode(raceId)

        repository.resetBibsMode(raceId, resetAtMillis = 9_000L)

        // Nothing is deleted — Start, Finish, Stop, and the new Reset marker are all present
        // in the full-history query.
        val allEntries = db.historyLineDao().observeAllForRace(raceId).first()
        assertEquals(4, allEntries.size)
        val resetRow = allEntries.single { it.action == HistoryAction.RESET }
        assertEquals(9_000L, resetRow.timestampMillis)

        // The clock/counter state resets, same as before.
        val race = db.raceDao().getById(raceId)
        assertEquals(1, race?.bibsModeNextSplit)
        assertNull(race?.bibsModeStoppedAtMillis)

        // The live/current-segment view is empty for the new segment — no fresh Clock row is
        // auto-inserted anymore (see BibsModeRepository.resetBibsMode's own doc), so it falls
        // back to the same Start-button state as a freshly created race.
        val currentSegment = db.historyLineDao().observeCurrentSegment(raceId, HistoryMode.BIBS, HistoryAction.RESET).first()
        assertEquals(0, currentSegment.size)
    }

    @Test
    fun resetIsNotUndoableWithNothingInTheNewSegmentToTarget() = runTest {
        repository.recordEntry(raceId, HistoryAction.START, 101, note = null)
        repository.resetBibsMode(raceId)

        // The new segment starts empty (no fresh Clock row — see resetBibsMode's own doc), so
        // there's nothing in it for Undo to target; it's a no-op, which is what makes Reset
        // itself effectively non-undoable, with no special-casing needed.
        repository.undoMostRecent(raceId)

        assertEquals(2, db.historyLineDao().observeAllForRace(raceId).first().size)
        assertEquals(0, db.historyLineDao().observeCurrentSegment(raceId, HistoryMode.BIBS, HistoryAction.RESET).first().size)
    }

    @Test
    fun lineNumberNeverRepeatsOrDecreasesAcrossAReset() = runTest {
        repository.recordEntry(raceId, HistoryAction.START, 101, note = null)
        repository.resetBibsMode(raceId)
        repository.recordEntry(raceId, HistoryAction.START, 102, note = null)

        // Sorted by id (true insertion order) so this genuinely verifies lineNumber tracks
        // insertion order strictly ascending with no repeats.
        val lineNumbersInInsertionOrder = db.historyLineDao().observeAllForRace(raceId).first().sortedBy { it.id }.map { it.lineNumber }
        assertEquals(3, lineNumbersInInsertionOrder.size)
        assertEquals(lineNumbersInInsertionOrder.distinct(), lineNumbersInInsertionOrder)
        for (i in 1 until lineNumbersInInsertionOrder.size) {
            assertTrue(lineNumbersInInsertionOrder[i] > lineNumbersInInsertionOrder[i - 1])
        }
    }

    @Test
    fun getLineNumbersForUuidsResolvesOnlyTheGivenAckedRows() = runTest {
        repository.recordEntry(raceId, HistoryAction.START, 101, note = null)
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)
        repository.recordEntry(raceId, HistoryAction.START, 102, note = null)
        val entries = db.historyLineDao().observeAllForRace(raceId).first().sortedBy { it.lineNumber }

        val lineNumbers = repository.getLineNumbersForUuids(listOf(entries[0].recordUuid, entries[2].recordUuid))

        assertEquals(setOf(entries[0].lineNumber, entries[2].lineNumber), lineNumbers.toSet())
    }
}
