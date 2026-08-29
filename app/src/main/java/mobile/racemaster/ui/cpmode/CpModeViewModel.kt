package mobile.racemaster.ui.cpmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.RaceEntity
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
    // True once a bib is entered — both of CP's two actions (Pass, Retire) always require one,
    // unlike Bibs' Submit button whose enablement depends on whichever pending type is
    // currently selected.
    val canSubmit: Boolean = false,
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
    private val beeper: Beeper,
) : ViewModel() {

    private val raceIdFlow: StateFlow<Long?> = settingsRepository.activeRaceId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val deviceName: StateFlow<String?> = settingsRepository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val digitsFlow = MutableStateFlow("")

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
    ) { context, digits ->
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
            canSubmit = race != null && race.cpModeStoppedAtMillis == null && digits.isNotEmpty(),
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

    fun onDigit(digit: Int) {
        if (digitsFlow.value.length >= MAX_BIB_DIGITS) return
        digitsFlow.value += digit.toString()
    }

    fun onBackspace() {
        digitsFlow.value = digitsFlow.value.dropLast(1)
    }

    fun onClear() {
        digitsFlow.value = ""
    }

    // [action] is HistoryAction.PASS or HistoryAction.RETIRE, whichever button was tapped —
    // both always need the bib currently entered, unlike Bibs' submit() which stages a pending
    // type first (see CpModeScreen for why CP's two fixed buttons don't need that staging step).
    fun submit(action: HistoryAction) {
        val raceId = raceIdFlow.value ?: return
        val bib = digitsFlow.value.toIntOrNull() ?: return
        viewModelScope.launch {
            cpModeRepository.recordEntry(raceId, action, bib, note = null)
            digitsFlow.value = ""
            beeper.beep()
        }
    }

    fun undoLast() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { cpModeRepository.undoMostRecent(raceId) }
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
                    Beeper(applicationContext()),
                )
            }
        }
    }
}
