package mobile.racemaster.ui.countmode

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mobile.racemaster.data.beacon.BeaconScanDecoder
import mobile.racemaster.data.beacon.DetectionTracking
import mobile.racemaster.data.beacon.shouldCommit
import mobile.racemaster.data.beacon.updatedDetectionTracking
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.mule.ProgressRepository
import mobile.racemaster.data.repository.CountModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.parseBeaconCount
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer

data class OutstandingBeaconUi(val bibNumber: Int, val beaconNumber: Int)

data class CountModeUiState(
    val uniqueBeaconsSeen: Int = 0,
    val outstanding: List<OutstandingBeaconUi> = emptyList(),
    val totalIssued: Int = 0,
    // For the top bar's own "Stop" button — same reasoning as CloseModeUiState.raceId/
    // canStopCloseMode: Count has no Reset of its own, so Stop (clearing the race's mode back
    // to null — see RaceRepository.stopMode) is the only way to ever move this race out of
    // Count mode once it's set up. canStopCountMode additionally requires the race's own
    // CURRENT mode to actually be Count — reached via the Beacons hub's own standing shortcut
    // (Routes.COUNT_BEACONS) as well as Setup Race's (Routes.COUNT_MODE), this screen can be
    // open while the active race is genuinely in Bibs/Time/Close mode instead, and Stop must
    // never clear a mode this screen had nothing to do with (same reasoning
    // CloseModeUiState.canStopCloseMode documents).
    val raceId: Long? = null,
    val canStopCountMode: Boolean = false,
)

