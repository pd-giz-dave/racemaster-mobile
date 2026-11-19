package mobile.racemaster.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mobile.racemaster.data.db.RacemasterDatabase
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.settings.AppMode
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
import org.junit.Assert.assertTrue
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
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var raceRepository: RaceRepository
    private lateinit var repository: TimeModeRepository
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
        repository = TimeModeRepository(db, db.raceDao(), db.historyLineDao(), raceRepository)
        raceId = db.raceDao().insert(RaceEntity(label = "Test Race", createdAtMillis = 0L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    // Mirrors Setup Race's own real write — RaceRepository.recordModeStart — so every test below
    // that starts the stopwatch has a genuine open segment to write into, exactly like the real
    // app: NEW_RACE + LOCATION + MODE_START (3 rows) up front, same as production.
    private suspend fun setupRace(location: String = "Finish") {
        raceRepository.recordModeStart(raceId, AppMode.TIME, location)
    }

    @Test
    fun sequentialFinishesNumberInOrder() = runTest {
        repository.recordSplit(raceId)
        repository.recordSplit(raceId)
        repository.recordSplit(raceId)

        val numbers = db.historyLineDao().observeAllForRace(raceId).first().map { it.splitNumber }.sortedBy { it }
        assertEquals(listOf(1, 2, 3), numbers)
    }

    @Test
    fun concurrentFinishesProduceNoDuplicatesOrGaps() = runTest {
        val count = 20
        coroutineScope {
            val jobs = (1..count).map { async { repository.recordSplit(raceId) } }
            jobs.awaitAll()
        }

        val numbers = db.historyLineDao().observeAllForRace(raceId).first().map { it.splitNumber }.sortedBy { it }
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
        val numbers = repository.observeCurrentSegmentSplits(raceId).first().map { it.splitNumber }.sortedBy { it }
        assertEquals(listOf(1, 2, 3), numbers)

        // Nothing was ever deleted from the permanent log — 3 splits + 1 undo-marker + 1 new
        // split = 5, and it only ever grows.
        assertEquals(5, db.historyLineDao().observeAllForRace(raceId).first().size)
    }

    @Test
    fun startStopwatchAddsZeroNumberedStartMarkerWithoutConsumingCounter() = runTest {
        setupRace()
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        repository.recordSplit(raceId, timestampMillis = 1_500L)

        // NEW_RACE + LOCATION + MODE_START (from setupRace) + START + SPLIT.
        val splits = db.historyLineDao().observeAllForRace(raceId).first()
        assertEquals(5, splits.size)
        val startRow = splits.single { it.action == HistoryAction.START }
        assertEquals(0, startRow.splitNumber)
        assertEquals(1_000L, startRow.timestampMillis)

        val live = repository.observeCurrentSegmentSplits(raceId).first()
        assertTrue(live.none { it.action == HistoryAction.MODE_START })
    }

    @Test
    fun undoingStartMarkerClearsStartedState() = runTest {
        setupRace()
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)

        repository.undoMostRecent(raceId)

        assertEquals(null, db.raceDao().getById(raceId)?.timeModeStartedAtMillis)
        // The LOCATION marker itself is still visible/undoable — only the Start marker's own
        // group was hidden by the undo.
        assertEquals(
            listOf(HistoryAction.LOCATION),
            repository.observeCurrentSegmentSplits(raceId).first().map { it.action },
        )
        // Nothing is ever deleted — only an undo-marker was appended on top of the Start row:
        // NEW_RACE, LOCATION, MODE_START, Start, Undo.
        assertEquals(5, db.historyLineDao().observeAllForRace(raceId).first().size)
    }

    @Test
    fun resetStopwatchInsertsMarkerAndLeavesPriorSplitsIntact() = runTest {
        setupRace()
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        repository.recordSplit(raceId)
        repository.recordSplit(raceId)

        val locationRow = db.historyLineDao().observeAllForRace(raceId).first().single { it.action == HistoryAction.LOCATION }
        repository.resetStopwatch(raceId, resetAtMillis = 6_000L)

        // Nothing is deleted — every pre-reset row plus the new Reset marker are all still
        // present in the full-history query: NEW_RACE, LOCATION, MODE_START, Start, 2 splits, Reset.
        val allSplits = db.historyLineDao().observeAllForRace(raceId).first()
        assertEquals(7, allSplits.size)
        val resetRow = allSplits.single { it.action == HistoryAction.RESET }
        assertEquals(6_000L, resetRow.timestampMillis)
        // Reset is a boundary marker, not a real logged split — no splitNumber of its own.
        assertEquals(null, resetRow.splitNumber)
        // Targets the LOCATION row it closed — see RaceRepository.closeCurrentSegment's own doc.
        assertEquals(locationRow.lineNumber, resetRow.refLineNumber)

        // The counter/started-at state resets too, same as before.
        val race = db.raceDao().getById(raceId)
        assertEquals(null, race?.timeModeStartedAtMillis)
        assertEquals(1, race?.timeModeNextSplit)

        // The live/current-segment view is empty immediately after reset — nothing pre-reset
        // leaks into what the screen shows.
        assertTrue(repository.observeCurrentSegmentSplits(raceId).first().isEmpty())
    }

    @Test
    fun resetWritesOneMarkerPerMergedVisitWhenRelocatedBackWithoutResetting() = runTest {
        setupRace("Finish")
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        repository.recordSplit(raceId)
        // Setup Race re-confirming Time mode without ever resetting — Time's own location is
        // always forced to "Finish" regardless of what's passed (RaceRepository.recordModeStart's
        // own effectiveLocation doc: Time has no genuinely different location to relocate to the
        // way Bibs does), but this still writes a brand-new LOCATION+MODE_START pair each time, so
        // there are still multiple visits for Reset to merge and close together.
        raceRepository.recordModeStart(raceId, AppMode.TIME, "Finish")
        repository.recordSplit(raceId)
        raceRepository.recordModeStart(raceId, AppMode.TIME, "Finish")
        repository.recordSplit(raceId)

        val locationLines = db.historyLineDao().observeAllForRace(raceId).first()
            .filter { it.action == HistoryAction.LOCATION }.map { it.lineNumber }.toSet()
        assertEquals(3, locationLines.size)

        repository.resetStopwatch(raceId, resetAtMillis = 9_000L)

        // One RESET row per merged visit — Time merges every visit regardless of location (it
        // isn't location-aware the way Bibs is), so all 3 are closed by the same Reset at once.
        val resetTargets = db.historyLineDao().observeAllForRace(raceId).first()
            .filter { it.action == HistoryAction.RESET }.mapNotNull { it.refLineNumber }.toSet()
        assertEquals(locationLines, resetTargets)
    }

    @Test
    fun reconfirmingTimeModeWithoutResettingResumesTheCounterAndKeepsPriorSplitsVisible() = runTest {
        setupRace("Finish")
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        repository.recordSplit(raceId) // split 1
        repository.recordSplit(raceId) // split 2

        // Same re-confirm-without-resetting scenario as the test above — still merges into the
        // same current segment either way, since Time isn't location-aware.
        raceRepository.recordModeStart(raceId, AppMode.TIME, "Finish")
        repository.recordSplit(raceId) // split 3 — continues the counter, never forks or resets it

        raceRepository.recordModeStart(raceId, AppMode.TIME, "Finish")

        // Resumes at 4 (one past the highest split recorded anywhere so far), not reset to 1.
        assertEquals(4, db.raceDao().getById(raceId)?.timeModeNextSplit)

        // All 3 splits stay visible — a re-confirm never hides prior entries the way a genuinely
        // new location would for Bibs.
        val live = repository.observeCurrentSegmentSplits(raceId).first()
        assertEquals(3, live.count { it.action == HistoryAction.SPLIT })
    }

    @Test
    fun undoCannotReachPastAClosedSegment() = runTest {
        setupRace()
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        repository.recordSplit(raceId)
        repository.resetStopwatch(raceId, resetAtMillis = 3_000L)

        // Nothing in the new segment yet — Undo must no-op (not even append an undo-marker),
        // not reach back into the closed one.
        repository.undoMostRecent(raceId)

        // NEW_RACE, LOCATION, MODE_START, Start, Split, Reset — the no-op Undo added nothing.
        assertEquals(6, db.historyLineDao().observeAllForRace(raceId).first().size)
        assertTrue(repository.observeCurrentSegmentSplits(raceId).first().isEmpty())
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
        setupRace()
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        val startRow = db.historyLineDao().observeAllForRace(raceId).first().single { it.action == HistoryAction.START }

        repository.updateNote(startRow.id, "Not actually the start")

        // No echo was inserted — the repository-level root-guard refuses to edit a row whose
        // root is a reserved marker. Still just NEW_RACE + LOCATION + MODE_START + Start.
        assertEquals(4, db.historyLineDao().observeAllForRace(raceId).first().size)
    }

    @Test
    fun aBareRelocateDoesNotHidePriorEntriesSoUndoCanReachAcrossIt() = runTest {
        setupRace()
        repository.startStopwatch(raceId, startedAtMillis = 1_000L)
        repository.recordSplit(raceId, timestampMillis = 1_500L)

        // Relocate mid-race (allowed even while Time is still recording) — forced back to
        // "Finish" either way (RaceRepository.recordModeStart's own effectiveLocation doc). Time
        // isn't location-aware (TODO.md's "A rationalisation"), so unlike Bibs' own version of
        // this scenario, the prior split stays fully visible rather than being hidden behind a
        // fresh, empty visit.
        raceRepository.recordModeStart(raceId, AppMode.TIME, "CP1")
        assertEquals(
            listOf(HistoryAction.LOCATION, HistoryAction.SPLIT, HistoryAction.START, HistoryAction.LOCATION),
            repository.observeCurrentSegmentSplits(raceId).first().map { it.action },
        )

        // Undo still never targets a LOCATION row itself (see EntryLogModeEngine.undoMostRecent's
        // own doc) — but with the prior split visible instead of hidden, it's what Undo reaches,
        // rather than this being a no-op the way it is for Bibs.
        repository.undoMostRecent(raceId)
        assertEquals(
            listOf(HistoryAction.LOCATION, HistoryAction.START, HistoryAction.LOCATION),
            repository.observeCurrentSegmentSplits(raceId).first().map { it.action },
        )
        // Coerced to "Finish" regardless of the "CP1" argument above — see effectiveLocation's
        // own doc.
        assertEquals("Finish", db.raceDao().getById(raceId)?.location)
    }
}
