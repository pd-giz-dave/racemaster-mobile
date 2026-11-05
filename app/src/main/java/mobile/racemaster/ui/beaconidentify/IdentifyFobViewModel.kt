package mobile.racemaster.ui.beaconidentify

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mobile.racemaster.data.beacon.BeaconScanDecoder
import mobile.racemaster.data.beacon.DetectedRaceBeacon
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/** [beaconsInRange] is nearest-first; empty when nothing is currently within
 *  [SettingsRepository.beaconIdentifyDistanceMeters]. See [IdentifyFobScreen]'s own doc. */
data class IdentifyFobUiState(val beaconsInRange: List<DetectedRaceBeacon> = emptyList())

/** Registration-desk read-only utility — per docs/beacon-detection-plan.md §5/TODO.md point 3,
 *  bib-to-beacon pairing happens server-side at registration, not on the phone; this screen's
 *  only job is letting registration staff read a specific physical beacon's own
 *  [DetectedRaceBeacon.beaconNumber] off itself, to check it's working and its number matches
 *  the label stuck on it.
 *
 *  Reuses [MuleRepository.scanForAdvertisements] (the unfiltered fan-out of the one shared Kable
 *  scan — see that function's own doc) rather than opening a second native scan registration,
 *  the same "share, don't duplicate" reasoning MulePullClient's own class-doc gives for why a
 *  second scanner risks real radio contention on cheap/old chipsets. */
