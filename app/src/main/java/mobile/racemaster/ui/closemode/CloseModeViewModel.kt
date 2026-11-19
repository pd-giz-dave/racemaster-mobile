package mobile.racemaster.ui.closemode

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.juul.kable.Advertisement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mobile.racemaster.data.beacon.BeaconRepository
import mobile.racemaster.data.beacon.BeaconScanDecoder
import mobile.racemaster.data.beacon.DetectionTracking
import mobile.racemaster.data.beacon.DxCp27Protocol
import mobile.racemaster.data.beacon.shouldCommit
import mobile.racemaster.data.beacon.updatedDetectionTracking
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.mule.ProgressRepository
import mobile.racemaster.data.repository.CloseModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.parseBeaconClose
import mobile.racemaster.data.repository.parseBeaconReopened
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/** One beacon currently audible, nearest-first — shown regardless of whether it's already been
 *  closed (see [CloseModeUiState.nearbyBeacons]'s own doc for why). [closeCount] is 0 for a
 *  beacon never closed this race. */
data class NearbyBeaconUi(
    val advertisement: Advertisement,
    val beaconNumber: Int,
    val rssi: Int,
    val distanceMeters: Double,
    val bibNumber: Int?,
    val closeCount: Int,
)

data class OutstandingBeaconUi(val bibNumber: Int, val beaconNumber: Int)

enum class CloseOutcome { SUCCESS, FAILURE }

/** Self-clearing, same shape as [mobile.racemaster.ui.beacon.ProgramResultUi]. */
data class CloseResultUi(val outcome: CloseOutcome, val message: String)

/** The most recent CLOSE row for a beacon the operator just selected — drives the confirm
 *  dialog's wording ("already closed at HH:MM, battery X% — close again?") instead of a plain
 *  prompt. Derived from [mobile.racemaster.data.db.entity.HistoryLineEntity] rows via
 *  [parseBeaconClose], not a dedicated table — see [CloseModeRepository]'s own doc. */
data class PriorCloseUi(val closedAtMillis: Long, val batteryPercent: Int, val closeCount: Int)

data class CloseModeUiState(
    // Every beacon currently heard, closed or not — never filtered by close state, which is
    // exactly what lets an already-closed beacon that's been turned back on keep showing up
    // instead of silently vanishing or being rejected (TODO.md's "allow for a beacon to be
    // re-presented" — see HistoryAction.CLOSE's own doc).
    val nearbyBeacons: List<NearbyBeaconUi> = emptyList(),
    val outstanding: List<OutstandingBeaconUi> = emptyList(),
    val totalIssued: Int = 0,
    val selected: NearbyBeaconUi? = null,
    val reclosingPriorRecord: PriorCloseUi? = null,
    val isBusy: Boolean = false,
    val lastResult: CloseResultUi? = null,
    // For the top bar's own "Stop" button — Close has no Reset of its own (see
    // CloseModeRepository's own doc), so Stop (clearing the race's mode back to null — see
    // RaceRepository.stopMode) is the only way to ever move this race out of Close mode once
    // it's set up. [canStopCloseMode] additionally requires the race's own CURRENT mode to
    // actually be Close — reached via the Beacons hub's own standing shortcut (see this class's
    // own doc), this screen can be open while the active race is genuinely in Bibs/Time/Count
    // mode instead, and Stop must never clear a mode this screen had nothing to do with.
    val raceId: Long? = null,
    val canStopCloseMode: Boolean = false,
)

/** Post-race beacon tidy-up: connect to each beacon handed out, read its battery, power it off,
 *  and log the result as an ordinary CLOSE row (see [CloseModeRepository]) — picked up by the
 *  existing periodic Mule sync loop like any other row, no explicit server push from here.
 *  Reuses [MuleRepository.scanForAdvertisements] (the shared Kable scan fan-out), the same
 *  "share, don't duplicate" reasoning every other beacon screen already follows, rather than
 *  [BeaconRepository.scanForDevices]'s own separate scanner (Program Beacon's own scanner, which
 *  needs Mule Mode paused while it runs — this screen doesn't, since it adds no second scan
 *  registration).
 *
 *  Reached two ways — Setup Race's own Close mode (`Routes.CLOSE_MODE`) and the Beacons hub's
 *  standing "Close" shortcut (`Routes.CLOSE_BEACONS`), kept for ad-hoc use regardless of the
 *  active race's own mode (see the plan's own Part A doc for why both stay). Recording a close
 *  itself never branches on `race.mode` — it just records CLOSE rows against whatever
 *  `activeRaceId` is — so both routes resolve to the exact same screen/ViewModel with no
 *  special-casing; only the top bar's own Stop button (see [CloseModeUiState.canStopCloseMode])
 *  cares what the race's current mode actually is. */
