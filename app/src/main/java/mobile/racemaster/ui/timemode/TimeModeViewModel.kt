package mobile.racemaster.ui.timemode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.mule.BluetoothStateRepository
import mobile.racemaster.data.mule.BtPollingStatus
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.mule.ServerStatus
import mobile.racemaster.data.mule.ServerStatusRepository
import mobile.racemaster.data.mule.ServerStatusState
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.TimeModeRepository
import mobile.racemaster.data.repository.LineSyncState
import mobile.racemaster.data.repository.isRaceActive
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
    val liveElapsedMillis: Long = 0L,
    val nextSplitNumber: Int = 1,
    val splits: List<FinishSplitUi> = emptyList(),
    val canUndo: Boolean = false,
    val raceInProgress: Boolean = false,
    val unsyncedCount: Int = 0,
    val lastSyncedAtMillis: Long? = null,
    // Count of genuine SPLIT actions only (not Start/Reset markers) — feeds
    // util.formatTimeSplitsText's running tally.
    val splitCount: Int = 0,
    // Shown as another header line (see ui/components/ServerStatusLine.kt) — server
    // connectivity matters here just as much as in Mule Mode, since this device pushes its
    // own recorded data to the server on the same schedule regardless of mode.
    val serverStatus: ServerStatusState = ServerStatusState(ServerStatus.UNKNOWN, null),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TimeModeViewModel(
    private val timeModeRepository: TimeModeRepository,
    private val raceRepository: RaceRepository,
    private val settingsRepository: SettingsRepository,
    private val serverStatusRepository: ServerStatusRepository,
    private val muleRepository: MuleRepository,
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
                    val liveElapsed = if (startedAt == null) 0L else now - startedAt
                    TimeModeUiState(
                        raceId = raceId,
                        raceLabel = race?.label.orEmpty(),
                        raceLocation = race?.location.orEmpty(),
                        stopwatchStarted = startedAt != null,
                        liveElapsedMillis = liveElapsed,
                        nextSplitNumber = race?.timeModeNextSplit ?: 1,
                        // LOCATION rows are deliberately left out of the rendered list — the
                        // race's own current location is already echoed on its own line in
                        // RaceProgressSummary right above, so listing it again here too was just
                        // confusing noise. Still fully present in (and undoable via) the
                        // underlying `splits` list this is filtered from — canUndo/splitCount
                        // below are computed from that unfiltered list, not this one, so
                        // undoing a relocate the operator can't see listed still works exactly
                        // as before.
                        splits = splits.filterNot { it.action == HistoryAction.LOCATION }.map {
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
                        raceInProgress = isRaceActive(startedAt, race?.bibsModeStartedAtMillis, race?.cpModeStartedAtMillis),
                        unsyncedCount = unsyncedCount,
                        lastSyncedAtMillis = lastSyncedAtMillis,
                        splitCount = splits.count { it.action == HistoryAction.SPLIT },
                        serverStatus = serverStatus,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimeModeUiState())

    // A device now records against exactly one race for its whole lifetime (see TODO.md's
    // phase 1 — the course concept is gone), so Start no longer needs to resolve WHICH row to
    // record into — it's always this device's own active race. The already-started branch below
    // is defensive/effectively unreachable through the main button now that there's no separate
    // stopped state (once started, a mode screen always shows SPLIT, never START, until Reset —
    // see HistoryAction's own doc) — kept as-is since Race History's own "Resume" action
    // (RaceRepository.switchActiveRace) could in principle still land here on an already-started,
    // not-yet-reset race before this screen's own reactive state has caught up.
    fun startStopwatch() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch {
            val race = raceRepository.getRace(raceId) ?: return@launch
            if (race.timeModeStartedAtMillis != null) {
                timeModeRepository.resumeStopwatch(raceId)
            } else {
                timeModeRepository.startStopwatch(raceId)
            }
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

    // See RaceRepository.closeCurrentSegment's own doc for the walk-back-by-segment behavior a
    // Reset press triggers. A true (abandoned) result means this closed the race's own very
    // first segment, reverting this device to "no race set up" — announced to the server right
    // away, mirroring Setup Race's own "push right away" pattern (SetupRaceViewModel.save),
    // rather than waiting for the next background sync tick.
    fun resetStopwatch() {
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch {
            val abandoned = timeModeRepository.resetStopwatch(raceId)
            if (abandoned) muleRepository.announceRaceSetup()
        }
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
                    container.muleRepository,
                    container.bluetoothStateRepository,
                    Beeper(applicationContext()),
                )
            }
        }
    }
}