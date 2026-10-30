package mobile.racemaster.ui.bibsmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.BIB_REQUIRED_ACTIONS
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.mule.BluetoothStateRepository
import mobile.racemaster.data.mule.BtPollingStatus
import mobile.racemaster.data.mule.ProgressEntry
import mobile.racemaster.data.mule.ProgressRepository
import mobile.racemaster.data.mule.ServerStatus
import mobile.racemaster.data.mule.ServerStatusRepository
import mobile.racemaster.data.mule.ServerStatusState
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.countDuplicateExtras
import mobile.racemaster.data.repository.duplicateBibNumbers
import mobile.racemaster.data.repository.expectedBibsAtLocation
import mobile.racemaster.data.repository.findDuplicateSplitRefs
import mobile.racemaster.data.repository.hasRealEntries
import mobile.racemaster.data.repository.isBibsActionAllowedAtLocation
import mobile.racemaster.data.repository.isRaceActive
import mobile.racemaster.data.repository.lineSyncState
import mobile.racemaster.data.repository.linesWithAnySync
import mobile.racemaster.data.repository.outstandingAtLocation
import mobile.racemaster.data.repository.unexpectedBibNumbers
import mobile.racemaster.data.repository.unexpectedBibWarning
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import mobile.racemaster.di.applicationContext
import mobile.racemaster.ui.btPollingStatusFlow
import mobile.racemaster.ui.components.EntryLogUi
import mobile.racemaster.ui.deviceNameFlow
import mobile.racemaster.util.Beeper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private data class RaceContext(
    val race: RaceEntity?,
    val entries: List<HistoryLineEntity>,
    val unsyncedCount: Int,
    val lastSyncedAtMillis: Long?,
    val linesWithAnySync: Set<Long>,
    val serverStatus: ServerStatusState,
    // This race's own shared progress records (bib/CP allocations from every contributing
    // phone, via server and/or BT mule — see ProgressRepository's own doc) — phase 4's data
    // source for the expected/outstanding/unexpected-bib computations below, replacing the old
    // configured-range model. Empty (not null) when nothing's been received yet for this race,
    // so downstream expectation functions' own "nothing to judge by" defaults apply uniformly.
    val progressEntries: List<ProgressEntry>,
    // See BibsModeUiState.otherOpenLocations' own doc.
    val openLocations: List<String>,
)

