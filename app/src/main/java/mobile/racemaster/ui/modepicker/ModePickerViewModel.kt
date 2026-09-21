package mobile.racemaster.ui.modepicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.NON_ENTRY_ACTIONS
import mobile.racemaster.data.mule.BluetoothStateRepository
import mobile.racemaster.data.mule.BtPollingStatus
import mobile.racemaster.data.mule.ServerStatusRepository
import mobile.racemaster.data.mule.ServerStatusState
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.CpModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.TimeModeRepository
import mobile.racemaster.data.repository.isModeStarted
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import mobile.racemaster.util.formatBibsSoFarText
import mobile.racemaster.util.formatCpSoFarText
import mobile.racemaster.util.formatTimeSplitsText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Mode Picker's own version of the [mobile.racemaster.ui.components.RaceProgressSummary]
 *  fields it doesn't already hold as a plain top-level StateFlow (deviceName, btPollingStatus —
 *  both device-wide, passed straight through from this ViewModel's own separate flows instead). */
data class RaceSummaryUiState(
    val raceLabel: String,
    val raceLocation: String,
    val nextSplitNumber: Int,
    val unsyncedCount: Int,
    val lastSyncedAtMillis: Long?,
    val serverStatus: ServerStatusState,
    val progressText: String,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ModePickerViewModel(
    private val raceRepository: RaceRepository,
    private val timeModeRepository: TimeModeRepository,
    private val bibsModeRepository: BibsModeRepository,
    private val cpModeRepository: CpModeRepository,
    private val settingsRepository: SettingsRepository,
    private val serverStatusRepository: ServerStatusRepository,
    bluetoothStateRepository: BluetoothStateRepository,
) : ViewModel() {

    val hasActiveRace: StateFlow<Boolean> = settingsRepository.activeRaceId
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val deviceName: StateFlow<String?> = settingsRepository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Device-wide, not tied to whether a race is selected — same reasoning as TimeModeViewModel's
    // own identical flow.
    val btPollingStatus: StateFlow<BtPollingStatus> = combine(
        bluetoothStateRepository.advertisingWarning,
        bluetoothStateRepository.lastPolledAtMillis,
        ::BtPollingStatus,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BtPollingStatus())

    // Echoed on the picker regardless of whether a race is active, alongside the Mule Mode
    // button — see SettingsRepository.muleSyncEnabled's own doc for what this actually gates.
    val muleSyncEnabled: StateFlow<Boolean> = settingsRepository.muleSyncEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Which mode(s) still have this race active — not this device's own last-selected AppMode
    // (that used to read "Mule Mode" whenever the operator had since switched screens away from
    // whichever mode actually started this race, pointing them at a screen with no way to
    // Stop/Reset it at all). Lets the picker mark the matching mode button "- active".
    val activeModes: StateFlow<Set<AppMode>> = settingsRepository.activeRaceId
        .flatMapLatest { raceId ->
            if (raceId == null) {
                flowOf(emptySet())
            } else {
                raceRepository.observeRace(raceId).map { race ->
                    if (race == null) emptySet() else AppMode.entries.filterTo(mutableSetOf()) { isModeStarted(it, race) }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // This race's own single, currently-chosen mode (RaceRepository.recordModeStart's own
    // write, at Setup Race or Relocate) — what the picker's one "Start <mode>" button both
    // labels itself from and navigates to. Mode is no longer switched here at all (see
    // RaceDetailsScreen's own Relocate flow for the only remaining way to change it once a race
    // exists) — tapping the button just opens whichever mode this race already has.
    val raceMode: StateFlow<AppMode?> = settingsRepository.activeRaceId
        .flatMapLatest { raceId ->
            if (raceId == null) {
                flowOf(null)
            } else {
                raceRepository.observeRace(raceId).map { race ->
                    race?.mode?.let { raw -> runCatching { AppMode.valueOf(raw) }.getOrNull() }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Once a race has genuinely been set up (a race exists AND its mode is known), the same
    // device/race/location/sync/progress summary the live mode screens themselves show (see
    // RaceProgressSummary) — sourced from whichever of Time/Bibs/CP repositories matches
    // [raceMode], since that's this race's one currently-recording mode.
    val raceSummary: StateFlow<RaceSummaryUiState?> = combine(settingsRepository.activeRaceId, raceMode) { raceId, mode -> raceId to mode }
        .flatMapLatest { (raceId, mode) ->
            if (raceId == null || mode == null) {
                flowOf(null)
            } else {
                val unsyncedFlow: Flow<Int> = when (mode) {
                    AppMode.TIME -> timeModeRepository.observeUnsyncedCount(raceId)
                    AppMode.BIBS -> bibsModeRepository.observeUnsyncedCount(raceId)
                    AppMode.CP -> cpModeRepository.observeUnsyncedCount(raceId)
                }
                val lastSyncedFlow: Flow<Long?> = when (mode) {
                    AppMode.TIME -> timeModeRepository.observeLastSyncedAtMillis(raceId)
                    AppMode.BIBS -> bibsModeRepository.observeLastSyncedAtMillis(raceId)
                    AppMode.CP -> cpModeRepository.observeLastSyncedAtMillis(raceId)
                }
                val progressTextFlow: Flow<String> = when (mode) {
                    // Matches TimeModeViewModel's own splitCount exactly (action == SPLIT, an
                    // allowlist) — not splitNumber != 0, which would also count the fixed Start
                    // marker's... no, Start's splitNumber is 0 so that's excluded correctly, but
                    // a LOCATION row's splitNumber is null, and null != 0 is true, wrongly
                    // counting it as a real split.
                    AppMode.TIME -> timeModeRepository.observeCurrentSegmentSplits(raceId)
                        .map { formatTimeSplitsText(it.count { s -> s.action == HistoryAction.SPLIT }) }
                    AppMode.BIBS -> bibsModeRepository.observeCurrentSegmentEntries(raceId)
                        .map { formatBibsSoFarText(it.count { e -> e.action !in NON_ENTRY_ACTIONS }) }
                    AppMode.CP -> cpModeRepository.observeCurrentSegmentEntries(raceId)
                        .map { formatCpSoFarText(it.count { e -> e.action !in NON_ENTRY_ACTIONS }) }
                }
                combine(
                    raceRepository.observeRace(raceId),
                    unsyncedFlow,
                    lastSyncedFlow,
                    progressTextFlow,
                    serverStatusRepository.state,
                ) { race, unsynced, lastSynced, progressText, serverStatus ->
                    if (race == null) return@combine null
                    val nextSplitNumber = when (mode) {
                        AppMode.TIME -> race.timeModeNextSplit
                        AppMode.BIBS -> race.bibsModeNextSplit
                        AppMode.CP -> race.cpModeNextSplit
                    }
                    RaceSummaryUiState(
                        raceLabel = race.label,
                        raceLocation = race.location,
                        nextSplitNumber = nextSplitNumber,
                        unsyncedCount = unsynced,
                        lastSyncedAtMillis = lastSynced,
                        serverStatus = serverStatus,
                        progressText = progressText,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                ModePickerViewModel(
                    container.raceRepository,
                    container.timeModeRepository,
                    container.bibsModeRepository,
                    container.cpModeRepository,
                    container.settingsRepository,
                    container.serverStatusRepository,
                    container.bluetoothStateRepository,
                )
            }
        }
    }
}