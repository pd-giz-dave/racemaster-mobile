package mobile.racemaster.ui.beacon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.juul.kable.Advertisement
import com.juul.kable.PlatformAdvertisement
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mobile.racemaster.data.beacon.BeaconConfig
import mobile.racemaster.data.beacon.BeaconConnectionPhase
import mobile.racemaster.data.beacon.BeaconLogEntry
import mobile.racemaster.data.beacon.BeaconRepository
import mobile.racemaster.data.beacon.DxCp27Protocol
import mobile.racemaster.data.mule.BluetoothStateRepository
import mobile.racemaster.di.appContainer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toKotlinUuid

/** This beacon advertises each of its configured slots as its own, separate advertisement packet
 *  (confirmed on real hardware: a device-name-slot packet with no manufacturer data interleaved
 *  with an iBeacon-slot packet that has no name — never both in the same packet) — so a single
 *  "latest advertisement" snapshot per beacon oscillates between the two rather than ever showing
 *  a complete picture. [frames] fixes that: one entry per distinct kind of advertisement seen
 *  from this beacon so far this scan, each refreshed in place as it re-appears, never dropped
 *  when a *different* kind arrives in between. Can legitimately be empty — e.g. every slot
 *  deleted/disabled, or this beacon's own identity frame hasn't arrived yet. */
data class ScannedBeaconUi(
    val advertisement: Advertisement,
    val address: String,
    val rssi: Int,
    val frames: List<ObservedAdvertFrame>,
) {
    /** Sticky once seen — the device-name frame, if any of [frames] is one, regardless of
     *  whether the *latest* packet received was that one or the iBeacon one instead. */
    val displayName: String?
        get() = frames.filterIsInstance<ObservedAdvertFrame.DeviceName>().firstOrNull()?.name
}

/** One distinct kind of advertisement this beacon has broadcast — see [ScannedBeaconUi.frames].
 *  [Unrecognized] exists so that verifying "this beacon is only transmitting iBeacon" is actually
 *  trustworthy: without it, any advertisement that's neither a name nor a decodable iBeacon frame
 *  (an Eddystone slot, say) would be silently dropped instead of showing up as a third line —
 *  making the other two look like the complete picture when they might not be. */
sealed interface ObservedAdvertFrame {
    data class DeviceName(val name: String) : ObservedAdvertFrame
    data class IBeacon(val beacon: DxCp27Protocol.AdvertisedIBeacon) : ObservedAdvertFrame
    // Keyed by subtype (UID/URL/TLM), not one shared slot — a factory-default beacon broadcasts
    // all three simultaneously, each as its own separate packet; collapsing them into one entry
    // would just recreate the original oscillation bug one level down.
    data class Eddystone(val subtype: Int, val label: String) : ObservedAdvertFrame
    data class Unrecognized(val rawHex: String) : ObservedAdvertFrame

    /** A fixed display order, independent of arrival order — see [BeaconProgrammerViewModel.mergeObservedFrame]'s own doc. */
    val sortOrder: Int
        get() = when (this) {
            is DeviceName -> 0
            is IBeacon -> 1
            is Eddystone -> 2_000 + subtype
            is Unrecognized -> Int.MAX_VALUE
        }
}

enum class ProgramOutcome { SUCCESS, FAILURE }

/** Shown next to the Program Beacon button for [BeaconProgrammerViewModel.PROGRAM_RESULT_DISPLAY_MILLIS],
 *  then self-clears — the log already records this, but it's easy to miss once the log has grown
 *  long enough to need scrolling. */
data class ProgramResultUi(val outcome: ProgramOutcome, val message: String)

