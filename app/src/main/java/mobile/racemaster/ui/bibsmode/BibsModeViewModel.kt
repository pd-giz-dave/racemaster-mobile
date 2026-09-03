package mobile.racemaster.ui.bibsmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.BIB_REQUIRED_ACTIONS
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.mule.BluetoothStateRepository
import mobile.racemaster.data.mule.BtPollingStatus
import mobile.racemaster.data.mule.ServerStatus
import mobile.racemaster.data.mule.ServerStatusRepository
import mobile.racemaster.data.mule.ServerStatusState
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.accountedForRecordCount
import mobile.racemaster.data.repository.countDuplicateExtras
import mobile.racemaster.data.repository.duplicateBibNumbers
import mobile.racemaster.data.repository.findDuplicateSplitRefs
import mobile.racemaster.data.repository.hasRealEntries
import mobile.racemaster.data.repository.isRaceInProgress
import mobile.racemaster.data.repository.lineSyncState
import mobile.racemaster.data.repository.linesWithAnySync
import mobile.racemaster.data.repository.outstandingBibs
import mobile.racemaster.data.repository.rangeWarningMessage
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import mobile.racemaster.di.applicationContext
import mobile.racemaster.ui.components.EntryLogUi
import mobile.racemaster.util.Beeper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private data class RaceContext(
    val race: RaceEntity?,
    val entries: List<HistoryLineEntity>,
    val unsyncedCount: Int,
    val lastSyncedAtMillis: Long?,
    val linesWithAnySync: Set<Long>,
    val serverStatus: ServerStatusState,
)

data class BibsModeUiState(
    val raceId: Long? = null,
    val raceLabel: String = "",
    val raceLocation: String = "",
    // Whether Bibs Mode has been started for this segment — false for a fresh race, a race
    // just switched into from a different mode, or one just Reset, in which case the screen
    // shows a Start button instead of the entry keypad/list (see BibsModeScreen). Sourced
    // directly from RaceEntity.bibsModeStartedAtMillis, same as CpModeUiState.started — see
    // that field's own doc for why its Clock marker's mere presence isn't what this is derived
    // from, even though Start still writes one.
    val started: Boolean = false,
    val currentDigits: String = "",
    // Which options the Event button's picker should offer — EVENT_PICKER_OPTIONS while there's
    // a just-auto-saved entry to retag, BIBS_STANDALONE_OPTIONS otherwise (see
    // BibsModeViewModel.onEventTypeSelected's own doc).
    val eventOptions: List<HistoryAction> = EVENT_PICKER_OPTIONS,
    val nextSplitNumber: Int = 1,
    val dupCount: Int = 0,
    val entries: List<EntryLogUi> = emptyList(),
    val canUndo: Boolean = false,
    val stopped: Boolean = false,
    val raceInProgress: Boolean = false,
    val unsyncedCount: Int = 0,
    val lastSyncedAtMillis: Long? = null,
    // Set from the race details screen — so the operator knows what to expect, and how many
    // are still outstanding.
    val firstBibNumber: Int? = null,
    val expectedRunnerCount: Int? = null,
    val finishedCount: Int = 0,
    // Only populated (and only meaningful) once few enough are left that listing them is
    // more useful than just the count — see BibsModeScreen.
    val outstandingBibs: List<Int> = emptyList(),
    // Distinct bib numbers involved in a duplicate log — empty (and hidden) when there are none.
    val duplicateBibNumbers: List<Int> = emptyList(),
    // Shown as another header line (see ui/components/ServerStatusLine.kt) — server
    // connectivity matters here just as much as in Mule Mode, since this device pushes its
    // own recorded data to the server on the same schedule regardless of mode.
    val serverStatus: ServerStatusState = ServerStatusState(ServerStatus.UNKNOWN, null),
)

