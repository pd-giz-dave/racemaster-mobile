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

@RunWith(AndroidJUnit4::class)
class BibsModeRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: RacemasterDatabase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var raceRepository: RaceRepository
    private lateinit var repository: BibsModeRepository
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
        repository = BibsModeRepository(db, db.raceDao(), db.historyLineDao(), raceRepository)
        raceId = db.raceDao().insert(RaceEntity(label = "Test Race", createdAtMillis = 0L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    // Mirrors Setup Race's own real write — see TimeModeRepositoryTest's identical helper.
    private suspend fun setupRace(location: String = "Finish") {
        raceRepository.recordModeStart(raceId, AppMode.BIBS, location)
    }

    @Test
    fun everyTypeExceptRetireConsumesTheSharedCounter() = runTest {
        repository.recordEntry(raceId, HistoryAction.START, 101, note = null)
        repository.recordEntry(raceId, HistoryAction.START, 102, note = null)
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)
        repository.recordEntry(raceId, HistoryAction.RETIRE, 103, note = null)
        repository.recordEntry(raceId, HistoryAction.FINISH, 102, note = null)
        repository.recordEntry(raceId, HistoryAction.START, 104, note = null)

        val splitNumbers = db.historyLineDao().observeAllForRace(raceId).first()
            .sortedBy { it.id }
            .map { it.splitNumber }

        // A retiree never crosses this timing point, so it has no corresponding Time Mode
        // split — it gets no splitNumber of its own, and the very next real crossing still
        // gets the number it would have gotten had the retiree never been logged.
        assertEquals(listOf(1, 2, 3, null, 4, 5), splitNumbers)
    }

    @Test
    fun undoAfterRetireLeavesCounterUnaffected() = runTest {
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
    fun retireGetsNoSplitNumberAndDoesNotConsumeTheCounter() = runTest {
        repository.recordEntry(raceId, HistoryAction.RETIRE, 105, note = null)
        val retireEntry = db.historyLineDao().observeAllForRace(raceId).first().single()
        assertNull(retireEntry.splitNumber)

        // The next real crossing still gets split 1 — the retiree never consumed it.
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)
        val finishEntry = db.historyLineDao().observeAllForRace(raceId).first().single { it.action == HistoryAction.FINISH }
        assertEquals(1, finishEntry.splitNumber)
    }

    @Test
    fun noBibTypesHaveBibNumberForcedToNull() = runTest {
        repository.recordEntry(raceId, HistoryAction.IGNORE, bibNumber = 999, note = null)

        val entry = db.historyLineDao().observeAllForRace(raceId).first().single()
        assertNull(entry.bibNumber)
    }

    @Test
    fun startBibsModeInsertsClockAndDoesNotConsumeCounter() = runTest {
        setupRace()
        repository.startBibsMode(raceId)

        // NEW_RACE + LOCATION + MODE_START (setupRace) + Clock.
        val entries = db.historyLineDao().observeAllForRace(raceId).first()
        assertEquals(4, entries.size)
        val clockEntry = entries.single { it.action == HistoryAction.CLOCK }
        assertEquals(0, clockEntry.splitNumber)

        // The counter is untouched by Clock — the next real entry still gets split 1.
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)
        val finishEntry = repository.observeCurrentSegmentEntries(raceId).first().single { it.action == HistoryAction.FINISH }
        assertEquals(1, finishEntry.splitNumber)
    }

    @Test
    fun startBibsModeInsertsOnlyTheClockMarkerOfItsOwn() = runTest {
        setupRace()
        val beforeStart = db.historyLineDao().observeAllForRace(raceId).first().size

        repository.startBibsMode(raceId, startedAtMillis = 5_000L)

        // Exactly one new row — the Clock marker — beyond whatever setupRace already wrote.
        val all = db.historyLineDao().observeAllForRace(raceId).first()
        assertEquals(beforeStart + 1, all.size)
        val clock = all.single { it.action == HistoryAction.CLOCK }
        assertEquals(HistoryMode.BIBS, clock.mode)
    }

    @Test
    fun startBibsModeSetsStartedAtTimestamp() = runTest {
        // Shaped identically to Time/CP's own start methods now — see
        // RaceEntity.bibsModeStartedAtMillis's own doc for why this, not the Clock row's mere
        // presence, is what "started" is derived from.
        setupRace()
        repository.startBibsMode(raceId, startedAtMillis = 5_000L)

        assertEquals(5_000L, db.raceDao().getById(raceId)?.bibsModeStartedAtMillis)
    }

    @Test
    fun undoingTheOnlyRealEntryLeavesStartedAtIntact() = runTest {
        // This is the whole point of bibsModeStartedAtMillis existing: undoing the very first
        // real entry must leave the screen still showing the keypad rather than reverting to a
        // pre-Start state, same as CP's own cpModeStartedAtMillis already guarantees.
        setupRace()
        repository.startBibsMode(raceId, startedAtMillis = 5_000L)
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)

        repository.undoMostRecent(raceId)

        assertEquals(5_000L, db.raceDao().getById(raceId)?.bibsModeStartedAtMillis)
    }

    @Test
    fun resetBibsModeClearsStartedAt() = runTest {
        setupRace()
        repository.startBibsMode(raceId, startedAtMillis = 1_000L)

        repository.resetBibsMode(raceId, resetAtMillis = 9_000L)

        assertNull(db.raceDao().getById(raceId)?.bibsModeStartedAtMillis)
    }

    // TODO.md: "undo last in time mode includes the initial start, in bibs/cp mode it does not,
    // make them consistent - make bibs/cp like time" — undoing the Clock row now clears
    // startedAt exactly like Time's own START branch, rather than being an unreachable no-op.
    @Test
    fun undoingTheClockRowWhenItsTheOnlyRowClearsStartedAt() = runTest {
        setupRace()
        repository.startBibsMode(raceId, startedAtMillis = 5_000L)

        repository.undoMostRecent(raceId)

        assertNull(db.raceDao().getById(raceId)?.bibsModeStartedAtMillis)
        // The LOCATION marker itself is still visible/undoable — only the Clock row's own
        // group was hidden by the undo.
        assertEquals(
            listOf(HistoryAction.LOCATION),
            repository.observeCurrentSegmentEntries(raceId).first().map { it.action },
        )
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
    fun editingAResetRowIsRejected() = runTest {
        setupRace()
        repository.startBibsMode(raceId)
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)
        repository.resetBibsMode(raceId)
        val resetRow = db.historyLineDao().observeAllForRace(raceId).first().single { it.action == HistoryAction.RESET }
        val countBefore = db.historyLineDao().observeAllForRace(raceId).first().size

        repository.updateEntry(resetRow.id, bibNumber = 999, action = HistoryAction.FINISH, note = "hacked")

        // No echo was inserted — the repository-level root-guard refuses to edit a Reset row.
        assertEquals(countBefore, db.historyLineDao().observeAllForRace(raceId).first().size)
    }

    @Test
    fun updateEntryOnClockRowPinsTypeToClockRegardlessOfRequestedType() = runTest {
        setupRace()
        repository.startBibsMode(raceId)
        val clockRow = db.historyLineDao().observeAllForRace(raceId).first().single { it.action == HistoryAction.CLOCK }

        // Bypasses the UI's own guard (which never offers a Clock row to the generic type
        // picker, only its dedicated time-only panel) — proves the repository-level
        // defense-in-depth holds independently of the UI's cooperation.
        repository.updateEntry(clockRow.id, bibNumber = 101, action = HistoryAction.FINISH, note = "5:30")

        val echo = db.historyLineDao().observeAllForRace(raceId).first().single { it.refLineNumber == clockRow.lineNumber }
        assertEquals(HistoryAction.CLOCK, echo.action)
        assertNull(echo.bibNumber)
        assertEquals("5:30", echo.note)
    }

    @Test
    fun undoingARelocateRevealsWhateverWasVisibleBeforeIt() = runTest {
        setupRace()
        repository.startBibsMode(raceId)
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)

        // Relocate mid-race (allowed even while Bibs is still recording).
        raceRepository.recordModeStart(raceId, AppMode.BIBS, "CP1")

        assertEquals(
            listOf(HistoryAction.LOCATION),
            repository.observeCurrentSegmentEntries(raceId).first().map { it.action },
        )

        // Undo: undoes the relocate — with the relocate's own LOCATION group folded away, the
        // race's own original (setupRace's own) LOCATION marker is what's left standing, so it
        // correctly reveals the whole pre-relocate segment (the Finish, the Clock marker, and
        // that original LOCATION row itself) exactly as it stood before.
        repository.undoMostRecent(raceId)
        assertEquals(
            listOf(HistoryAction.FINISH, HistoryAction.CLOCK, HistoryAction.LOCATION),
            repository.observeCurrentSegmentEntries(raceId).first().map { it.action },
        )
        assertEquals("Finish", db.raceDao().getById(raceId)?.location)
    }

    @Test
    fun resumeBibsModeIsANoOpNowThatThereIsNoStoppedStateToClear() = runTest {
        setupRace()
        repository.startBibsMode(raceId)
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)
        val before = db.historyLineDao().observeAllForRace(raceId).first()
        val raceBefore = db.raceDao().getById(raceId)

        repository.resumeBibsMode(raceId)

        assertEquals(before, db.historyLineDao().observeAllForRace(raceId).first())
        assertEquals(raceBefore, db.raceDao().getById(raceId))
    }

    @Test
    fun resetBibsModeInsertsMarkerAndLeavesPriorEntriesIntact() = runTest {
        setupRace()
        repository.startBibsMode(raceId)
        repository.recordEntry(raceId, HistoryAction.START, 101, note = null)
        repository.recordEntry(raceId, HistoryAction.FINISH, 101, note = null)

        val locationRow = db.historyLineDao().observeAllForRace(raceId).first().single { it.action == HistoryAction.LOCATION }
        val abandoned = repository.resetBibsMode(raceId, resetAtMillis = 9_000L)

        assertTrue(abandoned) // the race's own first-ever segment

        // Nothing is deleted — NEW_RACE, LOCATION, MODE_START, Clock, Start, Finish, and the
        // new Reset marker are all present in the full-history query.
        val allEntries = db.historyLineDao().observeAllForRace(raceId).first()
        assertEquals(7, allEntries.size)
        val resetRow = allEntries.single { it.action == HistoryAction.RESET }
        assertEquals(9_000L, resetRow.timestampMillis)
        assertEquals(locationRow.lineNumber, resetRow.refLineNumber)

        // The counter/started-at state resets, same as before.
        val race = db.raceDao().getById(raceId)
        assertEquals(1, race?.bibsModeNextSplit)
        assertNull(race?.bibsModeStartedAtMillis)

        // The live/current-segment view is empty for the new segment.
        assertTrue(repository.observeCurrentSegmentEntries(raceId).first().isEmpty())
    }

    @Test
    fun resetIsNotUndoableWithNothingInTheNewSegmentToTarget() = runTest {
        repository.recordEntry(raceId, HistoryAction.START, 101, note = null)
        repository.resetBibsMode(raceId)

        // The new segment starts empty, so there's nothing in it for Undo to target; it's a
        // no-op, which is what makes Reset itself effectively non-undoable, with no
        // special-casing needed.
        repository.undoMostRecent(raceId)

        assertEquals(2, db.historyLineDao().observeAllForRace(raceId).first().size)
        assertTrue(repository.observeCurrentSegmentEntries(raceId).first().isEmpty())
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
}
