package mobile.racemaster.data.mule

import android.util.Log
import com.juul.kable.Advertisement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.TimeModeRepository
import mobile.racemaster.data.settings.SettingsRepository

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
 *  [lastReachableAtMillis] and [unreachable] exist so the list's colour reflects *current*
 *  reachability rather than whatever was last successfully read — without them, a device
 *  that's gone out of range still shows the stale green/red from its last successful
 *  contact. A device stays listed (marked [unreachable]) rather than disappearing on the
 *  first failure, since a single missed BLE read is common and not worth losing the entry
 *  over — it's only dropped from [MuleSyncEngine]'s map once it's been continuously
 *  unreachable for [MuleSyncEngine.Companion.UNREACHABLE_DROP_THRESHOLD].
 *
 *  [consecutiveFailures] debounces [unreachable] itself: a *single* missed read is ordinary
 *  BLE noise (two radios doing central+peripheral duty at once — this phone scanning
 *  everyone else while also advertising and running its own GATT server — routinely drops
 *  the occasional connection attempt, especially on weaker chipsets), not a sign the device
 *  actually went anywhere. Flipping straight to red on every such blip made a device that
 *  was perfectly reachable flicker red "from time to time" for no real reason (confirmed in
 *  the field, worst on budget hardware). Only a run of consecutive failures now flips the
 *  colour — see [MuleSyncEngine.Companion.UNREACHABLE_FAILURE_THRESHOLD] — while a single
 *  miss still resets [lastReachableAtMillis]'s drop-timer contribution not at all (that
 *  timer only cares about the *last success*, so a debounced blip doesn't quietly extend a
 *  device's borrowed time either). Surfaced directly on the device's own row as a running
 *  "(missed N)" suffix below the threshold, then "(unreachable)" once it's crossed (see
 *  MuleModeScreen.NearbyDevicesSection) — not a shared banner naming only one device, which
 *  was misleading whenever more than one had actually dropped out. */
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

// discoveredFlow (see below) only ever holds devices that came from a live BLE scan result
// (see startScan()) — the self entry is added separately, purely at uiState's final merge
// step, and is never inserted into discoveredFlow itself. So every DiscoveredDevice reached
// through discoveredFlow — the only thing every BLE-specific call site below operates on —
// is guaranteed to carry a real advertisement, unlike the nullable-in-general field.
private val DiscoveredDevice.requiredAdvertisement: Advertisement
    get() = advertisement ?: error("Expected a BLE-scanned device, got the self entry")

