package mobile.racemaster.ui.mulemode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.juul.kable.Advertisement
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import mobile.racemaster.data.mule.DEVICE_ROLE_BIBS
import mobile.racemaster.data.mule.DatasetSummary
import mobile.racemaster.data.mule.DeviceInfo
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.di.appContainer

/** A single physical phone Mule has seen — keyed by its stable [deviceId] once known (a
 *  phone can advertise under more than one BLE address over time, e.g. address rotation or
 *  restarting Bluetooth, so address alone isn't a reliable identity), falling back to the
 *  raw BLE address as a placeholder key before the first successful [DeviceInfo] read. A
 *  phone that's run both Bibs mode and Time mode at different points accumulates *both*
 *  roles' counts here rather than appearing as two separate devices — only one of them is
 *  "live" at once (whichever mode the phone is currently in), the other reflects the last
 *  time it was seen in that role. */
data class DiscoveredDevice(
    val deviceKey: String,
    val advertisement: Advertisement,
    val deviceId: String? = null,
    val raceLabel: String = "",
    val roleCounts: Map<String, Int> = emptyMap(),
)

data class MuleModeUiState(
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val unsyncedCount: Int = 0,
    val lastSyncedAtMillis: Long? = null,
    val lastPulledAtMillis: Long? = null,
    val isLoggedIn: Boolean = false,
    val datasets: List<DatasetSummary> = emptyList(),
    val selectedDataset: Pair<String, String>? = null,
    val statusMessage: String? = null,
    val autoWarning: String? = null,
    val isBusy: Boolean = false,
    val lockedBibsDeviceId: String? = null,
    val lockedTimeDeviceId: String? = null,
    val autoSyncStopped: Boolean = false,
    val autoSyncArmed: Boolean = false,
)

/**
 * Mule can attach to up to two other phones at once — one Bibs device and one Time device,
 * independently optional (or the same phone if it happens to hold both) — and once logged
 * in with a dataset selected (no manual push required first: successfully fetching the
 * dataset list already proves the connection works), a background loop re-pulls from
 * whichever devices are locked and pushes to the server automatically, without the operator
 * re-tapping Pull/Push on every lap.
 */
class MuleModeViewModel(private val muleRepository: MuleRepository) : ViewModel() {

    private val discoveredFlow = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    private val datasetsFlow = MutableStateFlow<List<DatasetSummary>>(emptyList())
    private val statusMessageFlow = MutableStateFlow<String?>(null)
    // Distinct from statusMessageFlow (user-triggered action results): set when the
    // background auto-sync loop itself hits a failure, and cleared automatically as soon as
    // a later tick succeeds — so a transient "disconnect" during a brief reconnect doesn't
    // stick around forever once the connection recovers.
    private val autoWarningFlow = MutableStateFlow<String?>(null)
    private val busyFlow = MutableStateFlow(false)
    private var scanJob: Job? = null

