package mobile.racemaster.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.RaceEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimeModeRepositoryTest {

    private lateinit var db: RacemasterDatabase
    private lateinit var repository: TimeModeRepository
    private var raceId: Long = 0

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RacemasterDatabase::class.java,
        ).build()
        repository = TimeModeRepository(db, db.raceDao(), db.historyLineDao())
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

        val numbers = db.historyLineDao().observeAllForRace(raceId).first().map { it.splitNumber }.sorted()
        assertEquals(listOf(1, 2, 3), numbers)
    }

    @Test
    fun concurrentFinishesProduceNoDuplicatesOrGaps() = runTest {
        val count = 20
        coroutineScope {
            val jobs = (1..count).map { async { repository.recordSplit(raceId) } }
            jobs.awaitAll()
        }

        val numbers = db.historyLineDao().observeAllForRace(raceId).first().map { it.splitNumber }.sorted()
        assertEquals((1..count).toList(), numbers)
    }

    @Test
    fun undoInsertsMarkerAndReusesSplitNumberInLiveView() = runTest {
        repository.recordSplit(raceId)
        repository.recordSplit(raceId)
        repository.recordSplit(raceId)
        repository.undoMostRecent(raceId)
        repository.recordSplit(raceId)

        // Live view: the undone split disappears and its number is reused by the new one.
        val numbers = repository.observeCurrentSegmentSplits(raceId).first().map { it.splitNumber }.sorted()
        assertEquals(listOf(1, 2, 3), numbers)

        // Nothing was ever deleted from the permanent log — 3 splits + 1 undo-marker + 1 new
        // split = 5, and it only ever grows.
        assertEquals(5, db.historyLineDao().observeAllForRace(raceId).first().size)
    }

    @Test
    fun startStopwatchAddsZeroNumberedStartMarkerWithoutConsumingCounter() = runTest {
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        repository.recordSplit(raceId, timestampMillis = 1_500L)

        val splits = db.historyLineDao().observeAllForRace(raceId).first().sortedBy { it.splitNumber }
        assertEquals(listOf(0, 1), splits.map { it.splitNumber })
        assertEquals(HistoryAction.START, splits.first { it.splitNumber == 0 }.action)
        assertEquals(1_000L, splits.first { it.splitNumber == 0 }.timestampMillis)
    }

    @Test
    fun stopStopwatchAddsStopMarkerConsumingNextNumber() = runTest {
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        repository.recordSplit(raceId)
        repository.recordSplit(raceId)
        repository.stopStopwatch(raceId, stoppedAtMillis = 5_000L)

        val splits = db.historyLineDao().observeAllForRace(raceId).first().sortedBy { it.splitNumber }
        assertEquals(listOf(0, 1, 2, 3), splits.map { it.splitNumber })
        val stopSplit = splits.first { it.splitNumber == 3 }
        assertEquals(HistoryAction.STOP, stopSplit.action)
        assertEquals(5_000L, stopSplit.timestampMillis)
        assertEquals(5_000L, db.raceDao().getById(raceId)?.timeModeStoppedAtMillis)
    }

    @Test
    fun undoingStopMarkerResumesTheRace() = runTest {
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        repository.recordSplit(raceId)
        repository.stopStopwatch(raceId, stoppedAtMillis = 5_000L)

        repository.undoMostRecent(raceId)

        assertEquals(null, db.raceDao().getById(raceId)?.timeModeStoppedAtMillis)
        // The consumed split number is freed up again, same as undoing a normal split.
        repository.recordSplit(raceId)
        val numbers = repository.observeCurrentSegmentSplits(raceId).first().map { it.splitNumber }.sorted()
        assertEquals(listOf(0, 1, 2), numbers)
    }

    @Test
    fun undoingStartMarkerClearsStartedState() = runTest {
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)

        repository.undoMostRecent(raceId)

        assertEquals(null, db.raceDao().getById(raceId)?.timeModeStartedAtMillis)
        assertTrue(repository.observeCurrentSegmentSplits(raceId).first().isEmpty())
        // The Start row itself is never deleted — only an undo-marker was appended.
        assertEquals(2, db.historyLineDao().observeAllForRace(raceId).first().size)
    }

    @Test
    fun resetStopwatchInsertsMarkerAndLeavesPriorSplitsIntact() = runTest {
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        repository.recordSplit(raceId)
        repository.recordSplit(raceId)
        repository.stopStopwatch(raceId, stoppedAtMillis = 5_000L)

        repository.resetStopwatch(raceId, resetAtMillis = 6_000L)

        // Nothing is deleted — every pre-reset row (Start, both splits, Stop) plus the new
        // Reset marker are all still present in the full-history query.
        val allSplits = db.historyLineDao().observeAllForRace(raceId).first()
        assertEquals(5, allSplits.size)
        val resetRow = allSplits.single { it.action == HistoryAction.RESET }
        assertEquals(6_000L, resetRow.timestampMillis)

        // But the clock/counter state resets, same as before.
        val race = db.raceDao().getById(raceId)
        assertEquals(null, race?.timeModeStartedAtMillis)
        assertEquals(null, race?.timeModeStoppedAtMillis)
        assertEquals(1, race?.timeModeNextSplit)

        // The live/current-segment view is empty immediately after reset — nothing pre-reset
        // leaks into what the screen shows.
        assertTrue(db.historyLineDao().observeCurrentSegment(raceId, HistoryMode.TIME, HistoryAction.RESET).first().isEmpty())

        // The race can be started fresh afterward, numbering from scratch in the new segment,
        // while the full history still contains everything from both segments.
        repository.startStopwatch(raceId, startedAtMillis = 9_000L)
        repository.recordSplit(raceId)
        val currentSegmentNumbers =
            db.historyLineDao().observeCurrentSegment(raceId, HistoryMode.TIME, HistoryAction.RESET).first().map { it.splitNumber }.sorted()
        assertEquals(listOf(0, 1), currentSegmentNumbers)
        assertEquals(7, db.historyLineDao().observeAllForRace(raceId).first().size)
    }

    @Test
    fun lineNumberNeverRepeatsOrDecreasesAcrossAReset() = runTest {
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        repository.recordSplit(raceId)
        repository.resetStopwatch(raceId, resetAtMillis = 2_000L)
        repository.startStopwatch(raceId, startedAtMillis = 3_000L)
        repository.recordSplit(raceId)

        // Sorted by id (true insertion order) rather than lineNumber itself, so this
        // genuinely verifies lineNumber tracks insertion order strictly ascending with no
        // repeats.
        val lineNumbersInInsertionOrder = db.historyLineDao().observeAllForRace(raceId).first().sortedBy { it.id }.map { it.lineNumber }
        assertEquals(5, lineNumbersInInsertionOrder.size)
        assertEquals(lineNumbersInInsertionOrder.distinct(), lineNumbersInInsertionOrder)
        for (i in 1 until lineNumbersInInsertionOrder.size) {
            assertTrue(lineNumbersInInsertionOrder[i] > lineNumbersInInsertionOrder[i - 1])
        }
    }

    @Test
    fun undoCannotReachPastAResetBoundary() = runTest {
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        repository.recordSplit(raceId)
        repository.stopStopwatch(raceId, stoppedAtMillis = 2_000L)
        repository.resetStopwatch(raceId, resetAtMillis = 3_000L)

        // Nothing in the new segment yet — Undo must no-op (not even append an undo-marker),
        // not reach back into the old segment.
        repository.undoMostRecent(raceId)

        assertEquals(4, db.historyLineDao().observeAllForRace(raceId).first().size)
        assertTrue(db.historyLineDao().observeCurrentSegment(raceId, HistoryMode.TIME, HistoryAction.RESET).first().isEmpty())
    }

    @Test
    fun formerlyReservedLabelsAreNowOrdinaryFreeTextNotes() = runTest {
        repository.recordSplit(raceId, timestampMillis = 1_000L)
        val splitId = db.historyLineDao().observeAllForRace(raceId).first().single().id

        repository.updateNote(splitId, "Reset")

        // Markers now live in `action`, not `note` — an operator typing the word "Reset" into
        // a genuine note is just ordinary free text with no special meaning to collide with
        // anymore, so it's stored as-is via a normal edit-echo like any other note.
        val all = db.historyLineDao().observeAllForRace(raceId).first()
        assertEquals(2, all.size)
        assertEquals("Reset", all.single { it.note != null }.note)
    }

    @Test
    fun updateNotePersistsCustomNoteAsANewEchoLine() = runTest {
        repository.recordSplit(raceId, timestampMillis = 1_000L)
        val original = db.historyLineDao().observeAllForRace(raceId).first().single()

        repository.updateNote(original.id, "Checkpoint 1")

        // The original row is untouched — a new echo carries the edited note.
        val all = db.historyLineDao().observeAllForRace(raceId).first()
        assertEquals(2, all.size)
        val originalStill = all.single { it.id == original.id }
        assertEquals(null, originalStill.note)
        val echo = all.single { it.id != original.id }
        assertEquals("Checkpoint 1", echo.note)
        assertEquals(original.lineNumber, echo.refLineNumber)

        // The live view shows the edited content, not the original.
        val live = repository.observeCurrentSegmentSplits(raceId).first().single()
        assertEquals("Checkpoint 1", live.note)
    }

    @Test
    fun editEchoPreservesOriginalTimestampAndSplitNumber() = runTest {
        repository.recordSplit(raceId, timestampMillis = 12_345L)
        val original = db.historyLineDao().observeAllForRace(raceId).first().single()

        repository.updateNote(original.id, "Checkpoint 1")

        val echo = db.historyLineDao().observeAllForRace(raceId).first().single { it.id != original.id }
        assertEquals(original.timestampMillis, echo.timestampMillis)
        assertEquals(original.splitNumber, echo.splitNumber)
    }

    @Test
    fun undoOfEditedSplitHidesTheWholeLogicalEntry() = runTest {
        repository.recordSplit(raceId, timestampMillis = 1_000L)
        val original = db.historyLineDao().observeAllForRace(raceId).first().single()
        repository.updateNote(original.id, "Checkpoint 1")

        repository.undoMostRecent(raceId)

        // The whole logical entry (both the original and its edit) disappears from the live
        // view — Undo never partially reverts an edit, only hides the entry entirely.
        assertTrue(repository.observeCurrentSegmentSplits(raceId).first().isEmpty())

        // A second Undo is a no-op — nothing precedes it in the current segment.
        repository.undoMostRecent(raceId)
        assertEquals(3, db.historyLineDao().observeAllForRace(raceId).first().size)
    }

    @Test
    fun editingReservedMarkerRowIsRejected() = runTest {
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        val startRow = db.historyLineDao().observeAllForRace(raceId).first().single()

        repository.updateNote(startRow.id, "Not actually the start")

        // No echo was inserted — the repository-level root-guard refuses to edit a row whose
        // root is a reserved marker.
        assertEquals(1, db.historyLineDao().observeAllForRace(raceId).first().size)
    }

    @Test
    fun getLineNumbersForUuidsResolvesOnlyTheGivenAckedRows() = runTest {
        repository.recordSplit(raceId, timestampMillis = 1_000L)
        repository.recordSplit(raceId, timestampMillis = 2_000L)
        repository.recordSplit(raceId, timestampMillis = 3_000L)
        val splits = db.historyLineDao().observeAllForRace(raceId).first().sortedBy { it.lineNumber }

        val lineNumbers = repository.getLineNumbersForUuids(listOf(splits[0].recordUuid, splits[2].recordUuid))

        assertEquals(setOf(splits[0].lineNumber, splits[2].lineNumber), lineNumbers.toSet())
    }
}
