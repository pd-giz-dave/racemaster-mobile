package mobile.racemaster.ui.modepicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.NON_ENTRY_ACTIONS
import mobile.racemaster.data.mule.BluetoothStateRepository
import mobile.racemaster.data.mule.BtPollingStatus
import mobile.racemaster.data.mule.LocationStateRepository
import mobile.racemaster.data.mule.ServerStatusRepository
import mobile.racemaster.data.mule.ServerStatusState
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.CloseModeRepository
import mobile.racemaster.data.repository.CountModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.TimeModeRepository
import mobile.racemaster.data.repository.parseBeaconClose
import mobile.racemaster.data.repository.parseBeaconCount
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.data.settings.toHistoryMode
import mobile.racemaster.di.appContainer
import mobile.racemaster.ui.locationServicesEnabledFlow
import mobile.racemaster.util.formatBibsSoFarText
import mobile.racemaster.util.formatTimeSplitsText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val countModeRepository: CountModeRepository,
    private val closeModeRepository: CloseModeRepository,
    private val settingsRepository: SettingsRepository,
    private val serverStatusRepository: ServerStatusRepository,
    bluetoothStateRepository: BluetoothStateRepository,
    locationStateRepository: LocationStateRepository,
) : ViewModel() {

    val hasActiveRace: StateFlow<Boolean> = settingsRepository.activeRaceId
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val deviceName: StateFlow<String?> = settingsRepository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Device-wide, not tied to whether a race is selected — same reasoning as TimeModeViewModel's
    // own identical flow. settingsRepository.bluetoothOff is this app's own in-app Bluetooth
    // toggle — see BtPollingStatus.bluetoothOff's own doc for why the picker needs it too, not
    // just the live mode screens.
    val btPollingStatus: StateFlow<BtPollingStatus> = combine(
        bluetoothStateRepository.advertisingWarning,
        bluetoothStateRepository.lastPolledAtMillis,
        locationServicesEnabledFlow(locationStateRepository),
        settingsRepository.bluetoothOff,
        bluetoothStateRepository.observeEnabled(),
        ::BtPollingStatus,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BtPollingStatus())

    // Echoed on the picker regardless of whether a race is active, alongside the Mule Mode
    // button — see SettingsRepository.muleSyncEnabled's own doc for what this actually gates.
    val muleSyncEnabled: StateFlow<Boolean> = settingsRepository.muleSyncEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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
    // RaceProgressSummary) — sourced from whichever repository matches this race's own current
    // (location-aware — see AppMode.toHistoryMode(location)'s own doc) HistoryMode, since Pen/
    // Close are just two more Bibs locations now (Count/Close no longer have their own AppMode
    // value — see AppMode's own doc on the amalgamation) but still keep their own distinct rows.
    val raceSummary: StateFlow<RaceSummaryUiState?> = combine(settingsRepository.activeRaceId, raceMode) { raceId, mode -> raceId to mode }
        .flatMapLatest { (raceId, mode) ->
            if (raceId == null || mode == null) {
                flowOf(null)
            } else {
                raceRepository.observeRace(raceId).map { it?.location }.distinctUntilChanged().flatMapLatest { location ->
                    val historyMode = mode.toHistoryMode(location.orEmpty())
                    val unsyncedFlow: Flow<Int> = when (historyMode) {
                        HistoryMode.TIME -> timeModeRepository.observeUnsyncedCount(raceId)
                        HistoryMode.BIBS -> bibsModeRepository.observeUnsyncedCount(raceId)
                        HistoryMode.COUNT -> countModeRepository.observeUnsyncedCount(raceId)
                        HistoryMode.CLOSE -> closeModeRepository.observeUnsyncedCount(raceId)
                    }
                    val lastSyncedFlow: Flow<Long?> = when (historyMode) {
                        HistoryMode.TIME -> timeModeRepository.observeLastSyncedAtMillis(raceId)
                        HistoryMode.BIBS -> bibsModeRepository.observeLastSyncedAtMillis(raceId)
                        HistoryMode.COUNT -> countModeRepository.observeLastSyncedAtMillis(raceId)
                        HistoryMode.CLOSE -> closeModeRepository.observeLastSyncedAtMillis(raceId)
                    }
                    val progressTextFlow: Flow<String> = when (historyMode) {
                        // Matches TimeModeViewModel's own splitCount exactly (action == SPLIT, an
                        // allowlist) — not splitNumber != 0, which would also count the fixed Start
                        // marker's... no, Start's splitNumber is 0 so that's excluded correctly, but
                        // a LOCATION row's splitNumber is null, and null != 0 is true, wrongly
                        // counting it as a real split.
                        HistoryMode.TIME -> timeModeRepository.observeCurrentSegmentSplits(raceId)
                            .map { formatTimeSplitsText(it.count { s -> s.action == HistoryAction.SPLIT }) }
                        HistoryMode.BIBS -> bibsModeRepository.observeCurrentSegmentEntries(raceId)
                            .map { formatBibsSoFarText(it.count { e -> e.action !in NON_ENTRY_ACTIONS }) }
                        // Unique beacons, not total rows — a re-presented, re-closed beacon
                        // shouldn't inflate this count (see HistoryAction.CLOSE's own doc).
                        HistoryMode.COUNT -> countModeRepository.observeCounts(raceId)
                            .map { rows -> "${rows.mapNotNull { parseBeaconCount(it.note) }.distinct().size} beacons counted" }
                        HistoryMode.CLOSE -> closeModeRepository.observeCloses(raceId)
                            .map { rows -> "${rows.mapNotNull { parseBeaconClose(it.note)?.first }.distinct().size} beacons closed" }
                    }
                    combine(
                        raceRepository.observeRace(raceId),
                        unsyncedFlow,
                        lastSyncedFlow,
                        progressTextFlow,
                        serverStatusRepository.state,
                    ) { race, unsynced, lastSynced, progressText, serverStatus ->
                        if (race == null) return@combine null
                        // Count/Close have no NextSplit column (see showNextSplit's own doc) — this
                        // value is never actually displayed for them, so 0 is a harmless placeholder.
                        val nextSplitNumber = when (historyMode) {
                            HistoryMode.TIME -> race.timeModeNextSplit
                            HistoryMode.BIBS -> race.bibsModeNextSplit
                            HistoryMode.COUNT, HistoryMode.CLOSE -> 0
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
                    container.countModeRepository,
                    container.closeModeRepository,
                    container.settingsRepository,
                    container.serverStatusRepository,
                    container.bluetoothStateRepository,
                    container.locationStateRepository,
                )
            }
        }
    }
}