/**
 * Pre-start headcount — docs/beacon-detection-plan.md's Count mode. Purely passive: no tap, no
 * per-beacon confirmation, just a running "unique beacons seen" count as runners walk past the
 * phone. ViewModel-scoped (not a [mobile.racemaster.data.mule.PeripheralSyncService]-hosted
 * process-lifetime engine like [mobile.racemaster.data.beacon.BeaconScanEngine]) — a pre-race
 * headcount is a short, actively-supervised task, the same reasoning
 * [mobile.racemaster.ui.closemode.CloseModeViewModel]'s own scan loop already follows; see the
 * plan's own doc for why this is the one genuinely judgment-call design choice here (easy to
 * promote to a process-lifetime engine later if operators background the screen mid-count).
 *
 * Reuses [BeaconScanEngine]'s own already-extracted, already-tested pure debounce functions
 * ([updatedDetectionTracking]/[shouldCommit]) rather than that engine itself — this is a sibling,
 * not a shared instance, since the dedup key (beacon number, not resolved bib — a beacon can be
 * counted before its bib pairing is known, per the doc's own "a count of unique beacons seen"),
 * the gating mode, and the write target (COUNT_SEEN rows via [CountModeRepository], not Bibs
 * entries) all differ from Bibs' own checkpoint detection.
 *
 * Reached two ways — Setup Race's own Count mode (`Routes.COUNT_MODE`) and the Beacons hub's
 * standing "Count" shortcut (`Routes.COUNT_BEACONS`), same pairing
 * [mobile.racemaster.ui.closemode.CloseModeViewModel] already has. Recording a count itself
 * never branches on `race.mode` — it just records COUNT_SEEN rows against whatever
 * `activeRaceId` is — so both routes resolve to the exact same screen/ViewModel with no
 * special-casing; only the top bar's own Stop button (see [CountModeUiState.canStopCountMode])
 * cares what the race's current mode actually is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CountModeViewModel(
    private val muleRepository: MuleRepository,
    private val countModeRepository: CountModeRepository,
    private val progressRepository: ProgressRepository,
    private val raceRepository: RaceRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CountModeUiState())
    val uiState: StateFlow<CountModeUiState> = _uiState.asStateFlow()

    private val raceIdFlow: StateFlow<Long?> = settingsRepository.activeRaceId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val detectionTracking = ConcurrentHashMap<Int, DetectionTracking>()

    private var bibByBeaconNumber: Map<Int, Int> = emptyMap()

    // Mirrors countModeRepository.observeCounts(raceId)'s own rows — the single source of truth
    // for "already seen," same reasoning BeaconScanEngine.recordedBibs documents, just keyed by
    // beacon number instead of resolved bib.
    @Volatile
    private var seenBeaconNumbers: Set<Int> = emptySet()

    init {
        viewModelScope.launch { collectAdvertisements() }
        viewModelScope.launch {
            raceIdFlow.flatMapLatest { raceId ->
                if (raceId == null) flowOf(null) else raceRepository.observeRace(raceId).map { it?.mode }
            }.collectLatest { mode ->
                _uiState.update { it.copy(canStopCountMode = mode == AppMode.COUNT.name) }
            }
        }
        viewModelScope.launch {
            raceIdFlow.collectLatest { raceId ->
                detectionTracking.clear()
                _uiState.update { it.copy(raceId = raceId) }
                if (raceId == null) {
                    bibByBeaconNumber = emptyMap()
                    seenBeaconNumbers = emptySet()
                    _uiState.update { it.copy(outstanding = emptyList(), totalIssued = 0, uniqueBeaconsSeen = 0) }
                    return@collectLatest
                }
                combine(
                    progressRepository.observeIssuedBeacons(raceId),
                    countModeRepository.observeCounts(raceId),
                ) { issued, rows -> issued to rows }.collect { (issued, rows) ->
                    bibByBeaconNumber = issued.associate { it.beaconNumber!! to it.bibNumber }
                    seenBeaconNumbers = rows.mapNotNull { parseBeaconCount(it.note) }.toSet()
                    _uiState.update { it.copy(totalIssued = issued.size) }
                    recomputeOutstanding()
                }
            }
        }
    }

    private fun recomputeOutstanding() {
        val outstanding = bibByBeaconNumber.entries
            .filter { (beaconNumber, _) -> beaconNumber !in seenBeaconNumbers }
            .map { (beaconNumber, bibNumber) -> OutstandingBeaconUi(bibNumber, beaconNumber) }
            .sortedBy { it.bibNumber }
        _uiState.update { it.copy(outstanding = outstanding, uniqueBeaconsSeen = seenBeaconNumbers.size) }
    }

    private suspend fun collectAdvertisements() {
        try {
            muleRepository.scanForAdvertisements().collect { advertisement ->
                val beacon = BeaconScanDecoder.decodeFromAdvertisement(advertisement) ?: return@collect
                handleBeacon(beacon.beaconNumber, beacon.estimatedDistanceMeters)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "beacon scan failed", e)
        }
    }

    private fun handleBeacon(beaconNumber: Int, distanceMeters: Double) {
        // Beacon 000 is the sweep — never counted (TODO.md).
        if (beaconNumber == 0) return
        // Nothing issued for this race yet (progress.json hasn't synced, or this course isn't
        // using Beacons at all) — recording COUNT_SEEN rows against a total of 0 only produced a
        // confusing "0 of 0" with every beacon logged under a null bib, so Count mode is a no-op
        // until there's an actual expectation list to count against.
        if (bibByBeaconNumber.isEmpty()) return
        if (distanceMeters > COUNT_MAX_DISTANCE_METERS) return
        val now = System.currentTimeMillis()
        val updated = detectionTracking.compute(beaconNumber) { _, existing -> updatedDetectionTracking(existing, now, DEBOUNCE_GAP_MILLIS) }!!
        if (shouldCommit(updated, REQUIRED_CONSECUTIVE_READINGS)) {
            detectionTracking.remove(beaconNumber)
            viewModelScope.launch { commitDetection(beaconNumber) }
        }
    }

    private suspend fun commitDetection(beaconNumber: Int) {
        if (beaconNumber in seenBeaconNumbers) return
        val raceId = raceIdFlow.value ?: return
        val bib = progressRepository.getBibForBeacon(raceId, beaconNumber)
        // The seen tally itself is deliberately not computed here from seenBeaconNumbers — see
        // CountModeRepository.recordCount's own doc for why that used to race when two beacons
        // committed close together.
        countModeRepository.recordCount(raceId, beaconNumber, bib, bibByBeaconNumber.size)
    }

    /** "Stop" — see [CountModeUiState.canStopCountMode]'s own doc for why this is gated on the
     *  race's own current mode actually being Count. Writes a COUNT_STOPPED row first when
     *  beacons are still outstanding (see [HistoryAction.COUNT_STOPPED]'s own doc) — nothing to
     *  say if every issued beacon was already seen, since COUNT_COMPLETE already said so. */
    fun stopMode() {
        if (!_uiState.value.canStopCountMode) return
        val raceId = raceIdFlow.value ?: return
        val outstandingCount = _uiState.value.outstanding.size
        viewModelScope.launch {
            if (outstandingCount > 0) {
                countModeRepository.recordStopped(raceId, outstandingCount)
            }
            raceRepository.stopMode(raceId)
        }
    }

    companion object {
        private const val TAG = "CountModeViewModel"

        // Same close-range policy cap BeaconScanEngine uses for its own checkpoint detection
        // (docs/beacon-detection-plan.md: "only close-range beacons should be counted") — start
        // identical, tune against real hardware like every other distance constant here.
        private const val COUNT_MAX_DISTANCE_METERS = 3.0
        private const val DEBOUNCE_GAP_MILLIS = 5_000L
        private const val REQUIRED_CONSECUTIVE_READINGS = 3

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                CountModeViewModel(
                    container.muleRepository,
                    container.countModeRepository,
                    container.progressRepository,
                    container.raceRepository,
                    container.settingsRepository,
                )
            }
        }
    }
}