data class BeaconUiState(
    val connectionPhase: BeaconConnectionPhase = BeaconConnectionPhase.IDLE,
    val connectedDeviceLabel: String? = null,
    val isScanning: Boolean = false,
    val scanResults: List<ScannedBeaconUi> = emptyList(),
    val config: BeaconConfig? = null,
    val showManualPasswordField: Boolean = false,
    val manualPasswordInput: String = "",
    val minorInput: String = "",
    val measuredPowerInput: String = "",
    val showProgramConfirm: Boolean = false,
    val showPowerOffConfirm: Boolean = false,
    val showFactoryResetConfirm: Boolean = false,
    val isBusy: Boolean = false,
    val isCalibrating: Boolean = false,
    val calibrationSampleCount: Int = 0,
    val log: List<BeaconLogEntry> = emptyList(),
    val programResult: ProgramResultUi? = null,
) {
    val minorError: String?
        get() = minorInput.takeIf { it.isNotEmpty() }?.toIntOrNull()?.let { if (it !in 0..65535) "0-65535 only" else null }
            ?: minorInput.takeIf { it.isNotEmpty() && it.toIntOrNull() == null }?.let { "Numbers only" }

    val measuredPowerError: String?
        get() = measuredPowerInput.takeIf { it.isNotEmpty() }?.toIntOrNull()?.let { if (it !in -127..0) "-127 to 0 only" else null }
            ?: measuredPowerInput.takeIf { it.isNotEmpty() && it.toIntOrNull() == null }?.let { "Numbers only" }

    val canProgram: Boolean
        get() = connectionPhase == BeaconConnectionPhase.CONNECTED && !isBusy && !isCalibrating &&
            minorInput.isNotEmpty() && minorError == null &&
            measuredPowerInput.isNotEmpty() && measuredPowerError == null
}

/** Drives [mobile.racemaster.ui.beacon.BeaconProgrammerScreen]'s structured scan → connect →
 *  show-config → calibrate → program flow — see [BeaconRepository] for the four real operations
 *  this wraps in UI state. */
