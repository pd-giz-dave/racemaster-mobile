package mobile.racemaster.ui.cpmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.mule.BluetoothStateRepository
import mobile.racemaster.data.mule.BtPollingStatus
import mobile.racemaster.data.mule.ServerStatus
import mobile.racemaster.data.mule.ServerStatusRepository
import mobile.racemaster.data.mule.ServerStatusState
import mobile.racemaster.data.repository.CpModeRepository
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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

data class CpModeUiState(
    val raceId: Long? = null,
    val raceLabel: String = "",
    val raceLocation: String = "",
    // Whether CP Mode has been started for this segment — sourced directly from
    // RaceEntity.cpModeStartedAtMillis, same as BibsModeUiState.started (see that field's own
    // doc for why: it's what lets undoing CP's very first Pass/Retire leave the screen still
    // showing the keypad rather than reverting to a pre-Start state).
    val started: Boolean = false,
    val currentDigits: String = "",
    val nextSplitNumber: Int = 1,
    val dupCount: Int = 0,
    val entries: List<EntryLogUi> = emptyList(),
    // True only immediately after this device auto-saved the currently-displayed bib as a Pass
    // (see CpModeViewModel.canRetagFlow) — gates the retag button, which now retags that entry
    // in place rather than submitting a fresh one.
    val canRetag: Boolean = false,
    // "Pass" once the top entry is already a Retire — so an accidental Retire can be
    // re-instated as a Pass — "Retire" otherwise, the normal correction direction. See
    // CpModeViewModel.toggleLastRetag's own doc.
    val retagButtonLabel: String = "Retire",
    val canUndo: Boolean = false,
    val stopped: Boolean = false,
    val raceInProgress: Boolean = false,
    val unsyncedCount: Int = 0,
    val lastSyncedAtMillis: Long? = null,
    val firstBibNumber: Int? = null,
    val expectedRunnerCount: Int? = null,
    val finishedCount: Int = 0,
    val outstandingBibs: List<Int> = emptyList(),
    val duplicateBibNumbers: List<Int> = emptyList(),
    // Shown as another header line (see ui/components/ServerStatusLine.kt) — server
    // connectivity matters here just as much as in Mule Mode, since this device pushes its
    // own recorded data to the server on the same schedule regardless of mode.
    val serverStatus: ServerStatusState = ServerStatusState(ServerStatus.UNKNOWN, null),
)