    init {
        startScan()
        viewModelScope.launch {
            // Re-entering this screen creates a fresh ViewModel — without this, an
            // already-logged-in operator would see an empty dataset list until manually
            // tapping Refresh, even though they're already authenticated.
            if (muleRepository.isLoggedIn.first()) loadDatasets()
        }
        startAutoSyncLoop()
    }

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<MuleModeUiState> = combine(
        discoveredFlow,
        muleRepository.unsyncedCount,
        muleRepository.lastSyncedAtMillis,
        muleRepository.lastPulledAtMillis,
        muleRepository.isLoggedIn,
        datasetsFlow,
        muleRepository.selectedDataset,
        statusMessageFlow,
        autoWarningFlow,
        busyFlow,
        muleRepository.lockedBibsDeviceId,
        muleRepository.lockedTimeDeviceId,
        muleRepository.autoSyncStopped,
    ) { values ->
        val isLoggedIn = values[4] as Boolean
        val selectedDataset = values[6] as Pair<String, String>?
        val lockedBibsDeviceId = values[10] as String?
        val lockedTimeDeviceId = values[11] as String?
        val autoSyncStopped = values[12] as Boolean
        MuleModeUiState(
            discoveredDevices = (values[0] as Map<String, DiscoveredDevice>).values
                .sortedBy { it.raceLabel.ifEmpty { it.deviceKey } },
            unsyncedCount = values[1] as Int,
            lastSyncedAtMillis = values[2] as Long?,
            lastPulledAtMillis = values[3] as Long?,
            isLoggedIn = isLoggedIn,
            datasets = values[5] as List<DatasetSummary>,
            selectedDataset = selectedDataset,
            statusMessage = values[7] as String?,
            autoWarning = values[8] as String?,
            isBusy = values[9] as Boolean,
            lockedBibsDeviceId = lockedBibsDeviceId,
            lockedTimeDeviceId = lockedTimeDeviceId,
            autoSyncStopped = autoSyncStopped,
            // Armed once logged in, a dataset is picked, auto-sync hasn't been explicitly
            // stopped, and at least one device (Bibs and/or Time) is locked. A manual push
            // having succeeded is deliberately *not* required — fetching the dataset list
            // already proves the login/connection works.
            autoSyncArmed = isLoggedIn && selectedDataset != null && !autoSyncStopped &&
                (lockedBibsDeviceId != null || lockedTimeDeviceId != null),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MuleModeUiState())

    fun startScan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            muleRepository.scanForDevices().collect { advertisement ->
                val address = advertisement.identifier
                // Skip if this BLE identity is already tracked under any key (including a
                // deviceId key it's since been merged into) — otherwise a device rescanned
                // under the same address would be re-added and lose its accumulated state.
                val alreadyTracked = discoveredFlow.value.values.any { it.advertisement.identifier == address }
                if (alreadyTracked) return@collect
                discoveredFlow.value = discoveredFlow.value + (address to DiscoveredDevice(deviceKey = address, advertisement = advertisement))
                launch { refreshDeviceInfo(address) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    private fun startAutoSyncLoop() {
        viewModelScope.launch {
            while (isActive) {
                delay(AUTO_SYNC_INTERVAL)
                refreshAllDiscoveredDeviceInfo()
                autoPullAndPushIfArmed()
            }
        }
    }

    private suspend fun refreshAllDiscoveredDeviceInfo() {
        for (key in discoveredFlow.value.keys) {
            refreshDeviceInfo(key)
        }
    }

    private suspend fun refreshDeviceInfo(key: String) {
        val device = discoveredFlow.value[key] ?: return
        val info = runCatching { muleRepository.readDeviceInfo(device.advertisement) }.getOrNull() ?: return
        mergeDeviceInfo(key, device, info)
    }

    /** Folds a freshly-read [DeviceInfo] into [discoveredFlow], keyed by the stable
     *  `deviceId` rather than [oldKey] (a BLE address) — merging into any entry already
     *  tracked under that deviceId instead of creating a duplicate, and accumulating this
     *  role's count alongside whatever the other role's last-known count was. */
    private fun mergeDeviceInfo(oldKey: String, device: DiscoveredDevice, info: DeviceInfo) {
        val newKey = info.deviceId
        val current = discoveredFlow.value
        val base = current[newKey] ?: device
        val merged = base.copy(
            deviceKey = newKey,
            advertisement = device.advertisement,
            deviceId = newKey,
            raceLabel = info.raceLabel,
            roleCounts = base.roleCounts + (info.deviceRole to info.unsyncedCount),
        )
        discoveredFlow.value = (current - oldKey - newKey) + (newKey to merged)
    }

    private suspend fun autoPullAndPushIfArmed() {
        if (busyFlow.value) return
        // Checked directly against the raw persisted signals rather than the derived
        // uiState — that StateFlow only updates while something is actively observing it
        // (WhileSubscribed), so it could be stale for this background loop specifically.
        if (muleRepository.autoSyncStopped.first()) return
        if (!muleRepository.isLoggedIn.first()) return
        if (muleRepository.selectedDataset.first() == null) return

        val lockedIds = listOfNotNull(muleRepository.lockedBibsDeviceId.first(), muleRepository.lockedTimeDeviceId.first()).distinct()
        if (lockedIds.isEmpty()) return

        var tickFailure: String? = null
        for (lockedId in lockedIds) {
            val target = discoveredFlow.value.values.firstOrNull { it.deviceId == lockedId } ?: continue
            val freshInfo = runCatching { muleRepository.readDeviceInfo(target.advertisement) }.getOrNull()
            if (freshInfo == null) continue
            mergeDeviceInfo(target.deviceKey, target, freshInfo)
            if (freshInfo.unsyncedCount <= 0) continue
            busyFlow.value = true
            val result = runCatching { muleRepository.pullFrom(target.advertisement, freshInfo.deviceRole, freshInfo.raceLabel) }
            busyFlow.value = false
            result.onFailure { tickFailure = "Auto-pull failed: ${it.message}" }
            result.onSuccess {
                // Reflects the drop in unsynced count immediately rather than waiting for
                // the next periodic refresh, up to AUTO_SYNC_INTERVAL later.
                runCatching { muleRepository.readDeviceInfo(target.advertisement) }.getOrNull()
                    ?.let { mergeDeviceInfo(target.deviceKey, target, it) }
            }
        }
        // Self-clearing: a tick with no failures (including one where everything is already
        // synced and there was nothing to attempt) wipes out any earlier stale warning,
        // instead of it sticking around after the connection has actually recovered.
        autoWarningFlow.value = tickFailure

        pushIfNeeded(auto = true)
    }

    /** Returns the *other* role's currently locked+visible race label, if any — so a second
     *  attach can be checked against it before proceeding (Mule attaching to both a Bibs and
     *  a Time phone only makes sense if they're timing the same race). A single phone that
     *  supplies both roles itself is naturally excluded, since [excludeDeviceId] matches its
     *  own already-locked-by-the-other-role entry — it can't mismatch against itself. */
    private suspend fun otherRoleRaceLabelMismatch(candidateRole: String, candidateRaceLabel: String, excludeDeviceId: String): String? {
        val otherLockedId = when (candidateRole) {
            DEVICE_ROLE_BIBS -> muleRepository.lockedTimeDeviceId.first()
            else -> muleRepository.lockedBibsDeviceId.first()
        } ?: return null
        if (otherLockedId == excludeDeviceId) return null
        val otherLabel = discoveredFlow.value.values
            .firstOrNull { it.deviceId == otherLockedId }
            ?.raceLabel
            ?: return null
        return otherLabel.takeIf { it.isNotEmpty() && candidateRaceLabel.isNotEmpty() && it != candidateRaceLabel }
    }

    fun pullFrom(device: DiscoveredDevice) {
        viewModelScope.launch {
            busyFlow.value = true
            // Read fresh rather than relying on the cached roleCounts entry — the phone may
            // have switched mode since the last refresh, and this is what correctly tags the
            // pulled batch's source role rather than a possibly-stale cached one.
            val freshInfo = runCatching { muleRepository.readDeviceInfo(device.advertisement) }.getOrNull()
            if (freshInfo == null) {
                busyFlow.value = false
                statusMessageFlow.value = "Pull failed: couldn't reach device"
                return@launch
            }
            mergeDeviceInfo(device.deviceKey, device, freshInfo)

            val mismatch = otherRoleRaceLabelMismatch(freshInfo.deviceRole, freshInfo.raceLabel, freshInfo.deviceId)
            if (mismatch != null) {
                busyFlow.value = false
                statusMessageFlow.value =
                    "Race mismatch: the locked device is on \"$mismatch\", this one is on \"${freshInfo.raceLabel}\" — not pulling."
                return@launch
            }

            muleRepository.lockOntoDevice(freshInfo.deviceRole, freshInfo.deviceId)
            val result = runCatching { muleRepository.pullFrom(device.advertisement, freshInfo.deviceRole, freshInfo.raceLabel) }
            busyFlow.value = false
            statusMessageFlow.value = result.fold(
                onSuccess = { n -> "Pulled $n record${if (n == 1) "" else "s"} from ${freshInfo.raceLabel.ifEmpty { freshInfo.deviceRole }}" },
                onFailure = { e -> "Pull failed: ${e.message}" },
            )
            if (result.isSuccess) {
                runCatching { muleRepository.readDeviceInfo(device.advertisement) }.getOrNull()
                    ?.let { mergeDeviceInfo(device.deviceKey, device, it) }
                pushIfNeeded(auto = false)
            }
        }
    }

    fun login(baseUrl: String, username: String, password: String) {
        viewModelScope.launch {
            busyFlow.value = true
            val result = runCatching { muleRepository.login(baseUrl, username, password) }
            busyFlow.value = false
            statusMessageFlow.value = result.fold(
                onSuccess = { "Logged in" },
                onFailure = { e -> "Login failed: ${e.message}" },
            )
            if (result.isSuccess) loadDatasets()
        }
    }

    fun loadDatasets() {
        viewModelScope.launch {
            val result = runCatching { muleRepository.listDatasets() }
            result.onSuccess { datasetsFlow.value = it }
            result.onFailure { statusMessageFlow.value = "Couldn't load datasets: ${it.message}" }
        }
    }

    fun selectDataset(owner: String, fullName: String) {
        viewModelScope.launch { muleRepository.selectDataset(owner, fullName) }
    }

    fun pushToServer() {
        viewModelScope.launch { pushIfNeeded(auto = false) }
    }

    /** Immediate one-off pull-from-everything-locked + push, regardless of the stopped
     *  flag — doesn't itself resume auto-sync, it's just "do it right now". */
    fun forceSyncNow() {
        viewModelScope.launch {
            busyFlow.value = true
            val lockedIds = listOfNotNull(muleRepository.lockedBibsDeviceId.first(), muleRepository.lockedTimeDeviceId.first()).distinct()
            for (lockedId in lockedIds) {
                val target = discoveredFlow.value.values.firstOrNull { it.deviceId == lockedId } ?: continue
                val freshInfo = runCatching { muleRepository.readDeviceInfo(target.advertisement) }.getOrNull() ?: continue
                mergeDeviceInfo(target.deviceKey, target, freshInfo)
                runCatching { muleRepository.pullFrom(target.advertisement, freshInfo.deviceRole, freshInfo.raceLabel) }
                runCatching { muleRepository.readDeviceInfo(target.advertisement) }.getOrNull()
                    ?.let { mergeDeviceInfo(target.deviceKey, target, it) }
            }
            busyFlow.value = false
            pushIfNeeded(auto = false)
            autoWarningFlow.value = null
            statusMessageFlow.value = "Force sync complete"
        }
    }

    fun stopAutoSync() {
        viewModelScope.launch { muleRepository.setAutoSyncStopped(true) }
    }

    fun resumeAutoSync() {
        viewModelScope.launch { muleRepository.setAutoSyncStopped(false) }
    }

    private suspend fun pushIfNeeded(auto: Boolean) {
        if (!muleRepository.isLoggedIn.first()) {
            if (!auto) statusMessageFlow.value = "Push failed: not logged in"
            return
        }
        if (muleRepository.selectedDataset.first() == null) {
            if (!auto) statusMessageFlow.value = "Push failed: no dataset selected"
            return
        }
        if (muleRepository.unsyncedCount.first() <= 0) {
            if (!auto) statusMessageFlow.value = "Nothing to push"
            return
        }
        busyFlow.value = true
        val result = runCatching { muleRepository.pushToServer() }
        busyFlow.value = false
        if (!auto || result.isFailure) {
            statusMessageFlow.value = result.fold(
                onSuccess = { n -> "Pushed $n new record${if (n == 1) "" else "s"} to the server" },
                onFailure = { e -> "Push failed: ${e.message}" },
            )
        }
    }

    fun dismissStatusMessage() {
        statusMessageFlow.value = null
    }

    override fun onCleared() {
        stopScan()
    }

    companion object {
        private val AUTO_SYNC_INTERVAL = 15_000.milliseconds

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                MuleModeViewModel(container.muleRepository)
            }
        }
    }
}