class IdentifyFobViewModel(
    private val muleRepository: MuleRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(IdentifyFobUiState())
    val uiState: StateFlow<IdentifyFobUiState> = _uiState.asStateFlow()

    /** Per-beacon running state, keyed by advertiser address. Indoors, raw per-packet RSSI on
     *  cheap BLE radios swings by 5-10dB packet to packet even with nothing moving (multipath
     *  off walls/bodies) — enough to flip a beacon in and out of [DetectedRaceBeacon.distanceMeters]
     *  's threshold every recompute if read naively. Three layers fight that, each catching what
     *  the last one doesn't: [smoothedRssi] is an EMA, not the latest raw reading; the enter/exit
     *  thresholds in [recompute] are a band, not one line a reading sits right on; and
     *  [pendingFlips] requires the *other* state to keep winning for several consecutive
     *  recomputes in a row before [inRange] actually changes — a registration desk can afford
     *  that extra second or so of lag per direction, since a fob sits there for several seconds
     *  anyway, and it's what finally kills the single-reading flicker the first two layers alone
     *  didn't. */
    private class BeaconTracking(
        val beaconNumber: Int,
        val measuredPowerDbm: Int,
        var smoothedRssi: Double,
        var lastSeenAtMillis: Long,
        var inRange: Boolean = false,
        var pendingFlips: Int = 0,
    )

    private val trackingByAddress = ConcurrentHashMap<String, BeaconTracking>()

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
                    if (existing == null || now - existing.lastSeenAtMillis > RESET_SMOOTHING_AFTER_GAP_MILLIS) {
                        // First sighting ever, or reappearing after a long enough gap that the
                        // old average is from however long ago and doesn't deserve any weight —
                        // either way, start the EMA over at this one reading rather than slow-
                        // blending away from stale history. This is what makes a fob that's just
                        // been switched on (or carried back after a while) show up promptly
                        // instead of needing several seconds to out-vote its own old baseline.
                        // inRange carries over from the old entry (not reset to false) so a
                        // beacon that was already displayed and just had its packets delayed
                        // doesn't flicker out — see STALE_AFTER_MILLIS's own doc for that half.
                        BeaconTracking(
                            beaconNumber = beacon.beaconNumber,
                            measuredPowerDbm = beacon.measuredPowerDbm,
                            smoothedRssi = beacon.rssi.toDouble(),
                            lastSeenAtMillis = now,
                            inRange = existing?.inRange ?: false,
                        )
                    } else {
                        existing.apply {
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

    private suspend fun recomputeLoop() {
        while (viewModelScope.isActive) {
            recompute()
            delay(RECOMPUTE_INTERVAL)
        }
    }

    private suspend fun recompute() {
        val now = System.currentTimeMillis()
        trackingByAddress.entries.removeAll { (_, tracking) -> now - tracking.lastSeenAtMillis > STALE_AFTER_MILLIS }
        val enterDistance = settingsRepository.beaconIdentifyDistanceMeters.first()
        // Exit threshold is deliberately looser than entry — a beacon right at the boundary
        // settles into one state instead of flapping as smoothedRssi drifts a little either way.
        val exitDistance = enterDistance * HYSTERESIS_EXIT_FACTOR

        val inRange = trackingByAddress.entries.mapNotNull { (address, tracking) ->
            val distanceMeters = BeaconScanDecoder.estimateDistanceMeters(tracking.measuredPowerDbm, tracking.smoothedRssi)
            val wantsToFlip = when {
                !tracking.inRange && distanceMeters <= enterDistance -> true
                tracking.inRange && distanceMeters > exitDistance -> true
                else -> false
            }
            if (!wantsToFlip) {
                tracking.pendingFlips = 0
            } else {
                tracking.pendingFlips++
                val required = if (tracking.inRange) EXIT_CONSECUTIVE_READINGS else ENTER_CONSECUTIVE_READINGS
                if (tracking.pendingFlips >= required) {
                    tracking.inRange = !tracking.inRange
                    tracking.pendingFlips = 0
                }
            }
            if (!tracking.inRange) return@mapNotNull null
            DetectedRaceBeacon(
                address = address,
                beaconNumber = tracking.beaconNumber,
                measuredPowerDbm = tracking.measuredPowerDbm,
                rssi = tracking.smoothedRssi.toInt(),
            ) to distanceMeters
        }.sortedBy { (_, distanceMeters) -> distanceMeters }.map { (beacon, _) -> beacon }

        _uiState.value = IdentifyFobUiState(beaconsInRange = inRange)
    }

    companion object {
        private const val TAG = "IdentifyFobViewModel"
        private val RECOMPUTE_INTERVAL = 300.milliseconds

        // How long a beacon can go without a fresh advertisement before it's evicted from
        // trackingByAddress entirely (wiping its smoothing/hysteresis state and, if it was the
        // only one, dropping the screen to "Hold a beacon close to the phone"). Indoors, BLE
        // scanning drops the occasional packet even from a beacon sitting right next to the
        // phone — this needs to be generous enough that one or two missed packets never reach
        // it; only the beacon actually being taken away should.
        private const val STALE_AFTER_MILLIS = 8_000L

        // How long a gap since the last advertisement has to be before a new one resets
        // smoothedRssi from scratch instead of blending into the existing average — deliberately
        // shorter than STALE_AFTER_MILLIS, so eviction (which only affects whether the beacon
        // disappears from tracking at all) and detection speed (how fast a reappearing beacon's
        // estimate reflects reality) are tuned independently.
        private const val RESET_SMOOTHING_AFTER_GAP_MILLIS = 2_000L

        // Weight given to each new raw RSSI reading in the running average — low, so one noisy
        // packet barely moves the estimate; still responsive enough to follow a beacon actually
        // being carried away within a second or two.
        private const val RSSI_SMOOTHING_ALPHA = 0.15
        private const val HYSTERESIS_EXIT_FACTOR = 1.8

        // Consecutive 300ms recomputes the *other* state has to keep winning before inRange
        // actually flips — asymmetric on purpose: entering reacts fairly quickly (a fob just
        // presented), leaving is deliberately slower (a fob held steadily shouldn't blink out
        // on one bad reading) — see BeaconTracking's own doc.
        private const val ENTER_CONSECUTIVE_READINGS = 2
        private const val EXIT_CONSECUTIVE_READINGS = 6

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                IdentifyFobViewModel(container.muleRepository, container.settingsRepository)
            }
        }
    }
}