@OptIn(ExperimentalCoroutinesApi::class)
class CpModeViewModel(
    private val cpModeRepository: CpModeRepository,
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
    // See BibsModeViewModel's own doc for both of these — same state machine, just with PASS/
    // RETIRE in place of FINISH/the Event picker.
    private val digitsFrozenFlow = MutableStateFlow(false)
    private val canRetagFlow = MutableStateFlow(false)

    init {
        // See BibsModeViewModel's own init doc for why this hydration exists: without it,
        // canRetagFlow/digitsFrozenFlow default to false/blank on every fresh ViewModel (a
        // screen navigation away and back, the app backgrounded and killed under memory
        // pressure, a plain restart) even when a real Pass or Retire is still sitting on top of
        // the list — leaving the toggle button visibly relabelled correctly (that part reads
        // straight from the persisted entries) but disabled, so it can't actually be tapped.
        viewModelScope.launch {
            raceIdFlow.filterNotNull().distinctUntilChanged().collectLatest { raceId ->
                val topEntry = cpModeRepository.observeCurrentSegmentEntries(raceId).first().firstOrNull()
                digitsFlow.value = topEntry?.bibNumber?.let { it.toString().padStart(MAX_BIB_DIGITS, '0') }.orEmpty()
                digitsFrozenFlow.value = topEntry?.bibNumber != null
                canRetagFlow.value = topEntry?.bibNumber != null
            }
        }
    }

    private val raceAndEntriesFlow = raceIdFlow.flatMapLatest { raceId ->
        if (raceId == null) {
            flowOf(RaceContext(null, emptyList(), 0, null, emptySet(), ServerStatusState(ServerStatus.UNKNOWN, null)))
        } else {
            combine(
                raceRepository.observeRace(raceId),
                cpModeRepository.observeCurrentSegmentEntries(raceId),
                cpModeRepository.observeUnsyncedCount(raceId),
                // Paired rather than added as the combine's own 6th argument — kotlinx
                // coroutines' typed combine() overloads only go up to 5, and this stays a
                // plain, directly-typed lambda without needing MuleModeViewModel's own
                // Array<*>-based vararg + @Suppress("UNCHECKED_CAST") approach.
                combine(cpModeRepository.observeLastSyncedAtMillis(raceId), serverStatusRepository.state) { lastSyncedAtMillis, serverStatus ->
                    lastSyncedAtMillis to serverStatus
                },
                raceRepository.observeLineSyncs(raceId),
            ) { race, entries, unsyncedCount, (lastSyncedAtMillis, serverStatus), lineSyncs ->
                RaceContext(race, entries, unsyncedCount, lastSyncedAtMillis, linesWithAnySync(lineSyncs), serverStatus)
            }
        }
    }

    val uiState: StateFlow<CpModeUiState> = combine(
        raceAndEntriesFlow,
        digitsFlow,
        canRetagFlow,
    ) { context, digits, canRetag ->
        val (race, entries, unsyncedCount, lastSyncedAtMillis, linesWithAnySync, serverStatus) = context
        val dupRefs = findDuplicateSplitRefs(entries)
        val outstanding = outstandingBibs(entries, race?.bibsRangeStart, race?.bibsRangeCount)
        CpModeUiState(
            raceId = race?.id,
            raceLabel = race?.label.orEmpty(),
            raceLocation = race?.location.orEmpty(),
            started = race?.cpModeStartedAtMillis != null,
            currentDigits = digits,
            nextSplitNumber = race?.cpModeNextSplit ?: 1,
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
            canRetag = canRetag,
            retagButtonLabel = if (entries.firstOrNull()?.action == HistoryAction.RETIRE) "Pass" else "Retire",
            canUndo = entries.hasRealEntries(),
            stopped = race?.cpModeStoppedAtMillis != null,
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CpModeUiState())

    fun startCpMode() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { cpModeRepository.startCpMode(raceId) }
    }

    // Auto-saves as a PASS the moment the 3rd digit is typed — see BibsModeViewModel.onDigit's
    // own doc for the identical reasoning/state machine (digitsFrozenFlow/canRetagFlow), applied
    // here with PASS in place of FINISH and no Event picker (CP only ever needs the one
    // alternative, RETIRE — see toggleLastRetag).
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
                cpModeRepository.recordEntry(raceId, HistoryAction.PASS, bib, note = null)
                digitsFrozenFlow.value = true
                canRetagFlow.value = true
                beeper.beep()
            }
        }
    }

    fun onBackspace() {
        if (digitsFrozenFlow.value) { onClear(); return }
        digitsFlow.value = digitsFlow.value.dropLast(1)
    }

    fun onClear() {
        digitsFlow.value = ""
        digitsFrozenFlow.value = false
        canRetagFlow.value = false
    }

    // Retags the top entry between Pass and Retire, keeping its bib — only callable while
    // canRetagFlow is true (the button is disabled otherwise, see CpModeScreen). The direction
    // is whatever the button doesn't currently say the entry is (see
    // CpModeUiState.retagButtonLabel): normally Pass -> Retire, making a Retire 4 keystrokes
    // total (3 digits + this tap) instead of the old flow's 4 typed digits plus a separate
    // Retire tap that re-read the keypad — but also Retire -> Pass, so an accidental Retire tap
    // can be undone by tapping the same (now relabelled) button again rather than needing Undo
    // last, which would also throw away the bib itself.
    fun toggleLastRetag() {
        if (!canRetagFlow.value) return
        viewModelScope.launch {
            val target = uiState.value.entries.firstOrNull() ?: return@launch
            val newType = if (target.type == HistoryAction.RETIRE) HistoryAction.PASS else HistoryAction.RETIRE
            val bib = digitsFlow.value.toIntOrNull()
            cpModeRepository.updateEntry(target.id, bib, newType, note = null)
            // Leave the field showing that same bib rather than blanking it — it's still the
            // top of the list, just now shown as the other type. canRetagFlow is left true too
            // (see BibsModeViewModel.onEventTypeSelected's own doc for the general rule) rather
            // than reset to false, so a second tap can flip it right back again.
            digitsFrozenFlow.value = true
            beeper.beep()
        }
    }

    // See BibsModeViewModel.undoLast's own doc for why this reads index 1 of the pre-undo list
    // (the entry that becomes newly exposed on top once undo hides index 0) rather than index 0
    // itself, and why canRetagFlow follows it as true — Retire should apply to whatever the
    // operator can now see there, not to the row that just disappeared.
    fun undoLast() {
        val raceId = raceIdFlow.value ?: return
        val newlyExposedBib = uiState.value.entries.getOrNull(1)?.bibNumber
        viewModelScope.launch {
            cpModeRepository.undoMostRecent(raceId)
            digitsFlow.value = newlyExposedBib?.let { it.toString().padStart(MAX_BIB_DIGITS, '0') }.orEmpty()
            digitsFrozenFlow.value = newlyExposedBib != null
            canRetagFlow.value = newlyExposedBib != null
        }
    }

    fun stopCpMode() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { cpModeRepository.stopCpMode(raceId) }
    }

    fun resetCpMode() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { cpModeRepository.resetCpMode(raceId) }
    }

    override fun onCleared() {
        beeper.release()
    }

    companion object {
        private const val MAX_BIB_DIGITS = 3

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                CpModeViewModel(
                    container.cpModeRepository,
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