@OptIn(ExperimentalCoroutinesApi::class)
class BibsModeViewModel(
    private val bibsModeRepository: BibsModeRepository,
    private val raceRepository: RaceRepository,
    settingsRepository: SettingsRepository,
    private val serverStatusRepository: ServerStatusRepository,
    bluetoothStateRepository: BluetoothStateRepository,
    private val beeper: Beeper,
) : ViewModel() {

    private val raceIdFlow: StateFlow<Long?> = settingsRepository.activeRaceId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val deviceName: StateFlow<String?> = settingsRepository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // See TimeModeViewModel.btPollingStatus's own doc — device-wide, not tied to race selection.
    val btPollingStatus: StateFlow<BtPollingStatus> = combine(
        bluetoothStateRepository.advertisingWarning,
        bluetoothStateRepository.lastPolledAtMillis,
        ::BtPollingStatus,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BtPollingStatus())

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

    private val raceAndEntriesFlow = raceIdFlow.flatMapLatest { raceId ->
        if (raceId == null) {
            flowOf(RaceContext(null, emptyList(), 0, null, emptySet(), ServerStatusState(ServerStatus.UNKNOWN, null)))
        } else {
            combine(
                raceRepository.observeRace(raceId),
                bibsModeRepository.observeCurrentSegmentEntries(raceId),
                bibsModeRepository.observeUnsyncedCount(raceId),
                // Paired rather than added as the combine's own 6th argument — kotlinx
                // coroutines' typed combine() overloads only go up to 5, and this stays a
                // plain, directly-typed lambda without needing MuleModeViewModel's own
                // Array<*>-based vararg + @Suppress("UNCHECKED_CAST") approach.
                combine(bibsModeRepository.observeLastSyncedAtMillis(raceId), serverStatusRepository.state) { lastSyncedAtMillis, serverStatus ->
                    lastSyncedAtMillis to serverStatus
                },
                raceRepository.observeLineSyncs(raceId),
            ) { race, entries, unsyncedCount, (lastSyncedAtMillis, serverStatus), lineSyncs ->
                RaceContext(race, entries, unsyncedCount, lastSyncedAtMillis, linesWithAnySync(lineSyncs), serverStatus)
            }
        }
    }

    val uiState: StateFlow<BibsModeUiState> = combine(
        raceAndEntriesFlow,
        digitsFlow,
        canRetagFlow,
    ) { context, digits, canRetag ->
        val (race, entries, unsyncedCount, lastSyncedAtMillis, linesWithAnySync, serverStatus) = context
        val dupRefs = findDuplicateSplitRefs(entries)
        val outstanding = outstandingBibs(entries, race?.bibsRangeStart, race?.bibsRangeCount)
        BibsModeUiState(
            raceId = race?.id,
            raceLabel = race?.label.orEmpty(),
            raceLocation = race?.location.orEmpty(),
            started = race?.bibsModeStartedAtMillis != null,
            currentDigits = digits,
            eventOptions = if (canRetag) EVENT_PICKER_OPTIONS else BIBS_STANDALONE_OPTIONS,
            nextSplitNumber = race?.bibsModeNextSplit ?: 1,
            dupCount = countDuplicateExtras(entries),
            entries = entries.map {
                EntryLogUi(
                    id = it.id,
                    bibNumber = it.bibNumber,
                    splitNumber = it.splitNumber,
                    type = it.action,
                    note = it.note,
                    dupSplitRefs = dupRefs[it.id].orEmpty(),
                    syncState = lineSyncState(it.syncedAtMillis, it.lineNumber in linesWithAnySync),
                    rangeWarning = rangeWarningMessage(it.bibNumber, race?.bibsRangeStart, race?.bibsRangeCount),
                )
            },
            canUndo = entries.hasRealEntries(),
            stopped = race?.bibsModeStoppedAtMillis != null,
            raceInProgress = isRaceInProgress(
                race?.timeModeStartedAtMillis,
                race?.timeModeStoppedAtMillis,
                race?.bibsModeStartedAtMillis,
                race?.bibsModeStoppedAtMillis,
                race?.cpModeStartedAtMillis,
                race?.cpModeStoppedAtMillis,
            ),
            unsyncedCount = unsyncedCount,
            lastSyncedAtMillis = lastSyncedAtMillis,
            firstBibNumber = race?.bibsRangeStart,
            expectedRunnerCount = race?.bibsRangeCount,
            finishedCount = accountedForRecordCount(entries),
            outstandingBibs = outstanding,
            duplicateBibNumbers = duplicateBibNumbers(entries),
            serverStatus = serverStatus,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BibsModeUiState())

    fun startBibsMode() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { bibsModeRepository.startBibsMode(raceId) }
    }

    // A fresh digit always starts a new entry — if the digits on screen are a frozen (already
    // committed, or undo-repopulated) value, clear them first rather than appending onto a
    // number that's already 3 digits long. Reaching the 3rd digit immediately auto-saves it as
    // a FINISH (see this file's own doc / TODO.md's "Re-jig bibs mode" for why): the vast
    // majority of entries are ordinary finishes, and a wrong guess is one Event tap away from
    // being corrected via onEventTypeSelected rather than costing an extra keystroke on every
    // single entry.
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
                bibsModeRepository.recordEntry(raceId, HistoryAction.FINISH, bib, note = null)
                digitsFrozenFlow.value = true
                canRetagFlow.value = true
                beeper.beep()
            }
        }
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

    // Bibs' Event button is dual-purpose depending on whether there's a just-auto-saved entry
    // on screen (see canRetagFlow): with one, the picked type retags that entry in place
    // (keeping its bib if the new type still needs one, e.g. Finish -> Retire) — this is what
    // makes a non-finish entry only 5 keystrokes (3 digits + Event + the picked type) instead of
    // the old flow's 6. With no pending entry (nothing typed, or just cleared/undone), the
    // picker instead only offers BIBS_STANDALONE_OPTIONS and logs a fresh, bib-less marker entry
    // immediately — the same standalone marker logging the old pendingType-then-Submit flow gave
    // Seniors/Juniors/Male/Female/Ignore.
    fun onEventTypeSelected(type: HistoryAction) {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch {
            if (canRetagFlow.value) {
                val targetId = uiState.value.entries.firstOrNull()?.id ?: return@launch
                val needsBib = type in BIB_REQUIRED_ACTIONS
                val bib = if (needsBib) digitsFlow.value.toIntOrNull() else null
                bibsModeRepository.updateEntry(targetId, bib, type, note = null)
            } else {
                bibsModeRepository.recordEntry(raceId, type, bibNumber = null, note = null)
            }
            digitsFlow.value = ""
            digitsFrozenFlow.value = false
            canRetagFlow.value = false
            beeper.beep()
        }
    }

    fun undoLast() {
        val raceId = raceIdFlow.value ?: return
        // Snapshotted before the undo call so the just-undone row's own bib (rather than
        // whatever becomes newly topmost afterward) is what comes back into the entry field —
        // "instant feedback of the last number accepted" per TODO.md. Never retag-able (there's
        // nothing live left to retag once it's undone), so canRetagFlow stays false.
        val undoneBib = uiState.value.entries.firstOrNull()?.bibNumber
        viewModelScope.launch {
            bibsModeRepository.undoMostRecent(raceId)
            digitsFlow.value = undoneBib?.let { it.toString().padStart(MAX_BIB_DIGITS, '0') }.orEmpty()
            digitsFrozenFlow.value = undoneBib != null
            canRetagFlow.value = false
        }
    }

    fun stopBibsMode() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { bibsModeRepository.stopBibsMode(raceId) }
    }

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
                    container.bluetoothStateRepository,
                    Beeper(applicationContext()),
                )
            }
        }
    }
}