data class BibsModeUiState(
    val raceId: Long? = null,
    val raceLabel: String = "",
    val raceLocation: String = "",
    // Whether Bibs Mode has been started for the CURRENT segment specifically — false for a
    // fresh race, a race just switched into from a different mode, or a segment just Reset, in
    // which case the screen shows a Start button instead of the entry keypad/list (see
    // BibsModeScreen). Derived from whether `entries` (this segment's own rows) already contains
    // a Clock marker — NOT from RaceEntity.bibsModeStartedAtMillis, which is race-wide and so
    // goes stale the moment more than one segment can be open at once (see
    // RaceRepository.observeOpenLocations' own doc): resuming an already-started OTHER segment
    // via "Also open" must show the keypad immediately, not a stale Start button that would
    // insert a second, redundant Clock row into a segment that already has one; conversely
    // Relocating to a genuinely new segment must never inherit "started" from whichever segment
    // happened to set that race-wide field last.
    val started: Boolean = false,
    val currentDigits: String = "",
    // Which options the Event button's picker should offer — EVENT_PICKER_OPTIONS while there's
    // a just-auto-saved entry to retag, BIBS_STANDALONE_OPTIONS otherwise (see
    // BibsModeViewModel.onEventTypeSelected's own doc) — then further narrowed to whatever
    // isBibsActionAllowedAtLocation permits at the race's current location (e.g. Pass/Retire/
    // Ignore only at a CP).
    val eventOptions: List<HistoryAction> = EVENT_PICKER_OPTIONS,
    // Which HistoryAction a bib's 3rd digit currently auto-saves as (see
    // BibsModeViewModel.onDigit's own doc) — drives BibsModeScreen's DefaultBibsActionSelector,
    // operator-settable there with a single tap.
    val defaultBibsAction: HistoryAction = HistoryAction.FINISH,
    // Which buttons DefaultBibsActionSelector should actually render — BIBS_DEFAULT_ACTION_OPTIONS
    // narrowed by isBibsActionAllowedAtLocation for the race's current location: just [PASS] at a
    // CP, [START, FINISH] (no Pass) at Start or Finish.
    val defaultActionOptions: List<HistoryAction> = BIBS_DEFAULT_ACTION_OPTIONS,
    val nextSplitNumber: Int = 1,
    val dupCount: Int = 0,
    val entries: List<EntryLogUi> = emptyList(),
    val canUndo: Boolean = false,
    val raceInProgress: Boolean = false,
    val unsyncedCount: Int = 0,
    val lastSyncedAtMillis: Long? = null,
    // Phase 4: derived from this race's own shared progress records for this device's own
    // location (see data/repository/BibValidation.kt's expectedBibsAtLocation/outstandingAtLocation),
    // not a configured range. expectedCount is 0 when there's nothing to judge by yet (no
    // progress data received) — see BibsModeScreen for how that's treated as "hide this line"
    // rather than "zero expected".
    val expectedCount: Int = 0,
    val outstandingCount: Int = 0,
    // Only populated (and only meaningful) once few enough are left that listing them is
    // more useful than just the count — see BibsModeScreen.
    val outstandingBibs: List<Int> = emptyList(),
    // Distinct bib numbers involved in a duplicate log — empty (and hidden) when there are none.
    val duplicateBibNumbers: List<Int> = emptyList(),
    // Distinct bib numbers this device has recorded that aren't in the expected set for its own
    // location at all — flagged, never blocked (TODO.md's phase 4). Empty when there's nothing
    // to judge by yet, same as expectedCount/outstandingBibs.
    val unexpectedBibNumbers: List<Int> = emptyList(),
    // Shown as another header line (see ui/components/ServerStatusLine.kt) — server
    // connectivity matters here just as much as in Mule Mode, since this device pushes its
    // own recorded data to the server on the same schedule regardless of mode.
    val serverStatus: ServerStatusState = ServerStatusState(ServerStatus.UNKNOWN, null),
    // Every OTHER location Bibs Mode still has an un-Reset segment at (see
    // RaceRepository.observeOpenLocations' own doc) — resetting only ever closes the CURRENT
    // one, so this is what lets the operator see, and jump straight back to, one left behind by
    // an earlier Relocate rather than hunting for its exact name via Relocate's own history
    // dropdown.
    val otherOpenLocations: List<String> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class BibsModeViewModel(
    private val bibsModeRepository: BibsModeRepository,
    private val raceRepository: RaceRepository,
    private val settingsRepository: SettingsRepository,
    private val serverStatusRepository: ServerStatusRepository,
    private val progressRepository: ProgressRepository,
    bluetoothStateRepository: BluetoothStateRepository,
    private val beeper: Beeper,
) : ViewModel() {

    private val raceIdFlow: StateFlow<Long?> = settingsRepository.activeRaceId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Eagerly, not WhileSubscribed(5_000), like raceIdFlow above — onDigit needs to read the
    // current default synchronously via .value the instant it's called, which can happen before
    // a screen-driven WhileSubscribed collector would have started.
    private val defaultBibsActionFlow: StateFlow<HistoryAction> = settingsRepository.bibsAutoSaveAction
        .stateIn(viewModelScope, SharingStarted.Eagerly, HistoryAction.FINISH)

    val deviceName: StateFlow<String?> = deviceNameFlow(settingsRepository)

    val btPollingStatus: StateFlow<BtPollingStatus> = btPollingStatusFlow(bluetoothStateRepository)

    private val digitsFlow = MutableStateFlow("")
    // True whenever the digits currently on screen are already committed (either just
    // auto-saved, or redisplayed by undoLast) rather than mid-entry — the next onDigit call
    // clears them first instead of appending. Kept separate from canRetagFlow below: an
    // undo-repopulated bib is frozen for display purposes but isn't a live entry Event can
    // retag.
    private val digitsFrozenFlow = MutableStateFlow(false)
    // True only immediately after this device's own auto-save (never after undoLast) — gates
    // whether the Event button's selection retags that just-saved entry or logs a fresh
    // standalone one (see onEventTypeSelected).
    private val canRetagFlow = MutableStateFlow(false)

    init {
        // Hydrate the above three from whatever is actually on top of this race's history the
        // moment it's selected — not just what THIS ViewModel instance has personally
        // auto-saved. Without this, they default to blank/false on every fresh ViewModel (a
        // screen navigation away and back, the app backgrounded and killed under memory
        // pressure, a plain restart), even though the operator can still see a real entry
        // sitting on top of the list — e.g. a Retire from a prior session — and has no way to
        // tell Event "fix that one" once eventOptions has silently fallen back to
        // BIBS_STANDALONE_OPTIONS (which excludes Finish/Start/Retire, since none of them mean
        // anything without a live bib to retag). Runs once per race selection AND once again
        // every time this race's own location changes (a Relocate) — never on every entries
        // update, so it can't clobber the operator's own later typing, undo, or retag calls, but
        // a Relocate always moves this device onto a different (often empty) segment, so the
        // field needs to catch up to whatever — if anything — is already on top of THAT segment,
        // rather than keep showing a bib left over from the one just left.
        viewModelScope.launch {
            raceIdFlow.filterNotNull().flatMapLatest { raceId ->
                raceRepository.observeRace(raceId).map { it?.location }.distinctUntilChanged()
                    .map { raceId }
            }.collectLatest { raceId ->
                val topEntry = bibsModeRepository.observeCurrentSegmentEntries(raceId).first().firstOrNull()
                digitsFlow.value = topEntry?.bibNumber?.let { it.toString().padStart(MAX_BIB_DIGITS, '0') }.orEmpty()
                digitsFrozenFlow.value = topEntry?.bibNumber != null
                canRetagFlow.value = topEntry?.bibNumber != null
            }
        }
    }

    private val raceAndEntriesFlow = raceIdFlow.flatMapLatest { raceId ->
        if (raceId == null) {
            flowOf(RaceContext(null, emptyList(), 0, null, emptySet(), ServerStatusState(ServerStatus.UNKNOWN, null), emptyList(), emptyList()))
        } else {
            combine(
                raceRepository.observeRace(raceId),
                bibsModeRepository.observeCurrentSegmentEntries(raceId),
                bibsModeRepository.observeUnsyncedCount(raceId),
                // Triple-packed rather than added as the combine's own 6th/7th argument —
                // kotlinx coroutines' typed combine() overloads only go up to 5, and this stays
                // a plain, directly-typed lambda without needing MuleModeViewModel's own
                // Array<*>-based vararg + @Suppress("UNCHECKED_CAST") approach.
                combine(
                    bibsModeRepository.observeLastSyncedAtMillis(raceId),
                    serverStatusRepository.state,
                    progressRepository.current,
                ) { lastSyncedAtMillis, serverStatus, progress -> Triple(lastSyncedAtMillis, serverStatus, progress) },
                combine(
                    raceRepository.observeLineSyncs(raceId),
                    raceRepository.observeOpenLocations(raceId, HistoryMode.BIBS),
                ) { lineSyncs, openLocations -> lineSyncs to openLocations },
            ) { race, entries, unsyncedCount, (lastSyncedAtMillis, serverStatus, progress), (lineSyncs, openLocations) ->
                val progressEntries = progress?.takeIf { it.raceId == raceId }?.entries.orEmpty()
                RaceContext(race, entries, unsyncedCount, lastSyncedAtMillis, linesWithAnySync(lineSyncs), serverStatus, progressEntries, openLocations)
            }
        }
    }

    val uiState: StateFlow<BibsModeUiState> = combine(
        raceAndEntriesFlow,
        digitsFlow,
        canRetagFlow,
        defaultBibsActionFlow,
    ) { context, digits, canRetag, defaultBibsAction ->
        val (race, entries, unsyncedCount, lastSyncedAtMillis, linesWithAnySync, serverStatus, progressEntries, openLocations) = context
        val dupRefs = findDuplicateSplitRefs(entries)
        val raceLocation = race?.location.orEmpty()
        val expectedBibs = expectedBibsAtLocation(progressEntries, raceLocation)
        val outstanding = outstandingAtLocation(entries, progressEntries, raceLocation)
        BibsModeUiState(
            raceId = race?.id,
            raceLabel = race?.label.orEmpty(),
            raceLocation = race?.location.orEmpty(),
            started = entries.any { it.action == HistoryAction.CLOCK },
            currentDigits = digits,
            // Bib-required types (Finish/Start/Retire) only ever make sense against a real bib,
            // so they're withheld even while canRetag is otherwise true if the top entry itself
            // has none — a bib-less marker (Seniors/Juniors/...) can still be sitting on top
            // after undoLast exposes one, or after canRetag defaults false on a fresh ViewModel
            // (see this class's own init doc), with nothing here Event could retag into one of
            // them. Undo last is the only way back from there: it removes the marker outright
            // and exposes whatever bib-carrying entry was under it.
            eventOptions = (if (canRetag && entries.firstOrNull { it.action != HistoryAction.LOCATION }?.bibNumber != null) EVENT_PICKER_OPTIONS else BIBS_STANDALONE_OPTIONS)
                .filter { isBibsActionAllowedAtLocation(it, raceLocation) },
            defaultBibsAction = defaultBibsAction,
            defaultActionOptions = BIBS_DEFAULT_ACTION_OPTIONS.filter { isBibsActionAllowedAtLocation(it, raceLocation) },
            nextSplitNumber = race?.bibsModeNextSplit ?: 1,
            dupCount = countDuplicateExtras(entries),
            // LOCATION rows are deliberately left out of the rendered list — the race's own
            // current location is already echoed on its own line in EntryModeHeaderInfo right
            // above, so listing it again here too was just confusing noise. Still fully present
            // in (and undoable via) the underlying `entries` list this is filtered from —
            // eventOptions/dupCount/canUndo above are all computed from that unfiltered list,
            // not this one, so undoing a relocate the operator can't see listed still works
            // exactly as before.
            entries = entries.filterNot { it.action == HistoryAction.LOCATION }.map {
                EntryLogUi(
                    id = it.id,
                    bibNumber = it.bibNumber,
                    splitNumber = it.splitNumber,
                    type = it.action,
                    note = it.note,
                    dupSplitRefs = dupRefs[it.id].orEmpty(),
                    syncState = lineSyncState(it.syncedAtMillis, it.lineNumber in linesWithAnySync),
                    expectationWarning = unexpectedBibWarning(it.bibNumber, expectedBibs),
                )
            },
            canUndo = entries.hasRealEntries(),
            raceInProgress = isRaceActive(race?.timeModeStartedAtMillis, race?.bibsModeStartedAtMillis),
            unsyncedCount = unsyncedCount,
            lastSyncedAtMillis = lastSyncedAtMillis,
            expectedCount = expectedBibs.size,
            outstandingCount = outstanding.size,
            outstandingBibs = outstanding,
            duplicateBibNumbers = duplicateBibNumbers(entries),
            unexpectedBibNumbers = unexpectedBibNumbers(entries, progressEntries, raceLocation),
            serverStatus = serverStatus,
            otherOpenLocations = openLocations.filterNot { it == raceLocation },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BibsModeUiState())

    // A device now records against exactly one race for its whole lifetime (see TODO.md's
    // phase 1 — the course concept is gone), so Start no longer needs to resolve WHICH row to
    // record into — it's always this device's own active race. Guarded against uiState's own
    // derived `started` (this current segment's own rows), not RaceEntity.bibsModeStartedAtMillis
    // (see BibsModeUiState.started's own doc for why that field alone is unsafe here: it's
    // race-wide, so a segment resumed via "Also open" while a DIFFERENT segment happens to still
    // hold a truthy value there would otherwise skip writing this segment's own Clock row
    // entirely). Effectively unreachable through the main button in normal use (the button itself
    // only shows once uiState.started is false) — kept as a backstop since Race History's own
    // "Resume" action (RaceRepository.switchActiveRace) could in principle still land here before
    // this screen's own reactive state has caught up.
    fun startBibsMode() {
        val raceId = raceIdFlow.value ?: return
        if (uiState.value.started) return
        viewModelScope.launch { bibsModeRepository.startBibsMode(raceId) }
    }

    // Jumps directly to a DIFFERENT location this mode still has an un-Reset segment at (see
    // RaceRepository.observeOpenLocations/BibsModeUiState.otherOpenLocations' own docs) — reuses
    // recordModeStart's own resume-on-relocate-back behavior, exactly what Relocate's own
    // location field already triggers for a same-mode move, rather than a second code path.
    fun goToLocation(location: String) {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { raceRepository.recordModeStart(raceId, AppMode.BIBS, location) }
    }

    // A fresh digit always starts a new entry — if the digits on screen are a frozen (already
    // committed, or undo-repopulated) value, clear them first rather than appending onto a
    // number that's already 3 digits long. Reaching the 3rd digit immediately auto-saves it as
    // whichever action defaultBibsActionFlow currently holds (FINISH unless the operator has
    // picked Start via BibsModeScreen's DefaultBibsActionSelector — see this file's own doc /
    // TODO.md's "Re-jig bibs mode" for why FINISH is the default): the vast majority of entries
    // are ordinary finishes, and a wrong guess is one Event tap away from being corrected via
    // onEventTypeSelected rather than costing an extra keystroke on every single entry.
    fun onDigit(digit: Int) {
        if (digitsFrozenFlow.value) {
            digitsFlow.value = ""
            digitsFrozenFlow.value = false
            canRetagFlow.value = false
        }
        if (digitsFlow.value.length >= MAX_BIB_DIGITS) return
        digitsFlow.value += digit.toString()
        if (digitsFlow.value.length == MAX_BIB_DIGITS) {
            val raceId = raceIdFlow.value ?: return
            val bib = digitsFlow.value.toIntOrNull() ?: return
            viewModelScope.launch {
                bibsModeRepository.recordEntry(raceId, defaultBibsActionFlow.value, bib, note = null)
                digitsFrozenFlow.value = true
                canRetagFlow.value = true
                beeper.beep()
            }
        }
    }

    fun onDefaultBibsActionSelected(action: HistoryAction) {
        viewModelScope.launch { settingsRepository.setBibsAutoSaveAction(action) }
    }

    fun onBackspace() {
        // A frozen value has already been saved — there's nothing left to edit character by
        // character, so treat Backspace the same as Clear rather than lopping a digit off an
        // already-committed number.
        if (digitsFrozenFlow.value) { onClear(); return }
        digitsFlow.value = digitsFlow.value.dropLast(1)
    }

    fun onClear() {
        digitsFlow.value = ""
        digitsFrozenFlow.value = false
        canRetagFlow.value = false
    }

    // Bibs' Event button is dual-purpose depending on whether the picked type needs a bib (see
    // BIB_REQUIRED_ACTIONS) and whether there's a just-auto-saved entry on screen to apply it to
    // (see canRetagFlow). Picking a bib-required type (Finish/Start/Retire) with one retags that
    // entry in place, keeping its bib — this is what makes a non-finish entry only 5 keystrokes
    // (3 digits + Event + the picked type) instead of the old flow's 6. Picking a marker type
    // (Seniors/Juniors/Male/Female/Ignore) never retags, even with a bib entry on screen: it
    // always logs a fresh, bib-less standalone entry ON TOP of whatever's there, leaving that
    // bib entry untouched underneath — e.g. "001 [auto-saves Finish] Event Ignore 002 [auto-saves
    // Finish]" ends up as three separate rows (001/Finish, Ignore, 002/Finish), not a
    // 001/Finish overwritten into Ignore. A marker is a flag about the moment ("the next runner
    // through was ignored"), not a correction to the last bib, so it should never cost that bib
    // its own entry.
    fun onEventTypeSelected(type: HistoryAction) {
        val raceId = raceIdFlow.value ?: return
        val needsBib = type in BIB_REQUIRED_ACTIONS
        viewModelScope.launch {
            if (canRetagFlow.value && needsBib) {
                // The entry field keeps reflecting the top of the list after this, same as
                // undoLast — never blanked outright, since the operator still needs to see what
                // they can act on next.
                val targetId = uiState.value.entries.firstOrNull()?.id ?: return@launch
                val bib = digitsFlow.value.toIntOrNull()
                bibsModeRepository.updateEntry(targetId, bib, type, note = null)
                digitsFlow.value = bib?.let { it.toString().padStart(MAX_BIB_DIGITS, '0') }.orEmpty()
                digitsFrozenFlow.value = bib != null
                // canRetagFlow stays true (not reset to false): a picked type is only a best
                // guess until the operator sees it land, e.g. meaning to tap Start but hitting
                // Retire — leaving Event still targeting this same entry (by id, so it keeps
                // working whether or not a bib is currently showing) is what makes "event retire
                // event start" a correction rather than a second, wrong entry to undo.
            } else {
                bibsModeRepository.recordEntry(raceId, type, bibNumber = null, note = null)
                digitsFlow.value = ""
                digitsFrozenFlow.value = false
                canRetagFlow.value = false
            }
            beeper.beep()
        }
    }

    fun undoLast() {
        val raceId = raceIdFlow.value ?: return
        // The entry field should show whatever the operator can now SEE on top of the list —
        // the newly-exposed entry (index 1 of the pre-undo list, since undo always hides
        // index 0 — see EntryLogModeEngine.undoMostRecent's own doc), not the one that was just
        // undone and is no longer visible at all. Snapshotted from the pre-undo list rather than
        // read back from uiState after the repository call returns: the entries Flow updates
        // asynchronously off Room's own invalidation, so it isn't guaranteed to already reflect
        // the undo by the time this suspend call resumes, whereas "index 1 becomes the new
        // index 0" is already known from the list as it stands right now. canRetagFlow follows
        // it (true, not false) so Event applies to that same newly-exposed entry, matching what
        // the operator sees — undoing a bad Finish and then wanting to immediately mark the
        // real last one as Retire is exactly this two-step correction.
        val newlyExposedBib = uiState.value.entries.getOrNull(1)?.bibNumber
        viewModelScope.launch {
            bibsModeRepository.undoMostRecent(raceId)
            digitsFlow.value = newlyExposedBib?.let { it.toString().padStart(MAX_BIB_DIGITS, '0') }.orEmpty()
            digitsFrozenFlow.value = newlyExposedBib != null
            canRetagFlow.value = newlyExposedBib != null
        }
    }

    // See RaceRepository.closeCurrentSegment's own doc for the walk-back-by-segment behavior a
    // Reset press triggers.
    fun resetBibsMode() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { bibsModeRepository.resetBibsMode(raceId) }
    }

    override fun onCleared() {
        beeper.release()
    }

    companion object {
        private const val MAX_BIB_DIGITS = 3

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                BibsModeViewModel(
                    container.bibsModeRepository,
                    container.raceRepository,
                    container.settingsRepository,
                    container.serverStatusRepository,
                    container.progressRepository,
                    container.bluetoothStateRepository,
                    Beeper(applicationContext()),
                )
            }
        }
    }
}
