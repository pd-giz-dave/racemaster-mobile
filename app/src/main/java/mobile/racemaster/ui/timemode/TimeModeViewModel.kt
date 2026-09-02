package mobile.racemaster.ui.timemode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.mule.BluetoothStateRepository
import mobile.racemaster.data.mule.BtPollingStatus
import mobile.racemaster.data.mule.ServerStatus
import mobile.racemaster.data.mule.ServerStatusRepository
import mobile.racemaster.data.mule.ServerStatusState
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.TimeModeRepository
import mobile.racemaster.data.repository.LineSyncState
import mobile.racemaster.data.repository.isRaceInProgress
import mobile.racemaster.data.repository.lineSyncState
import mobile.racemaster.data.repository.linesWithAnySync
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import mobile.racemaster.di.applicationContext
import mobile.racemaster.util.Beeper
import mobile.racemaster.util.tickerFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FinishSplitUi(
    val id: Long,
    // Null for a Stop row — see HistoryLineEntity.splitNumber's own doc — displayed as "–" via
    // formatSplitRef, with the action column (see SplitRow) carrying "Stop" instead.
    val splitNumber: Int?,
    val action: HistoryAction,
    val elapsedMillis: Long,
    val note: String?,
    val syncState: LineSyncState,
)

data class TimeModeUiState(
    val raceId: Long? = null,
    val raceLabel: String = "",
    val raceLocation: String = "",
    val stopwatchStarted: Boolean = false,
    val stopwatchStopped: Boolean = false,
    val liveElapsedMillis: Long = 0L,
    val nextSplitNumber: Int = 1,
    val splits: List<FinishSplitUi> = emptyList(),
    val canUndo: Boolean = false,
    val raceInProgress: Boolean = false,
    val unsyncedCount: Int = 0,
    val lastSyncedAtMillis: Long? = null,
    // Set from the race details screen — mirrors BibsModeUiState's fields exactly (form and
    // feedback wording are meant to be identical between the two modes), even though Time
    // Mode itself never actually reads firstBibNumber for anything.
    val firstBibNumber: Int? = null,
    val expectedRunnerCount: Int? = null,
    val finishedCount: Int = 0,
    // Shown as another header line (see ui/components/ServerStatusLine.kt) — server
    // connectivity matters here just as much as in Mule Mode, since this device pushes its
    // own recorded data to the server on the same schedule regardless of mode.
    val serverStatus: ServerStatusState = ServerStatusState(ServerStatus.UNKNOWN, null),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TimeModeViewModel(
    private val timeModeRepository: TimeModeRepository,
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

    // Device-wide, not tied to whether a race is selected — same reasoning as deviceName above;
    // whether this phone is actually being polled over BT has nothing to do with race state.
    val btPollingStatus: StateFlow<BtPollingStatus> = combine(
        bluetoothStateRepository.advertisingWarning,
        bluetoothStateRepository.lastPolledAtMillis,
        ::BtPollingStatus,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BtPollingStatus())

    val uiState: StateFlow<TimeModeUiState> = raceIdFlow
        .flatMapLatest { raceId ->
            if (raceId == null) {
                flowOf(TimeModeUiState())
            } else {
                val muleStatusFlow = combine(
                    timeModeRepository.observeUnsyncedCount(raceId),
                    timeModeRepository.observeLastSyncedAtMillis(raceId),
                    serverStatusRepository.state,
                ) { unsyncedCount, lastSyncedAtMillis, serverStatus -> Triple(unsyncedCount, lastSyncedAtMillis, serverStatus) }

                combine(
                    raceRepository.observeRace(raceId),
                    timeModeRepository.observeCurrentSegmentSplits(raceId),
                    tickerFlow,
                    muleStatusFlow,
                    raceRepository.observeLineSyncs(raceId),
                ) { race, splits, now, (unsyncedCount, lastSyncedAtMillis, serverStatus), lineSyncs ->
                    val linesWithAnySync = linesWithAnySync(lineSyncs)
                    val startedAt = race?.timeModeStartedAtMillis
                    val stoppedAt = race?.timeModeStoppedAtMillis
                    val liveElapsed = when {
                        startedAt == null -> 0L
                        stoppedAt != null -> stoppedAt - startedAt
                        else -> now - startedAt
                    }
                    TimeModeUiState(
                        raceId = raceId,
                        raceLabel = race?.label.orEmpty(),
                        raceLocation = race?.location.orEmpty(),
                        stopwatchStarted = startedAt != null,
                        stopwatchStopped = stoppedAt != null,
                        liveElapsedMillis = liveElapsed,
                        nextSplitNumber = race?.timeModeNextSplit ?: 1,
                        splits = splits.map {
                            FinishSplitUi(
                                id = it.id,
                                splitNumber = it.splitNumber,
                                action = it.action,
                                elapsedMillis = startedAt?.let { s -> it.timestampMillis - s } ?: 0L,
                                note = it.note,
                                syncState = lineSyncState(it.syncedAtMillis, it.lineNumber in linesWithAnySync),
                            )
                        },
                        canUndo = splits.isNotEmpty(),
                        raceInProgress = isRaceInProgress(
                            startedAt,
                            stoppedAt,
                            race?.bibsModeStartedAtMillis,
                            race?.bibsModeStoppedAtMillis,
                            race?.cpModeStartedAtMillis,
                            race?.cpModeStoppedAtMillis,
                        ),
                        unsyncedCount = unsyncedCount,
                        lastSyncedAtMillis = lastSyncedAtMillis,
                        firstBibNumber = race?.bibsRangeStart,
                        expectedRunnerCount = race?.bibsRangeCount,
                        finishedCount = splits.count { it.action == HistoryAction.SPLIT },
                        serverStatus = serverStatus,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimeModeUiState())

    fun startStopwatch() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch {
            timeModeRepository.startStopwatch(raceId)
            beeper.beep()
        }
    }

    // No debounce here by design: two taps in quick succession (two finishers crossing close
    // together) must always produce two distinct splits, never get merged into one.
    fun recordSplit() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch {
            timeModeRepository.recordSplit(raceId)
            beeper.beep()
        }
    }

    fun stopStopwatch() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { timeModeRepository.stopStopwatch(raceId) }
    }

    fun resetStopwatch() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { timeModeRepository.resetStopwatch(raceId) }
    }

    fun undoLast() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { timeModeRepository.undoMostRecent(raceId) }
    }

    fun updateNote(splitId: Long, note: String) {
        viewModelScope.launch { timeModeRepository.updateNote(splitId, note) }
    }

    override fun onCleared() {
        beeper.release()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                TimeModeViewModel(
                    container.timeModeRepository,
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