@OptIn(ExperimentalCoroutinesApi::class)
class CloseModeViewModel(
    private val muleRepository: MuleRepository,
    private val beaconRepository: BeaconRepository,
    private val closeModeRepository: CloseModeRepository,
    private val progressRepository: ProgressRepository,
    private val raceRepository: RaceRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CloseModeUiState())
    val uiState: StateFlow<CloseModeUiState> = _uiState.asStateFlow()

    private val raceIdFlow: StateFlow<Long?> = settingsRepository.activeRaceId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Per-beacon running state, keyed by advertiser address — deliberately simpler than
     *  [mobile.racemaster.ui.beaconidentify.IdentifyFobViewModel]'s own enter/exit hysteresis:
     *  this screen has no "in range" threshold to decide, just a live list of whatever's
     *  currently audible for the operator to tap, same posture as
     *  [RangeCalibrationViewModel]'s own tracking. [advertisement] is kept live (not just the
     *  decoded fields) since [confirmClose] needs it to actually connect. */
    private class Tracking(
        val beaconNumber: Int,
        val measuredPowerDbm: Int,
        var advertisement: Advertisement,
        var smoothedRssi: Double,
        var lastSeenAtMillis: Long,
    )

    private val trackingByAddress = ConcurrentHashMap<String, Tracking>()

    // Debounces "this already-closed beacon is advertising again" the same way BeaconScanEngine/
    // CountModeViewModel debounce their own passive detections — a few consecutive readings, not
    // one stray packet, before concluding it's genuinely back on (reusing their already-tested
    // pure helpers, keyed by beacon number like CountModeViewModel's own detectionTracking, not
    // address — the same beacon can hop advertiser addresses and this still shouldn't double-fire).
    private val reopenDetectionTracking = ConcurrentHashMap<Int, DetectionTracking>()

    /** One CLOSE/CLOSE_REOPENED row, decoded from
     *  [mobile.racemaster.data.db.entity.HistoryLineEntity.note] via [parseBeaconClose]/
     *  [parseBeaconReopened]. [batteryPercent] is null for a CLOSE_REOPENED row (it carries no
     *  battery reading — see [HistoryAction.CLOSE_REOPENED]'s own doc). [lineNumber] breaks ties
     *  for "the latest one" — ascending and immutable per row, so it's a cleaner ordering key than
     *  [timestampMillis] alone. */
    private data class CloseEvent(val action: HistoryAction, val batteryPercent: Int?, val timestampMillis: Long, val lineNumber: Long)

    // Snapshots of ProgressRepository/CloseModeRepository, refreshed as raceIdFlow/
    // closeModeRepository's own Flow change — read from recompute() below without a suspend
    // call per tick. eventsByBeaconNumber is grouped by beacon number (not a single latest row)
    // so closeCount can still reflect every CLOSE row for that beacon; closedBeaconNumbers is
    // derived from it separately — a beacon's current closed/outstanding status is always
    // "whichever of CLOSE/CLOSE_REOPENED is its most recent event," never "has it ever had a
    // CLOSE row" (see HistoryAction.CLOSE_REOPENED's own doc).
    private var bibByBeaconNumber: Map<Int, Int> = emptyMap()
    private var eventsByBeaconNumber: Map<Int, List<CloseEvent>> = emptyMap()
    private var closedBeaconNumbers: Set<Int> = emptySet()

    init {
        viewModelScope.launch { collectAdvertisements() }
        viewModelScope.launch { recomputeLoop() }
        viewModelScope.launch {
            raceIdFlow.flatMapLatest { raceId ->
                if (raceId == null) flowOf(null) else raceRepository.observeRace(raceId).map { it?.mode }
            }.collectLatest { mode ->
                _uiState.update { it.copy(canStopCloseMode = mode == AppMode.CLOSE.name) }
            }
        }
        viewModelScope.launch {
            raceIdFlow.collectLatest { raceId ->
                reopenDetectionTracking.clear()
                _uiState.update { it.copy(raceId = raceId) }
                if (raceId == null) {
                    bibByBeaconNumber = emptyMap()
                    eventsByBeaconNumber = emptyMap()
                    closedBeaconNumbers = emptySet()
                    _uiState.update { it.copy(outstanding = emptyList(), totalIssued = 0) }
                    return@collectLatest
                }
                combine(
                    progressRepository.observeIssuedBeacons(raceId),
                    closeModeRepository.observeCloses(raceId),
                ) { issued, rows -> issued to rows }.collect { (issued, rows) ->
                    bibByBeaconNumber = issued.associate { it.beaconNumber!! to it.bibNumber }
                    _uiState.update { it.copy(totalIssued = issued.size) }
                    eventsByBeaconNumber = rows
                        .mapNotNull { row ->
                            when (row.action) {
                                HistoryAction.CLOSE -> parseBeaconClose(row.note)?.let { (beaconNumber, battery) ->
                                    beaconNumber to CloseEvent(HistoryAction.CLOSE, battery, row.timestampMillis, row.lineNumber)
                                }
                                HistoryAction.CLOSE_REOPENED -> parseBeaconReopened(row.note)?.let { beaconNumber ->
                                    beaconNumber to CloseEvent(HistoryAction.CLOSE_REOPENED, null, row.timestampMillis, row.lineNumber)
                                }
                                else -> null
                            }
                        }
                        .groupBy({ it.first }, { it.second })
                    closedBeaconNumbers = eventsByBeaconNumber
                        .filterValues { events -> events.maxByOrNull { it.lineNumber }?.action == HistoryAction.CLOSE }
                        .keys
                    recomputeOutstanding()
                }
            }
        }
    }

    private fun recomputeOutstanding() {
        val outstanding = bibByBeaconNumber.entries
            .filter { (beaconNumber, _) -> beaconNumber !in closedBeaconNumbers }
            .map { (beaconNumber, bibNumber) -> OutstandingBeaconUi(bibNumber, beaconNumber) }
            .sortedBy { it.bibNumber }
        _uiState.update { it.copy(outstanding = outstanding) }
    }

    private suspend fun collectAdvertisements() {
        try {
            muleRepository.scanForAdvertisements().collect { advertisement ->
                val beacon = BeaconScanDecoder.decodeFromAdvertisement(advertisement) ?: return@collect
                handleReopenDetection(beacon.beaconNumber)
                val now = System.currentTimeMillis()
                trackingByAddress.compute(beacon.address) { _, existing ->
                    if (existing == null) {
                        Tracking(
                            beaconNumber = beacon.beaconNumber,
                            measuredPowerDbm = beacon.measuredPowerDbm,
                            advertisement = advertisement,
                            smoothedRssi = beacon.rssi.toDouble(),
                            lastSeenAtMillis = now,
                        )
                    } else {
                        existing.apply {
                            this.advertisement = advertisement
                            smoothedRssi = RSSI_SMOOTHING_ALPHA * beacon.rssi + (1 - RSSI_SMOOTHING_ALPHA) * smoothedRssi
                            lastSeenAtMillis = now
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "beacon scan failed", e)
        }
    }

    /** Auto-reinstates an already-closed beacon as outstanding once it's heard advertising
     *  again — see [CloseModeRepository.recordReopenedIfClosed]'s own doc. [handleReopenDetection]
     *  itself is a cheap, no-suspend guard (skip entirely if this beacon isn't currently flagged
     *  closed) before even touching the debounce map, so the common "every other beacon" case
     *  costs nothing extra per advertisement. */
    private fun handleReopenDetection(beaconNumber: Int) {
        if (beaconNumber !in closedBeaconNumbers) return
        val now = System.currentTimeMillis()
        val updated = reopenDetectionTracking.compute(beaconNumber) { _, existing ->
            updatedDetectionTracking(existing, now, REOPEN_DEBOUNCE_GAP_MILLIS)
        }!!
        if (shouldCommit(updated, REOPEN_REQUIRED_CONSECUTIVE_READINGS)) {
            reopenDetectionTracking.remove(beaconNumber)
            val raceId = raceIdFlow.value ?: return
            val bibNumber = bibByBeaconNumber[beaconNumber]
            viewModelScope.launch { closeModeRepository.recordReopenedIfClosed(raceId, beaconNumber, bibNumber) }
        }
    }

    private suspend fun recomputeLoop() {
        while (viewModelScope.isActive) {
            recompute()
            delay(RECOMPUTE_INTERVAL)
        }
    }

    private fun recompute() {
        val now = System.currentTimeMillis()
        trackingByAddress.entries.removeAll { (_, tracking) -> now - tracking.lastSeenAtMillis > STALE_AFTER_MILLIS }
        val nearby = trackingByAddress.values.map { tracking ->
            val distanceMeters = BeaconScanDecoder.estimateDistanceMeters(tracking.measuredPowerDbm, tracking.smoothedRssi)
            NearbyBeaconUi(
                advertisement = tracking.advertisement,
                beaconNumber = tracking.beaconNumber,
                rssi = tracking.smoothedRssi.toInt(),
                distanceMeters = distanceMeters,
                bibNumber = bibByBeaconNumber[tracking.beaconNumber],
                closeCount = eventsByBeaconNumber[tracking.beaconNumber]?.count { it.action == HistoryAction.CLOSE } ?: 0,
            ) to distanceMeters
        }.sortedBy { (_, distanceMeters) -> distanceMeters }.map { (beacon, _) -> beacon }
        _uiState.update { it.copy(nearbyBeacons = nearby) }
    }

    /** Null whenever this beacon isn't currently closed — including right after it's been
     *  auto-reinstated as outstanding (see [handleReopenDetection]) — so selecting it shows a
     *  plain "close" prompt, not a misleading "already closed, close again?" one. */
    private fun priorCloseFor(beaconNumber: Int): PriorCloseUi? {
        val events = eventsByBeaconNumber[beaconNumber] ?: return null
        val latest = events.maxByOrNull { it.lineNumber } ?: return null
        if (latest.action != HistoryAction.CLOSE) return null
        val closeCount = events.count { it.action == HistoryAction.CLOSE }
        return PriorCloseUi(closedAtMillis = latest.timestampMillis, batteryPercent = latest.batteryPercent!!, closeCount = closeCount)
    }

    fun selectBeacon(beacon: NearbyBeaconUi) {
        _uiState.update { it.copy(selected = beacon, reclosingPriorRecord = priorCloseFor(beacon.beaconNumber)) }
    }

    fun dismissSelection() {
        _uiState.update { it.copy(selected = null, reclosingPriorRecord = null) }
    }

    fun confirmClose() {
        val raceId = raceIdFlow.value ?: return
        val beacon = _uiState.value.selected ?: return
        _uiState.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            // Every beacon reaching this screen is already race-programmed — skip the
            // factory-default guess, same reasoning BeaconProgrammerViewModel.connect uses.
            beaconRepository.connectAndAuthenticate(beacon.advertisement, listOf(DxCp27Protocol.RACEMASTER_PASSWORD))
                .onSuccess { closeConnectedBeacon(raceId, beacon) }
                .onFailure { showCloseResult(beacon.beaconNumber, success = false) }
        }
    }

    private suspend fun closeConnectedBeacon(raceId: Long, beacon: NearbyBeaconUi) {
        val batteryPercent = beaconRepository.loadCurrentConfig().getOrNull()?.deviceInfo?.batteryPercent
        if (batteryPercent == null) {
            showCloseResult(beacon.beaconNumber, success = false)
            return
        }
        beaconRepository.powerOff()
            .onSuccess {
                closeModeRepository.recordClose(raceId, beacon.beaconNumber, beacon.bibNumber, batteryPercent)
                showCloseResult(beacon.beaconNumber, success = true)
            }
            .onFailure { showCloseResult(beacon.beaconNumber, success = false) }
    }

    /** "Stop" — see [CloseModeUiState.canStopCloseMode]'s own doc for why this is gated on the
     *  race's own current mode actually being Close (never called with that false, since the
     *  screen's own Stop button is disabled otherwise, but defensive either way). */
    fun stopMode() {
        if (!_uiState.value.canStopCloseMode) return
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { raceRepository.stopMode(raceId) }
    }

    private var resultClearJob: Job? = null

    /** On success, clears [CloseModeUiState.selected] and returns to the nearby list. On
     *  failure, deliberately leaves it set so the operator can retry the same beacon via the
     *  dialog's own Confirm button without re-tapping it in the list. */
    private fun showCloseResult(beaconNumber: Int, success: Boolean) {
        val label = "%03d".format(beaconNumber)
        val message = if (success) "Beacon $label closed" else "Failed to close beacon $label"
        _uiState.update {
            it.copy(
                isBusy = false,
                selected = if (success) null else it.selected,
                reclosingPriorRecord = if (success) null else it.reclosingPriorRecord,
                lastResult = CloseResultUi(if (success) CloseOutcome.SUCCESS else CloseOutcome.FAILURE, message),
            )
        }
        resultClearJob?.cancel()
        resultClearJob = viewModelScope.launch {
            delay(RESULT_DISPLAY_MILLIS)
            _uiState.update { it.copy(lastResult = null) }
        }
    }

    companion object {
        private const val TAG = "CloseModeViewModel"
        private val RECOMPUTE_INTERVAL = 300.milliseconds
        private const val STALE_AFTER_MILLIS = 8_000L
        private const val RSSI_SMOOTHING_ALPHA = 0.15
        private const val RESULT_DISPLAY_MILLIS = 5_000L

        // Same debounce shape CountModeViewModel uses for its own passive detection — a few
        // consecutive readings, not one stray packet, before concluding a closed beacon is
        // genuinely back on.
        private const val REOPEN_DEBOUNCE_GAP_MILLIS = 5_000L
        private const val REOPEN_REQUIRED_CONSECUTIVE_READINGS = 3

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                CloseModeViewModel(
                    container.muleRepository,
                    container.beaconRepository,
                    container.closeModeRepository,
                    container.progressRepository,
                    container.raceRepository,
                    container.settingsRepository,
                )
            }
        }
    }
}