class BeaconProgrammerViewModel(
    private val repository: BeaconRepository,
    private val bluetoothStateRepository: BluetoothStateRepository,
) : ViewModel() {
    fun isBluetoothEnabled(): Boolean = bluetoothStateRepository.isEnabled()

    private val isScanningFlow = MutableStateFlow(false)
    private val scanResultsFlow = MutableStateFlow<List<ScannedBeaconUi>>(emptyList())
    private val connectedAdvertisementFlow = MutableStateFlow<Advertisement?>(null)
    private val showManualPasswordFieldFlow = MutableStateFlow(false)
    private val manualPasswordInputFlow = MutableStateFlow("")
    private val minorInputFlow = MutableStateFlow("")
    private val measuredPowerInputFlow = MutableStateFlow("")
    private val showProgramConfirmFlow = MutableStateFlow(false)
    private val showPowerOffConfirmFlow = MutableStateFlow(false)
    private val showFactoryResetConfirmFlow = MutableStateFlow(false)
    private val programResultFlow = MutableStateFlow<ProgramResultUi?>(null)
    private val isCalibratingFlow = MutableStateFlow(false)
    private val calibrationSampleCountFlow = MutableStateFlow(0)

    val uiState: StateFlow<BeaconUiState> = combine(
        repository.connectionPhase,
        connectedAdvertisementFlow,
        isScanningFlow,
        scanResultsFlow,
        repository.config,
        showManualPasswordFieldFlow,
        manualPasswordInputFlow,
        minorInputFlow,
        measuredPowerInputFlow,
        showProgramConfirmFlow,
        repository.isBusy,
        repository.log,
        programResultFlow,
        isCalibratingFlow,
        calibrationSampleCountFlow,
        showPowerOffConfirmFlow,
        showFactoryResetConfirmFlow,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        BeaconUiState(
            connectionPhase = values[0] as BeaconConnectionPhase,
            connectedDeviceLabel = (values[1] as Advertisement?)?.let { it.name ?: it.identifier },
            isScanning = values[2] as Boolean,
            scanResults = values[3] as List<ScannedBeaconUi>,
            config = values[4] as BeaconConfig?,
            showManualPasswordField = values[5] as Boolean,
            manualPasswordInput = values[6] as String,
            minorInput = values[7] as String,
            measuredPowerInput = values[8] as String,
            showProgramConfirm = values[9] as Boolean,
            isBusy = values[10] as Boolean,
            log = values[11] as List<BeaconLogEntry>,
            programResult = values[12] as ProgramResultUi?,
            isCalibrating = values[13] as Boolean,
            calibrationSampleCount = values[14] as Int,
            showPowerOffConfirm = values[15] as Boolean,
            showFactoryResetConfirm = values[16] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BeaconUiState())

    private val seenAddresses = mutableMapOf<String, ScannedBeaconUi>()
    private var scanJob: Job? = null

    fun startScan() {
        if (scanJob?.isActive == true) return
        seenAddresses.clear()
        scanResultsFlow.value = emptyList()
        isScanningFlow.value = true
        repository.logScanStarted()
        scanJob = viewModelScope.launch {
            try {
                repository.scanForDevices().collect { advertisement ->
                    // Logged once per beacon, not once per advertisement packet — the same
                    // beacon re-advertises continuously for as long as the scan runs.
                    val isNewBeacon = advertisement.identifier !in seenAddresses
                    val previousFrames = seenAddresses[advertisement.identifier]?.frames ?: emptyList()
                    val mergedFrames = mergeObservedFrame(previousFrames, advertisement)
                    seenAddresses[advertisement.identifier] = ScannedBeaconUi(
                        advertisement = advertisement,
                        address = advertisement.identifier,
                        rssi = advertisement.rssi,
                        frames = mergedFrames,
                    )
                    scanResultsFlow.value = seenAddresses.values.sortedByDescending { it.rssi }
                    if (isNewBeacon) repository.logBeaconFound(advertisement.name, advertisement.identifier, advertisement.rssi)
                }
            } finally {
                isScanningFlow.value = false
                repository.logScanStopped()
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        isScanningFlow.value = false
    }

    /** Refreshes whichever of [existing]'s frame kinds this [advertisement] matches (dropping its
     *  old value, since it's possible for e.g. minor/RSSI to have changed), leaving any other
     *  kind untouched — see [ScannedBeaconUi.frames]'s own doc for why this can't just be "the
     *  latest advertisement decoded". Checks name, iBeacon-decode and Eddystone independently
     *  (not else-if'd), since a factory-default beacon broadcasts an iBeacon frame *and* all
     *  three Eddystone sub-frames simultaneously — never combined into one packet, but all real
     *  at once. Anything matching none of those refreshes a single [ObservedAdvertFrame.Unrecognized]
     *  slot rather than being silently dropped (see that type's own doc for why). */
    @OptIn(ExperimentalUuidApi::class)
    private fun mergeObservedFrame(existing: List<ObservedAdvertFrame>, advertisement: Advertisement): List<ObservedAdvertFrame> {
        var frames = existing
        var recognized = false
        val name = advertisement.name
        if (name != null) {
            frames = frames.filterNot { it is ObservedAdvertFrame.DeviceName } + ObservedAdvertFrame.DeviceName(name)
            recognized = true
        }
        val decodedIBeacon = DxCp27Protocol.parseIBeaconAdvertisement(
            advertisement.manufacturerData(DxCp27Protocol.APPLE_COMPANY_ID)
                ?: advertisement.manufacturerData(DxCp27Protocol.VENDOR_COMPANY_ID),
        )
        if (decodedIBeacon != null) {
            frames = frames.filterNot { it is ObservedAdvertFrame.IBeacon } + ObservedAdvertFrame.IBeacon(decodedIBeacon)
            recognized = true
        }
        val eddystoneData = advertisement.serviceData(DxCp27Protocol.EDDYSTONE_SERVICE_UUID.toKotlinUuid())
        if (eddystoneData != null) {
            val subtype = eddystoneData.firstOrNull()?.toInt()?.and(0xff) ?: -1
            val label = DxCp27Protocol.eddystoneFrameLabel(eddystoneData)
            frames = frames.filterNot { it is ObservedAdvertFrame.Eddystone && it.subtype == subtype } +
                ObservedAdvertFrame.Eddystone(subtype, label)
            recognized = true
        }
        if (!recognized) {
            val rawHex = (advertisement as? PlatformAdvertisement)?.bytes?.joinToString("") { "%02x".format(it) }
                ?: "(raw advertisement bytes unavailable)"
            frames = frames.filterNot { it is ObservedAdvertFrame.Unrecognized } + ObservedAdvertFrame.Unrecognized(rawHex)
        }
        // Sorted by a fixed type order, not insertion order — this beacon's slots arrive as
        // separate packets at unpredictable times, so without this the dialog's line order kept
        // reshuffling mid-scan as whichever frame type most recently refreshed jumped to the end,
        // making it unreadable while still scanning.
        return frames.sortedBy(ObservedAdvertFrame::sortOrder)
    }

    fun connect(beacon: ScannedBeaconUi) {
        stopScan()
        connectedAdvertisementFlow.value = beacon.advertisement
        showManualPasswordFieldFlow.value = false
        viewModelScope.launch {
            repository.connectAndAuthenticate(beacon.advertisement)
                .onSuccess { loadConfigAndPrefill() }
                .onFailure { showManualPasswordFieldFlow.value = true }
        }
    }

    /** Pre-fills the minor/measured-power inputs from the beacon's own currently-read iBeacon
     *  frame, if it already has one — so re-programming an already-programmed beacon (e.g. to
     *  tweak one field) doesn't force retyping or recalibrating values it already has. */
    private suspend fun loadConfigAndPrefill() {
        repository.loadCurrentConfig().onSuccess { config ->
            config.iBeaconFrame?.let { frame ->
                minorInputFlow.value = frame.minor.toString()
                measuredPowerInputFlow.value = frame.measuredPowerDbm.toString()
            }
        }
    }

    fun updateManualPassword(text: String) {
        manualPasswordInputFlow.value = text
    }

    fun submitManualPassword() {
        val password = manualPasswordInputFlow.value
        if (password.isBlank()) return
        viewModelScope.launch {
            repository.authenticateWithPassword(password)
                .onSuccess {
                    showManualPasswordFieldFlow.value = false
                    manualPasswordInputFlow.value = ""
                    loadConfigAndPrefill()
                }
        }
    }

    fun clearLog() = repository.clearLog()

    /** Shared by [disconnect], [powerOff] and [factoryReset] — all three end this app's session
     *  with the beacon and return the screen to the scan list, so all three need the same local
     *  cleanup (notably cancelling any in-progress calibration, which can otherwise keep running
     *  against a beacon that's no longer there to listen to). */
    private fun resetLocalConnectionState() {
        calibrationJob?.cancel()
        calibrationJob = null
        isCalibratingFlow.value = false
        connectedAdvertisementFlow.value = null
        showManualPasswordFieldFlow.value = false
        minorInputFlow.value = ""
    }

    fun disconnect() {
        resetLocalConnectionState()
        viewModelScope.launch { repository.disconnect() }
    }

    fun requestPowerOff() {
        showPowerOffConfirmFlow.value = true
    }

    fun dismissPowerOffConfirm() {
        showPowerOffConfirmFlow.value = false
    }

    /** Powers the beacon off — [BeaconRepository.powerOff] already tears down the session on
     *  success or failure alike (the beacon drops the link as it shuts down either way), so the
     *  screen returns to the scan list once [connectedAdvertisementFlow] clears here, same as a
     *  plain [disconnect]. Also drops it from the scan list itself (unlike a plain disconnect or
     *  [confirmFactoryReset]) — a powered-off beacon stops advertising entirely, so leaving it
     *  listed would offer a "beacon" that can't actually be reconnected to until it's physically
     *  switched back on. */
    fun confirmPowerOff() {
        showPowerOffConfirmFlow.value = false
        removeFromScanResults(connectedAdvertisementFlow.value?.identifier)
        resetLocalConnectionState()
        viewModelScope.launch { repository.powerOff() }
    }

    private fun removeFromScanResults(address: String?) {
        if (address == null || seenAddresses.remove(address) == null) return
        scanResultsFlow.value = seenAddresses.values.sortedByDescending { it.rssi }
    }

    fun requestFactoryReset() {
        showFactoryResetConfirmFlow.value = true
    }

    fun dismissFactoryResetConfirm() {
        showFactoryResetConfirmFlow.value = false
    }

    /** Factory-resets the beacon back to firmware defaults — same screen-reverts-to-scan
     *  behaviour as [confirmPowerOff], since this app's own programming (password, iBeacon
     *  frame) is gone afterward too. */
    fun confirmFactoryReset() {
        showFactoryResetConfirmFlow.value = false
        resetLocalConnectionState()
        viewModelScope.launch { repository.factoryReset() }
    }

    fun updateMinorInput(text: String) {
        minorInputFlow.value = text.filter(Char::isDigit).take(5)
    }

    fun updateMeasuredPowerInput(text: String) {
        measuredPowerInputFlow.value = text.filter { it.isDigit() || it == '-' }.take(4)
    }

    private var calibrationJob: Job? = null
    private val calibrationSamples = mutableListOf<Int>()

    /** Starts an open-ended calibration — each advertisement RSSI sample is logged live as it
     *  arrives (see [BeaconRepository.calibrationRssiSamples]) and the operator decides for
     *  themselves when enough have come in, via [stopCalibration], rather than a fixed window
     *  (confirmed on real hardware that even 10s wasn't reliably long enough). */
    fun startCalibration() {
        if (calibrationJob?.isActive == true) return
        val samples = repository.calibrationRssiSamples() ?: return
        calibrationSamples.clear()
        calibrationSampleCountFlow.value = 0
        isCalibratingFlow.value = true
        calibrationJob = viewModelScope.launch {
            try {
                // Must disconnect first — confirmed on real hardware the beacon can't advertise
                // while GATT-connected, which is why calibration saw no adverts at all before
                // this. finishCalibrating() (called from stopCalibration()) reconnects after.
                repository.startCalibrating().onFailure { return@launch }
                samples.collect { rssi ->
                    calibrationSamples.add(rssi)
                    calibrationSampleCountFlow.value = calibrationSamples.size
                }
            } finally {
                isCalibratingFlow.value = false
            }
        }
    }

    fun stopCalibration() {
        calibrationJob?.cancel()
        calibrationJob = null
        isCalibratingFlow.value = false
        val median = calibrationSamples.takeIf { it.isNotEmpty() }?.sorted()?.get(calibrationSamples.size / 2)
        repository.logCalibrationResult(median, calibrationSamples.size)
        if (median != null) measuredPowerInputFlow.value = median.toString()
        viewModelScope.launch { repository.finishCalibrating() }
    }

    fun requestProgram() {
        if (!uiState.value.canProgram) return
        showProgramConfirmFlow.value = true
    }

    fun dismissProgramConfirm() {
        showProgramConfirmFlow.value = false
    }

    private var programResultJob: Job? = null

    fun confirmProgram() {
        showProgramConfirmFlow.value = false
        val state = uiState.value
        val minor = state.minorInput.toIntOrNull() ?: return
        val measuredPower = state.measuredPowerInput.toIntOrNull() ?: return
        viewModelScope.launch {
            val result = repository.programForRace(minor, measuredPower)
            result.onSuccess { repository.loadCurrentConfig() }
            showProgramResult(
                if (result.isSuccess) {
                    ProgramResultUi(ProgramOutcome.SUCCESS, "Programmed")
                } else {
                    ProgramResultUi(ProgramOutcome.FAILURE, "Failed")
                },
            )
        }
    }

    private fun showProgramResult(result: ProgramResultUi) {
        programResultJob?.cancel()
        programResultFlow.value = result
        programResultJob = viewModelScope.launch {
            delay(PROGRAM_RESULT_DISPLAY_MILLIS)
            programResultFlow.value = null
        }
    }

    companion object {
        const val PROGRAM_RESULT_DISPLAY_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                BeaconProgrammerViewModel(container.beaconRepository, container.bluetoothStateRepository)
            }
        }
    }
}