/**
 * Owns Mule Mode's entire background job: scanning for nearby Time/Bibs/Mule phones, pulling
 * whatever they're holding (plus this phone's own unsynced data) into the local inbox, and
 * pushing everything on to the server — all continuously, for the life of the process, via
 * [start] (called once from [PeripheralSyncService.onCreate]), independent of whether the
 * operator is actually looking at the Mule Mode screen. This is what lets a single phone
 * record Time or Bibs mode *and* act as a Mule for every other nearby device at the same
 * time, instead of Mule's sync only running while its own screen happens to be open.
 * [mobile.racemaster.ui.mulemode.MuleModeViewModel] is a thin presentation-layer wrapper
 * around this — it renders these flows and forwards button taps to [forceSyncNow] etc., but
 * owns none of the actual scanning/pulling/pushing itself, so none of it stops when that
 * screen (or its ViewModel) goes away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MuleSyncEngine(
    private val muleRepository: MuleRepository,
    private val bluetoothStateRepository: BluetoothStateRepository,
    private val raceRepository: RaceRepository,
    private val timeModeRepository: TimeModeRepository,
    private val bibsModeRepository: BibsModeRepository,
    private val settingsRepository: SettingsRepository,
) {
    // A background engine that talks to arbitrary other phones over BLE regardless of what
    // screen (if any) is currently showing must never let a stray uncaught exception take
    // down the whole app — the operator could be mid-race on Time or Bibs mode. Individual
    // call sites below still catch what they can anticipate; this is the last-resort net.
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught exception in MuleSyncEngine — swallowed to avoid crashing the app", throwable)
    }
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    private val discoveredFlow = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
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

    @Volatile
    private var started = false

    val discoveredDevices: StateFlow<Map<String, DiscoveredDevice>> = discoveredFlow.asStateFlow()
    val statusMessage: StateFlow<String?> = statusMessageFlow.asStateFlow()
    val autoWarning: StateFlow<String?> = autoWarningFlow.asStateFlow()
    val bluetoothWarning: StateFlow<String?> = bluetoothWarningFlow.asStateFlow()
    val isBusy: StateFlow<Boolean> = busyFlow.asStateFlow()

    // This device's own unsynced data, shaped as one more DiscoveredDevice (isSelf = true) so
    // it can be folded straight into the same list real BLE-discovered devices render in —
    // "self" is exactly as much a sync candidate as anything found over the radio. Always
    // shown, with an empty raceLabel/roleCounts while there's no active race — same as any
    // other device Mule can see that hasn't got a race defined either (see
    // MuleModeScreen.NearbyDevicesSection's "no race" label), never hidden entirely just
    // because this device itself has nothing recorded right now.
    val selfDevice: Flow<DiscoveredDevice> = settingsRepository.activeRaceId
        .flatMapLatest { raceId ->
            if (raceId == null) {
                muleRepository.deviceName.map { deviceName ->
                    DiscoveredDevice(
                        deviceKey = SELF_DEVICE_KEY,
                        advertisement = null,
                        deviceId = SELF_DEVICE_KEY,
                        deviceName = deviceName.orEmpty(),
                        raceLabel = "",
                        roleCounts = emptyMap(),
                        isSelf = true,
                    )
                }
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

    /** Idempotent — starts the background scan/auto-sync/Bluetooth-state loops exactly once
     *  for the life of the process, however many times (or from wherever) this is called.
     *  Called from [PeripheralSyncService.onCreate], so this engine's sync keeps running
     *  regardless of which mode's screen the operator is actually looking at. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        startScan()
        startAutoSyncLoop()
        startBluetoothStateLoop()
    }

    // Polls rather than registering a BluetoothAdapter.ACTION_STATE_CHANGED receiver — this
    // engine already polls for the auto-sync loop on a similar cadence, so one more simple
    // loop is more consistent with the rest of this file than a second, receiver-based
    // mechanism would be. Re-tries startScan() (a no-op if it's already running) on every
    // tick, which is what actually recovers scanning once the operator turns Bluetooth back
    // on — nothing else here would otherwise notice and restart it.
    private fun startBluetoothStateLoop() {
        engineScope.launch {
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

    private fun startScan() {
        if (scanJob?.isActive == true) return
        // Checked proactively, not just caught below, so a disabled radio never even reaches
        // Kable's scanner — it throws (UnmetRequirementException) rather than just failing
        // quietly, which used to crash the whole app since nothing here caught it.
        if (!bluetoothStateRepository.isEnabled()) {
            bluetoothWarningFlow.value = "Bluetooth is off — turn it on to discover nearby devices"
            return
        }
        // The radio's confirmed on at this point, so any warning still showing is stale —
        // clear it now rather than waiting for the collect below to see a first advertisement,
        // which never happens (leaving a false "Bluetooth is off" stuck on screen) when
        // scanning is perfectly healthy but simply no peer device happens to be nearby.
        bluetoothWarningFlow.value = null
        scanJob = engineScope.launch {
            try {
                muleRepository.scanForDevices().collect { advertisement ->
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
            } catch (_: SecurityException) {
                // BLUETOOTH_SCAN missing or revoked — distinct from the radio being off, and
                // "turn Bluetooth on" would be actively misleading here. Same retry-on-next-tick
                // behavior via startBluetoothStateLoop, in case it's granted later.
                bluetoothWarningFlow.value = "Bluetooth permission needed to discover nearby devices"
            } catch (_: Exception) {
                // Defensive backstop for the proactive check above — covers the radio being
                // switched off in the brief window between that check and actually starting
                // the scan, and any other way Kable's scanner can fail. The Bluetooth-state
                // loop (see startBluetoothStateLoop) is what retries once it's viable again.
                bluetoothWarningFlow.value = "Bluetooth is off — turn it on to discover nearby devices"
            }
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    private fun startAutoSyncLoop() {
        engineScope.launch {
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
        val since = muleRepository.lastPulledLineNumber(info.deviceId, info.raceLabel)
        mergeDeviceInfo(key, device, info, since)
    }

    /** Folds a freshly-read [DeviceInfo] into [discoveredFlow], keyed by the stable
     *  `deviceId` rather than [oldKey] (a BLE address) — merging into any entry already
     *  tracked under that deviceId instead of creating a duplicate, and accumulating this
     *  role's outstanding-line count (info.lastLineNumber minus [lastPulledLineNumber], the
     *  delta already computed by the caller) alongside whatever the other role's last-known
     *  count was. A successful read is by definition proof of reachability, so it always
     *  clears [DiscoveredDevice.unreachable] and [DiscoveredDevice.consecutiveFailures], and
     *  bumps [DiscoveredDevice.lastReachableAtMillis] to now. */
    private fun mergeDeviceInfo(oldKey: String, device: DiscoveredDevice, info: DeviceInfo, lastPulledLineNumber: Long) {
        val newKey = info.deviceId
        val current = discoveredFlow.value
        val base = current[newKey] ?: device
        val outstanding = (info.lastLineNumber - lastPulledLineNumber).coerceAtLeast(0).toInt()
        val merged = base.copy(
            deviceKey = newKey,
            advertisement = device.advertisement,
            deviceId = newKey,
            deviceName = info.deviceName,
            raceLabel = info.raceLabel,
            roleCounts = base.roleCounts + (info.deviceRole to outstanding),
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
        // Deliberately *not* gated on login here — pulling is a purely local BLE operation
        // into Mule's own inbox, and every device visible should end up captured there (and
        // colored green once caught up) regardless of whether *this* phone is logged in to
        // push anywhere yet. Only the push phase below needs that, and pushIfNeeded() already
        // no-ops quietly if it isn't configured. Without this split, an unlogged-in phone
        // would never auto-pull from anyone — including itself — and its nearby-devices list
        // would stay red forever no matter how caught up it really was, which is exactly the
        // inconsistency this is fixing.
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
     *  pulling anything. A device that simply couldn't be reached this tick doesn't set the
     *  returned failure message — that's tracked per-device instead (see [markUnreachable] /
     *  [DiscoveredDevice.consecutiveFailures], shown as a "(missed N)"/"(unreachable)" suffix
     *  on that device's own row — a single shared banner naming only the last unreachable
     *  device of however many was misleading when more than one had actually dropped out).
     *  Returns a failure message from the last device whose *pull itself* failed (having
     *  already proven reachable via a successful DeviceInfo read) this tick, or null if every
     *  attempt succeeded (including "nothing to pull"). */
    private suspend fun pullAllVisibleDevices(): String? {
        var tickFailure: String? = null
        for ((key, device) in discoveredFlow.value.toList()) {
            val freshInfo = runCatching { muleRepository.readDeviceInfo(device.requiredAdvertisement) }.getOrNull()
            if (freshInfo == null) {
                markUnreachable(key, device)
                continue
            }
            val since = muleRepository.lastPulledLineNumber(freshInfo.deviceId, freshInfo.raceLabel)
            mergeDeviceInfo(key, device, freshInfo, since)
            if (freshInfo.lastLineNumber - since <= 0) continue
            val result = runCatching {
                muleRepository.pullFrom(
                    device.requiredAdvertisement,
                    freshInfo.deviceRole,
                    freshInfo.raceLabel,
                    freshInfo.deviceId,
                    freshInfo.deviceName,
                    since,
                )
            }
            result.onFailure { tickFailure = "Auto-pull failed: ${it.message}" }
            result.onSuccess {
                // Reflects the drop in outstanding lines immediately rather than waiting for
                // the next periodic refresh, up to AUTO_SYNC_INTERVAL later.
                runCatching { muleRepository.readDeviceInfo(device.requiredAdvertisement) }.getOrNull()?.let {
                    val newSince = muleRepository.lastPulledLineNumber(it.deviceId, it.raceLabel)
                    mergeDeviceInfo(key, device, it, newSince)
                }
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
     *  never reflect having been pulled. Since this engine runs continuously regardless of
     *  which screen is showing (see class doc), this is what lets a phone actively recording
     *  Time or Bibs mode sync its own data to the server at the same time, with no separate
     *  physical Mule and no direct self-push path needed. */
    private suspend fun pullSelfRecords(): Int {
        val raceId = settingsRepository.activeRaceId.first() ?: return 0
        val race = raceRepository.getRace(raceId)
        val raceLabel = race?.label.orEmpty()
        val deviceName = race?.createdByDeviceName.orEmpty()
        val myDeviceId = settingsRepository.getOrCreateDeviceId()
        var total = 0
        val splits = timeModeRepository.getUnsyncedSplits(raceId)
        if (splits.isNotEmpty()) {
            val records = splits.map { it.toSyncRecord(race?.timeModeStartedAtMillis) }
            total += muleRepository.pullFromSelf(DEVICE_ROLE_TIME, raceLabel, myDeviceId, deviceName, records)
            timeModeRepository.markSplitsSyncedByUuid(records.map { it.recordUuid })
        }
        val entries = bibsModeRepository.getUnsyncedEntries(raceId)
        if (entries.isNotEmpty()) {
            val records = entries.map { it.toSyncRecord(race?.timeModeStartedAtMillis) }
            total += muleRepository.pullFromSelf(DEVICE_ROLE_BIBS, raceLabel, myDeviceId, deviceName, records)
            bibsModeRepository.markEntriesSyncedByUuid(records.map { it.recordUuid })
        }
        return total
    }

    /** Immediate one-off pull-from-everything-visible + push, regardless of the stopped
     *  flag — doesn't itself resume auto-sync, it's just "do it right now". `pushIfNeeded`
     *  (called with auto=false below) always sets `statusMessageFlow` itself to the real
     *  outcome ("Pushed N records", "Push failed: ...", "Nothing to push", etc.) — don't
     *  stomp on that with a blanket "success" message afterwards, or a genuine push failure
     *  becomes invisible to the operator. */
    fun forceSyncNow() {
        engineScope.launch {
            busyFlow.value = true
            pullAllVisibleDevices()
            busyFlow.value = false
            pushIfNeeded(auto = false)
            autoWarningFlow.value = null
        }
    }

    // The cheap local-only unsyncedCount check is only a valid shortcut for a background auto
    // tick (skip the network round-trip on the overwhelmingly common case that nothing local
    // changed since the last one). A manual Force Sync must not trust it: pushToServer()
    // itself always re-checks the server's actual stored state (see its own doc) and only
    // marks a row synced once that fresh check confirms it — a row that silently failed to
    // land on the server (dropped mid-request, a server-side hiccup, ...) would otherwise
    // never get retried, since the local unsyncedCount gate would keep skipping it forever
    // once every local row already *looks* synced. Force Sync is exactly the operator's "no,
    // really check" button, so it always runs the real reconciliation when logged in.
    private suspend fun pushIfNeeded(auto: Boolean) {
        if (!muleRepository.isLoggedIn.first()) {
            if (!auto) statusMessageFlow.value = "Push failed: not logged in"
            return
        }
        if (auto && muleRepository.unsyncedCount.first() <= 0) {
            return
        }
        busyFlow.value = true
        val result = runCatching { muleRepository.pushToServer() }
        busyFlow.value = false
        pushResultMessage(auto, result)?.let { statusMessageFlow.value = it }
    }

    fun dismissStatusMessage() {
        statusMessageFlow.value = null
    }

    companion object {
        private const val TAG = "MuleSyncEngine"
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
    }
}

/**
 * The status message (if any) a push attempt's outcome should surface, pulled out as a pure
 * function so it's directly testable without standing up MuleSyncEngine's full BLE/HTTP
 * dependency graph. A genuinely successful automatic push is surfaced too, not just failures
 * — previously an operator watching Mule Mode passively had no way to tell a successful
 * background push had happened at all, only ever seeing a message on a manual "Force sync
 * now" tap or on failure. Still suppressed (returns null) when there was genuinely nothing new
 * (n == 0) on an automatic tick, so this doesn't spam a message every 10s tick once a device
 * is fully caught up. A manual push (auto = false) always gets a message, including "Pushed 0
 * new records" so a no-op manual tap still confirms something happened.
 */
internal fun pushResultMessage(auto: Boolean, result: Result<Int>): String? = result.fold(
    onSuccess = { n ->
        if (!auto || n > 0) "Pushed $n new record${if (n == 1) "" else "s"} to the server" else null
    },
    onFailure = { e -> "Push failed: ${e.message}" },
)
