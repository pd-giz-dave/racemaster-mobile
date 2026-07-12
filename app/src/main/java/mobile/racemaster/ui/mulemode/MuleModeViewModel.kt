package mobile.racemaster.ui.mulemode

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import mobile.racemaster.data.mule.BluetoothStateRepository
import mobile.racemaster.data.mule.DEVICE_ROLE_BIBS
import mobile.racemaster.data.mule.DEVICE_ROLE_TIME
import mobile.racemaster.data.mule.DatasetSummary
import mobile.racemaster.data.mule.DeviceInfo
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.mule.toSyncRecord
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.TimeModeRepository
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer

/** A single physical phone Mule has seen — keyed by its stable [deviceId] once known (a
 *  phone can advertise under more than one BLE address over time, e.g. address rotation or
 *  restarting Bluetooth, so address alone isn't a reliable identity), falling back to the
 *  raw BLE address as a placeholder key before the first successful [DeviceInfo] read. A
 *  phone that's run both Bibs mode and Time mode at different points accumulates *both*
 *  roles' counts here rather than appearing as two separate devices — only one of them is
 *  "live" at once (whichever mode the phone is currently in), the other reflects the last
 *  time it was seen in that role.
 *
 *  This device's own unsynced data is folded into the same list as one more entry
 *  ([isSelf] = true) rather than shown separately — it's just as much a sync candidate as
 *  anything found over BLE, and [advertisement] is null for it since there's no actual
 *  radio involved in "pulling" from yourself.
 *
 *  [lastReachableAtMillis] and [unreachable] exist so the list's color reflects *current*
 *  reachability rather than whatever was last successfully read — without them, a device
 *  that's gone out of range still shows the stale green/red from its last successful
 *  contact, contradicting an "Auto-pull failed: couldn't reach ..." warning shown at the
 *  same time. A device stays listed (marked [unreachable]) rather than disappearing on the
 *  first failure, since a single missed BLE read is common and not worth losing the entry
 *  over — it's only dropped from [MuleModeViewModel]'s map once it's been continuously
 *  unreachable for [MuleModeViewModel.Companion.UNREACHABLE_DROP_THRESHOLD].
 *
 *  [consecutiveFailures] debounces [unreachable] itself: a *single* missed read is ordinary
 *  BLE noise (two radios doing central+peripheral duty at once — this phone scanning
 *  everyone else while also advertising and running its own GATT server — routinely drops
 *  the occasional connection attempt, especially on weaker chipsets), not a sign the device
 *  actually went anywhere. Flipping straight to red on every such blip made a device that
 *  was perfectly reachable flicker red "from time to time" for no real reason (confirmed in
 *  the field, worst on budget hardware). Only a run of consecutive failures now flips the
 *  color — see [MuleModeViewModel.Companion.UNREACHABLE_FAILURE_THRESHOLD] — while a single
 *  miss still resets [lastReachableAtMillis]'s drop-timer contribution not at all (that
 *  timer only cares about the *last success*, so a debounced blip doesn't quietly extend a
 *  device's borrowed time either). */
data class DiscoveredDevice(
    val deviceKey: String,
    val advertisement: Advertisement?,
    val deviceId: String? = null,
    val deviceName: String = "",
    val raceLabel: String = "",
    val roleCounts: Map<String, Int> = emptyMap(),
    val isSelf: Boolean = false,
    val lastReachableAtMillis: Long = System.currentTimeMillis(),
    val consecutiveFailures: Int = 0,
    val unreachable: Boolean = false,
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
    val bluetoothWarning: String? = null,
    val isBusy: Boolean = false,
    val autoSyncStopped: Boolean = false,
    val autoSyncArmed: Boolean = false,
)

/**
 * Mule has no explicit "attach" step and no limit on how many other phones it syncs from —
 * once logged in with a dataset selected (no manual push required first: successfully
 * fetching the dataset list already proves the connection works), a background loop pulls
 * from every currently-visible device (plus this phone's own unsynced data) and pushes to
 * the server automatically, without the operator tapping anything on every lap.
 */
// discoveredFlow (see below) only ever holds devices that came from a live BLE scan result
// (see startScan()) — the self entry is added separately, purely at uiState's final merge
// step, and is never inserted into discoveredFlow itself. So every DiscoveredDevice reached
// through discoveredFlow — the only thing every BLE-specific call site below operates on —
// is guaranteed to carry a real advertisement, unlike the nullable-in-general field.
private val DiscoveredDevice.requiredAdvertisement: Advertisement
    get() = advertisement ?: error("Expected a BLE-scanned device, got the self entry")

