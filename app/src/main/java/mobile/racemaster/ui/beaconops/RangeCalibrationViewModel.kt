package mobile.racemaster.ui.beaconops

import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mobile.racemaster.data.beacon.BeaconScanDecoder
import mobile.racemaster.data.beacon.RangeContext
import mobile.racemaster.data.beacon.RangeSample
import mobile.racemaster.data.beacon.RangeSampleLogger
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.di.appContainer
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/** One currently-audible programmed race beacon, shown with its *raw* (unsmoothed) RSSI — unlike
 *  [mobile.racemaster.ui.beaconidentify.IdentifyFobScreen], this tool deliberately doesn't EMA/
 *  hysteresis-stabilize anything, since smoothing in the UI layer would corrupt the very data
 *  this screen exists to record. [currentEstimateMeters] is [BeaconScanDecoder]'s existing (and,
 *  per the reason this screen exists, probably inaccurate) live formula — shown purely so the
 *  operator can see at a glance, in the field, how wrong it is next to the distance they just
 *  measured. */
data class VisibleRangeBeacon(
    val address: String,
    val beaconNumber: Int,
    val measuredPowerDbm: Int,
    val rawRssi: Int,
    val currentEstimateMeters: Double,
)

data class RangeCalibrationUiState(
    val context: RangeContext = RangeContext.INDOOR_CROWDED,
    val distanceMetersInput: String = "",
    val beaconsInRange: List<VisibleRangeBeacon> = emptyList(),
    val sampleCount: Int = 0,
    val showClearConfirm: Boolean = false,
) {
    val canRecord: Boolean get() = distanceMetersInput.toDoubleOrNull() != null
}

/** Data-collection tool for TODO.md's "Bluetooth Beacon range estimation" section — logs
 *  (context, real measured distance, beacon, RSSI stats, phone model) samples to
 *  [RangeSampleLogger] for later offline analysis (e.g. fitting a per-context model against the
 *  exported CSV). This screen never estimates or replaces anything itself — see
 *  docs/beacon-detection-plan.md's range-calibration section for why that's a deliberate, later,
 *  data-informed step rather than something guessed again here.
 *
 *  Reuses [MuleRepository.scanForAdvertisements] (the shared Kable scan fan-out — see that
 *  function's own doc) rather than opening a second native scan, the same "share, don't
 *  duplicate" reasoning every other beacon screen already follows. */
class RangeCalibrationViewModel(
    private val muleRepository: MuleRepository,
    private val rangeSampleLogger: RangeSampleLogger,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RangeCalibrationUiState(sampleCount = rangeSampleLogger.sampleCount()))
    val uiState: StateFlow<RangeCalibrationUiState> = _uiState.asStateFlow()

    /** [recentRssi] is a small ring buffer of the last few raw readings, continuously maintained
     *  (not just captured at the moment [record] is tapped) — gives [record] a short recent
     *  history to summarize (median/min/max/count) instead of trusting one noisy packet, with no
     *  extra wait needed since it's already there by the time the operator taps. */
    private class Tracking(
        val beaconNumber: Int,
        val measuredPowerDbm: Int,
        val recentRssi: ArrayDeque<Int> = ArrayDeque(),
        var lastSeenAtMillis: Long,
    )

    private val trackingByAddress = ConcurrentHashMap<String, Tracking>()

    init {
        viewModelScope.launch { collectAdvertisements() }
        viewModelScope.launch { recomputeLoop() }
    }

    private suspend fun collectAdvertisements() {
        try {
            muleRepository.scanForAdvertisements().collect { advertisement ->
                val beacon = BeaconScanDecoder.decodeFromAdvertisement(advertisement) ?: return@collect
                val now = System.currentTimeMillis()
                trackingByAddress.compute(beacon.address) { _, existing ->
                    val tracking = existing ?: Tracking(beacon.beaconNumber, beacon.measuredPowerDbm, lastSeenAtMillis = now)
                    tracking.recentRssi.addLast(beacon.rssi)
                    while (tracking.recentRssi.size > RSSI_RING_BUFFER_SIZE) tracking.recentRssi.removeFirst()
                    tracking.lastSeenAtMillis = now
                    tracking
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "beacon scan failed", e)
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
        val visible = trackingByAddress.entries.mapNotNull { (address, tracking) ->
            val rawRssi = tracking.recentRssi.lastOrNull() ?: return@mapNotNull null
            VisibleRangeBeacon(
                address = address,
                beaconNumber = tracking.beaconNumber,
                measuredPowerDbm = tracking.measuredPowerDbm,
                rawRssi = rawRssi,
                currentEstimateMeters = BeaconScanDecoder.estimateDistanceMeters(tracking.measuredPowerDbm, rawRssi),
            )
        }.sortedBy { it.currentEstimateMeters }
        _uiState.update { it.copy(beaconsInRange = visible) }
    }

    fun setContext(context: RangeContext) {
        _uiState.update { it.copy(context = context) }
    }

    fun setDistanceMetersInput(text: String) {
        _uiState.update { it.copy(distanceMetersInput = text) }
    }

    /** Logs one sample for [address] at the operator-entered distance/context, summarizing
     *  whatever's currently in that beacon's RSSI ring buffer. No-ops if the distance field isn't
     *  a valid number, or nothing's been heard from this beacon yet. */
    fun record(address: String) {
        val distanceMeters = _uiState.value.distanceMetersInput.toDoubleOrNull() ?: return
        val tracking = trackingByAddress[address] ?: return
        val readings = tracking.recentRssi.toList()
        if (readings.isEmpty()) return
        val sorted = readings.sorted()
        val sample = RangeSample(
            timestampMillis = System.currentTimeMillis(),
            context = _uiState.value.context,
            distanceMeters = distanceMeters,
            beaconNumber = tracking.beaconNumber,
            measuredPowerDbm = tracking.measuredPowerDbm,
            rssiMedian = sorted[sorted.size / 2],
            rssiMin = sorted.first(),
            rssiMax = sorted.last(),
            sampleCount = readings.size,
            phoneModel = Build.MODEL,
            androidSdk = Build.VERSION.SDK_INT,
        )
        viewModelScope.launch(Dispatchers.IO) {
            rangeSampleLogger.appendSample(sample)
            val newCount = rangeSampleLogger.sampleCount()
            _uiState.update { it.copy(sampleCount = newCount) }
        }
    }

    fun requestClear() {
        _uiState.update { it.copy(showClearConfirm = true) }
    }

    fun dismissClearConfirm() {
        _uiState.update { it.copy(showClearConfirm = false) }
    }

    fun confirmClear() {
        viewModelScope.launch(Dispatchers.IO) {
            rangeSampleLogger.clear()
            _uiState.update { it.copy(showClearConfirm = false, sampleCount = 0) }
        }
    }

    fun shareIntent(): Intent? = rangeSampleLogger.shareIntent()

    companion object {
        private const val TAG = "RangeCalibrationViewModel"
        private val RECOMPUTE_INTERVAL = 300.milliseconds
        private const val STALE_AFTER_MILLIS = 5_000L
        private const val RSSI_RING_BUFFER_SIZE = 10

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                RangeCalibrationViewModel(container.muleRepository, container.rangeSampleLogger)
            }
        }
    }
}