@OptIn(ExperimentalCoroutinesApi::class)
class MuleModeViewModel(
    private val muleRepository: MuleRepository,
    private val bluetoothStateRepository: BluetoothStateRepository,
    private val raceRepository: RaceRepository,
    private val timeModeRepository: TimeModeRepository,
    private val bibsModeRepository: BibsModeRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val discoveredFlow = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    private val datasetsFlow = MutableStateFlow<List<DatasetSummary>>(emptyList())
    private val statusMessageFlow = MutableStateFlow<String?>(null)
    // Distinct from statusMessageFlow (user-triggered action results): set when the
    // background auto-sync loop itself hits a failure, and cleared automatically as soon as
    // a later tick succeeds — so a transient "disconnect" during a brief reconnect doesn't
    // stick around forever once the connection recovers.
    private val autoWarningFlow = MutableStateFlow<String?>(null)
    // Set whenever the radio is off (checked both proactively before starting a scan and
    // reactively via the periodic re-check below), cleared the moment a scan actually starts
    // successfully — this is what stands between a disabled Bluetooth adapter and the crash
    // that used to follow (com.juul.kable.UnmetRequirementException, uncaught, from
    // startScan()'s collect).
    private val bluetoothWarningFlow = MutableStateFlow<String?>(null)
    private val busyFlow = MutableStateFlow(false)
    private var scanJob: Job? = null

    val deviceName: StateFlow<String?> = muleRepository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Read directly rather than via uiState.isLoggedIn — that StateFlow's initial value is
    // a synthetic "not logged in" default until the underlying DataStore read actually
    // completes, which would otherwise misfire the screen's auto-forward-to-Setup-Server
    // check (see MuleModeScreen) for an operator who genuinely already is logged in.
    suspend fun isLoggedIn(): Boolean = muleRepository.isLoggedIn.first()

    init {
        startScan()
        viewModelScope.launch {
            // Reacts to every transition to logged-in, not just a one-shot check at
            // creation time — covers both a fresh ViewModel finding an already-logged-in
            // session, and (now that login lives on a separate Setup Server screen) this
            // same ViewModel surviving the round trip there and back, where a one-shot
            // check at init would already have run before the login happened.
            muleRepository.isLoggedIn.collect { loggedIn -> if (loggedIn) loadDatasets() }
        }
        startAutoSyncLoop()
        startBluetoothStateLoop()
    }

    // Polls rather than registering a BluetoothAdapter.ACTION_STATE_CHANGED receiver — this
    // ViewModel already polls for the auto-sync loop on a similar cadence, so one more simple
    // loop is more consistent with the rest of this file than a second, receiver-based
    // mechanism would be. Re-tries startScan() (a no-op if it's already running) on every
    // tick, which is what actually recovers scanning once the operator turns Bluetooth back
    // on — nothing else in this ViewModel would otherwise notice and restart it.
    private fun startBluetoothStateLoop() {
        viewModelScope.launch {
            while (isActive) {
                if (bluetoothStateRepository.isEnabled()) {
                    startScan()
                } else {
                    stopScan()
                    bluetoothWarningFlow.value = "Bluetooth is off — turn it on to discover nearby devices"
                }
                delay(BLUETOOTH_CHECK_INTERVAL)
            }
        }
    }

    // This device's own unsynced data, shaped as one more DiscoveredDevice (isSelf = true) so
    // it can be folded straight into the same list real BLE-discovered devices render in —
    // "self" is exactly as much a sync candidate as anything found over the radio. Null
    // (nothing to show) while there's no active race, same as a discovered device that
    // hasn't reported anything yet.
    private val selfDeviceFlow: Flow<DiscoveredDevice?> = settingsRepository.activeRaceId
        .flatMapLatest { raceId ->
            if (raceId == null) {
                flowOf(null)
            } else {
                combine(
                    raceRepository.observeRace(raceId),
                    timeModeRepository.observeUnsyncedCount(raceId),
                    bibsModeRepository.observeUnsyncedCount(raceId),
                    muleRepository.deviceName,
                ) { race, unsyncedSplits, unsyncedEntries, deviceName ->
                    DiscoveredDevice(
                        deviceKey = SELF_DEVICE_KEY,
                        advertisement = null,
                        deviceId = SELF_DEVICE_KEY,
                        deviceName = deviceName.orEmpty(),
                        raceLabel = race?.label.orEmpty(),
                        roleCounts = mapOf(DEVICE_ROLE_TIME to unsyncedSplits, DEVICE_ROLE_BIBS to unsyncedEntries),
                        isSelf = true,
                    )
                }
            }
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
        muleRepository.autoSyncStopped,
        bluetoothWarningFlow,
        selfDeviceFlow,
    ) { values ->
        val isLoggedIn = values[4] as Boolean
        val selectedDataset = values[6] as Pair<String, String>?
        val autoSyncStopped = values[10] as Boolean
        val selfDevice = values[12] as DiscoveredDevice?
        MuleModeUiState(
            discoveredDevices = ((values[0] as Map<String, DiscoveredDevice>).values + listOfNotNull(selfDevice))
                .sortedBy { it.raceLabel.ifEmpty { it.deviceKey } },
            unsyncedCount = values[1] as Int,
            lastSyncedAtMillis = values[2] as Long?,
            lastPulledAtMillis = values[3] as Long?,
            isLoggedIn = isLoggedIn,
            datasets = values[5] as List<DatasetSummary>,
            selectedDataset = selectedDataset,
            statusMessage = values[7] as String?,
            autoWarning = values[8] as String?,
            bluetoothWarning = values[11] as String?,
            isBusy = values[9] as Boolean,
            autoSyncStopped = autoSyncStopped,
            // Armed once logged in, a dataset is picked, and auto-sync hasn't been
            // explicitly stopped — no longer gated on any particular device being visible,
            // since every device seen (plus self) is synced automatically each tick anyway.
            autoSyncArmed = isLoggedIn && selectedDataset != null && !autoSyncStopped,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MuleModeUiState())

    fun startScan() {
        if (scanJob?.isActive == true) return
        // Checked proactively, not just caught below, so a disabled radio never even reaches
        // Kable's scanner — it throws (UnmetRequirementException) rather than just failing
        // quietly, which used to crash the whole app since nothing here caught it.
        if (!bluetoothStateRepository.isEnabled()) {
            bluetoothWarningFlow.value = "Bluetooth is off — turn it on to discover nearby devices"
            return
        }
        scanJob = viewModelScope.launch {
            try {
                muleRepository.scanForDevices().collect { advertisement ->
                    // First successful emission proves the radio really is on and scanning —
                    // clears any stale warning left over from before it was turned on.
                    bluetoothWarningFlow.value = null
                    val address = advertisement.identifier
                    // Skip if this BLE identity is already tracked under any key (including a
                    // deviceId key it's since been merged into) — otherwise a device rescanned
                    // under the same address would be re-added and lose its accumulated state.
                    val alreadyTracked = discoveredFlow.value.values.any { it.requiredAdvertisement.identifier == address }
                    if (alreadyTracked) return@collect
                    discoveredFlow.value = discoveredFlow.value + (address to DiscoveredDevice(deviceKey = address, advertisement = advertisement))
                    launch { refreshDeviceInfo(address) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Defensive backstop for the proactive check above — covers the radio being
                // switched off in the brief window between that check and actually starting
                // the scan, and any other way Kable's scanner can fail. The Bluetooth-state
                // loop (see startBluetoothStateLoop) is what retries once it's viable again.
                bluetoothWarningFlow.value = "Bluetooth is off — turn it on to discover nearby devices"
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
                autoPullAndPushIfArmed()
            }
        }
    }

    // Only used for a newly-discovered device's first resolve (see startScan()) — the
    // periodic loop no longer needs a separate universal refresh pass, since
    // pullAllVisibleDevices() (called every tick from autoPullAndPushIfArmed) already
    // re-reads every currently-tracked device's info as a side effect regardless of
    // whether it ends up pulling anything, so a second full pass here would just double the
    // BLE traffic (and doubled contention) for no benefit.
    private suspend fun refreshDeviceInfo(key: String) {
        val device = discoveredFlow.value[key] ?: return
        val info = runCatching { muleRepository.readDeviceInfo(device.requiredAdvertisement) }.getOrNull()
        if (info == null) {
            markUnreachable(key, device)
            return
        }
        mergeDeviceInfo(key, device, info)
    }

    /** Folds a freshly-read [DeviceInfo] into [discoveredFlow], keyed by the stable
     *  `deviceId` rather than [oldKey] (a BLE address) — merging into any entry already
     *  tracked under that deviceId instead of creating a duplicate, and accumulating this
     *  role's count alongside whatever the other role's last-known count was. A successful
     *  read is by definition proof of reachability, so it always clears
     *  [DiscoveredDevice.unreachable] and [DiscoveredDevice.consecutiveFailures], and bumps
     *  [DiscoveredDevice.lastReachableAtMillis] to now. */
    private fun mergeDeviceInfo(oldKey: String, device: DiscoveredDevice, info: DeviceInfo) {
        val newKey = info.deviceId
        val current = discoveredFlow.value
        val base = current[newKey] ?: device
        val merged = base.copy(
            deviceKey = newKey,
            advertisement = device.advertisement,
            deviceId = newKey,
            deviceName = info.deviceName,
            raceLabel = info.raceLabel,
            roleCounts = base.roleCounts + (info.deviceRole to info.unsyncedCount),
            lastReachableAtMillis = System.currentTimeMillis(),
            consecutiveFailures = 0,
            unreachable = false,
        )
        discoveredFlow.value = (current - oldKey - newKey) + (newKey to merged)
    }

    /** Records a device that just failed to answer a read. If it's been unreachable
     *  continuously since before its drop threshold ago, drops it from the list entirely.
     *  Otherwise bumps [DiscoveredDevice.consecutiveFailures] and only actually flips
     *  [DiscoveredDevice.unreachable] (turning the list entry red) once that streak reaches
     *  [UNREACHABLE_FAILURE_THRESHOLD] — see [DiscoveredDevice]'s doc for why a single missed
     *  read shouldn't flip the color on its own.
     *
     *  A device that's never once completed a [DeviceInfo] read (still keyed by its raw BLE
     *  address, [DiscoveredDevice.deviceId] null — see [DiscoveredDevice]'s "Discovering…"
     *  state) gets a much shorter drop grace period than one that's genuinely gone quiet after
     *  being seen properly. That distinction matters because Android periodically rotates a
     *  device's random BLE advertising address for privacy (often every ~15 minutes, and can
     *  be more frequent) — every rotation makes [startScan] treat it as a brand new address
     *  it's never met before, so a device this phone simply can't complete a GATT read
     *  against (out of practical range, mid-connection to someone else, momentarily
     *  overwhelmed by too many concurrent scan hits) piles up an endless trail of
     *  never-resolving "Discovering…" ghosts, one per rotation, if left at the same one-hour
     *  grace period as a properly-known device — confirmed in the field as "lots of stalled
     *  ##:##:## — Discovering… lines" that only cleared by leaving and re-entering Mule
     *  Mode (which just recreates discoveredFlow from empty). A device that *has* resolved
     *  at least once keeps the full hour, so it isn't lost from the list just because the
     *  operator stepped out of range briefly. */
    private fun markUnreachable(key: String, device: DiscoveredDevice) {
        val now = System.currentTimeMillis()
        val dropThreshold = if (device.deviceId == null) UNRESOLVED_DROP_THRESHOLD else UNREACHABLE_DROP_THRESHOLD
        if (now - device.lastReachableAtMillis >= dropThreshold.inWholeMilliseconds) {
            discoveredFlow.value = discoveredFlow.value - key
            return
        }
        val failures = device.consecutiveFailures + 1
        discoveredFlow.value = discoveredFlow.value + (
            key to device.copy(consecutiveFailures = failures, unreachable = failures >= UNREACHABLE_FAILURE_THRESHOLD)
        )
    }

    private suspend fun autoPullAndPushIfArmed() {
        if (busyFlow.value) return
        // Checked directly against the raw persisted signal rather than the derived
        // uiState — that StateFlow only updates while something is actively observing it
        // (WhileSubscribed), so it could be stale for this background loop specifically.
        //
        // Deliberately *not* gated on login/dataset here — pulling is a purely local BLE
        // operation into Mule's own inbox, and every device visible should end up captured
        // there (and colored green once caught up) regardless of whether *this* phone is
        // logged in to push anywhere yet. Only the push phase below needs that, and
        // pushIfNeeded() already no-ops quietly if it isn't configured. Without this split,
        // an unlogged-in phone would never auto-pull from anyone — including itself — and
        // its nearby-devices list would stay red forever no matter how caught up it really
        // was, which is exactly the inconsistency this is fixing.
        if (muleRepository.autoSyncStopped.first()) return

        busyFlow.value = true
        val tickFailure = pullAllVisibleDevices()
        busyFlow.value = false
        // Self-clearing: a tick with no failures (including one where everything is already
        // synced and there was nothing to attempt) wipes out any earlier stale warning,
        // instead of it sticking around after the connection has actually recovered.
        autoWarningFlow.value = tickFailure

        pushIfNeeded(auto = true)
    }

    /** Pulls from every currently-visible BLE device — no attach step, no per-role limit, no
     *  race-label matching: anything Mule can see, it pulls from — plus this phone's own
     *  unsynced data. Folds fresh [DeviceInfo] into [discoveredFlow] as it goes so the
     *  nearby-devices list's red/green status stays current even on ticks that don't end up
     *  pulling anything. Returns a failure message from the last device that didn't respond
     *  this tick, or null if every attempt succeeded (including "nothing to pull"). */
    private suspend fun pullAllVisibleDevices(): String? {
        var tickFailure: String? = null
        for ((key, device) in discoveredFlow.value.toList()) {
            val freshInfo = runCatching { muleRepository.readDeviceInfo(device.requiredAdvertisement) }.getOrNull()
            if (freshInfo == null) {
                tickFailure = "Auto-pull failed: couldn't reach ${device.deviceName.ifEmpty { device.deviceKey.take(8) }}"
                markUnreachable(key, device)
                continue
            }
            mergeDeviceInfo(key, device, freshInfo)
            if (freshInfo.unsyncedCount <= 0) continue
            val result = runCatching { muleRepository.pullFrom(device.requiredAdvertisement, freshInfo.deviceRole, freshInfo.raceLabel) }
            result.onFailure { tickFailure = "Auto-pull failed: ${it.message}" }
            result.onSuccess {
                // Reflects the drop in unsynced count immediately rather than waiting for
                // the next periodic refresh, up to AUTO_SYNC_INTERVAL later.
                runCatching { muleRepository.readDeviceInfo(device.requiredAdvertisement) }.getOrNull()
                    ?.let { mergeDeviceInfo(key, device, it) }
            }
        }
        runCatching { pullSelfRecords() }.onFailure { tickFailure = "Auto-pull failed: ${it.message}" }
        return tickFailure
    }

    /** The self-entry's counterpart to a BLE pull — no radio involved, so this reads the
     *  active race's own unsynced splits/entries straight from the local database and drops
     *  them into the same Mule inbox a BLE pull would have, tagged with this device's own
     *  role(s)/race label exactly like a genuinely pulled source. From there they're
     *  indistinguishable from anything pulled from a real nearby device — same push-to-server
     *  path, same "Mule source" history entry. Also marks the source splits/entries synced
     *  locally, mirroring what a remote Mule's BLE ack would otherwise have done — without
     *  this, self's own unsynced count (and the Bibs/Time screens' own red/green rows) would
     *  never reflect having been pulled. */
    private suspend fun pullSelfRecords(): Int {
        val raceId = settingsRepository.activeRaceId.first() ?: return 0
        val race = raceRepository.getRace(raceId)
        val raceLabel = race?.label.orEmpty()
        var total = 0
        val splits = timeModeRepository.getUnsyncedSplits(raceId)
        if (splits.isNotEmpty()) {
            val records = splits.map { it.toSyncRecord(race?.timeModeStartedAtMillis) }
            total += muleRepository.pullFromSelf(DEVICE_ROLE_TIME, raceLabel, records)
            timeModeRepository.markSplitsSyncedByUuid(records.map { it.recordUuid })
        }
        val entries = bibsModeRepository.getUnsyncedEntries(raceId)
        if (entries.isNotEmpty()) {
            val records = entries.map { it.toSyncRecord() }
            total += muleRepository.pullFromSelf(DEVICE_ROLE_BIBS, raceLabel, records)
            bibsModeRepository.markEntriesSyncedByUuid(records.map { it.recordUuid })
        }
        return total
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

    /** Immediate one-off pull-from-everything-visible + push, regardless of the stopped
     *  flag — doesn't itself resume auto-sync, it's just "do it right now". */
    fun forceSyncNow() {
        viewModelScope.launch {
            busyFlow.value = true
            pullAllVisibleDevices()
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
        private val AUTO_SYNC_INTERVAL = 10_000.milliseconds
        private val BLUETOOTH_CHECK_INTERVAL = 3_000.milliseconds
        private val UNREACHABLE_DROP_THRESHOLD = 60.minutes
        private val UNRESOLVED_DROP_THRESHOLD = 2.minutes

        // A single missed BLE read is ordinary noise (see DiscoveredDevice's doc comment) —
        // this many *consecutive* misses (roughly this many AUTO_SYNC_INTERVAL ticks) before
        // a device is actually flagged unreachable/shown red.
        private const val UNREACHABLE_FAILURE_THRESHOLD = 3

        // Never a real BLE address (those are always MAC-formatted), so this can't collide
        // with a genuinely discovered device's deviceKey/deviceId.
        private const val SELF_DEVICE_KEY = "self"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                MuleModeViewModel(
                    container.muleRepository,
                    container.bluetoothStateRepository,
                    container.raceRepository,
                    container.timeModeRepository,
                    container.bibsModeRepository,
                    container.settingsRepository,
                )
            }
        }
    }
}
