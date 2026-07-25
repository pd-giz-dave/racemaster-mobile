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
 *  raw BLE address as a placeholder key before the first successful [DeviceInfo] read. Which
 *  mode screen a phone currently has open is irrelevant here and deliberately not tracked at
 *  all — from a puller's point of view a device is just "how much pending data does it have"
 *  ([unsyncedCount]), a single whole-race count regardless of Time/Bibs, since both share one
 *  lineNumber sequence (see [DeviceInfo.lastLineNumber]'s own doc). An earlier version tracked
 *  that count per currently-reported role and summed the roles for display — which
 *  double-counted the moment the same phone was later read under a *different* role while the
 *  previous role's now-stale entry was still cached (confirmed in the field: the unsynced
 *  badge for a phone that switched from Bibs to Time roughly doubled instead of just
 *  reflecting its one true outstanding count).
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
 *  was misleading whenever more than one had actually dropped out.
 *
 *  [relayedViaDeviceName] is non-null exactly for a synthetic row representing an origin known
 *  only *transitively* — data this phone doesn't have direct BLE visibility of, only reachable
 *  because some other Mule ([relayedViaDeviceName]) is currently relaying it (see
 *  [MuleSyncEngine.relayDevices]). Such a row has no [advertisement] of its own (nothing to
 *  connect to directly) and doesn't participate in the [unreachable]/[consecutiveFailures]
 *  reachability tracking above — those describe *this phone's own* BLE link to a peer, which
 *  is meaningless for an origin it's never actually connected to. */
data class DiscoveredDevice(
    val deviceKey: String,
    val advertisement: Advertisement?,
    val deviceId: String? = null,
    val deviceName: String = "",
    val raceLabel: String = "",
    val unsyncedCount: Int = 0,
    val isSelf: Boolean = false,
    val lastReachableAtMillis: Long = System.currentTimeMillis(),
    val consecutiveFailures: Int = 0,
    val unreachable: Boolean = false,
    val relayedViaDeviceName: String? = null,
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
    settingsRepository: SettingsRepository,
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
    // Origins known only transitively, via some other Mule's relay manifest — kept separate
    // from discoveredFlow (rather than folded straight in) so discoveredFlow's own documented
    // guarantee that every entry carries a real Advertisement (see requiredAdvertisement above)
    // stays true; MuleModeViewModel merges this in alongside discoveredFlow/selfDevice for
    // display, the same way it already folds selfDevice in. Rebuilt from scratch every
    // pullAllVisibleDevices() tick from whichever peers were actually reachable that tick, so a
    // relay row for an origin no longer offered by any currently-visible peer simply drops
    // rather than lingering as a stale ghost entry.
    private val relayFlow = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
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
    val relayDevices: StateFlow<Map<String, DiscoveredDevice>> = relayFlow.asStateFlow()
    val statusMessage: StateFlow<String?> = statusMessageFlow.asStateFlow()
    val autoWarning: StateFlow<String?> = autoWarningFlow.asStateFlow()
    val bluetoothWarning: StateFlow<String?> = bluetoothWarningFlow.asStateFlow()
    val isBusy: StateFlow<Boolean> = busyFlow.asStateFlow()

    // This device's own unsynced data, shaped as one more DiscoveredDevice (isSelf = true) so
    // it can be folded straight into the same list real BLE-discovered devices render in —
    // "self" is exactly as much a sync candidate as anything found over the radio. Always
    // shown, with an empty raceLabel and zero unsyncedCount while there's no active race —
    // same as any other device Mule can see that hasn't got a race defined either (see
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
                        unsyncedCount = 0,
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
                        // Time and Bibs are independently-scoped counts (a HistoryLineEntity
                        // row has exactly one mode), so summing them is safe here — unlike the
                        // BLE-pulled path below, there's no risk of double-counting the same
                        // line under two different reads.
                        unsyncedCount = unsyncedSplits + unsyncedEntries,
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
                if (muleRepository.bluetoothOff.first()) {
                    // Explicit operator choice, not a warning — tearing down the scan here
                    // (rather than leaving it running and just discarding results) is what
                    // stops this phone showing up as "still discovering" to itself and lets
                    // startScan() rebuild discoveredFlow from scratch once back online, same
                    // as re-entering Mule Mode already does.
                    stopScan()
                    discoveredFlow.value = emptyMap()
                    bluetoothWarningFlow.value = null
                } else if (bluetoothStateRepository.isEnabled()) {
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
     *  tracked under that deviceId instead of creating a duplicate, and overwriting (not
     *  accumulating — see [DiscoveredDevice]'s own doc for why) its outstanding-line count
     *  with this fresh one (info.lastLineNumber minus [lastPulledLineNumber], the delta
     *  already computed by the caller). A successful read is by definition proof of
     *  reachability, so it always clears [DiscoveredDevice.unreachable] and
     *  [DiscoveredDevice.consecutiveFailures], and bumps [DiscoveredDevice.lastReachableAtMillis]
     *  to now. */
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
            unsyncedCount = outstanding,
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
        // Deliberately *not* gated on bluetoothOff or login here — pulling from another device
        // over BLE is a purely local operation into Mule's own inbox, and every such device
        // visible should end up captured there (and colored green once caught up) regardless
        // of whether *this* phone is logged in to push anywhere yet (in which case
        // discoveredFlow is simply empty and this loop does nothing). Only the push phase
        // below needs the login/server-sync gates, and pushIfNeeded() already no-ops quietly
        // if it isn't configured. This device's own "self" status (see selfDevice) is a
        // different story now: since pushToServer builds and confirms self's own data directly
        // against the server rather than staging it into a local inbox first, self's own
        // green/red genuinely does depend on being logged in and reachable — an honest change
        // from the old design, where self could show green the instant it was merely handed
        // off locally, before ever actually reaching the server.
        if (muleRepository.autoSyncStopped.first()) return

        // try/finally, not a bare set-true/set-false either side of the call: MulePullClient's
        // own CONNECT_TIMEOUT/PULL_TIMEOUT already bound how long a single stuck device can
        // hang pullAllVisibleDevices for, but this is the backstop that guarantees busyFlow
        // still gets cleared even if something unexpected still throws (or a future change
        // reintroduces an unbounded suspend somewhere in that call chain) — without it, every
        // future tick's `if (busyFlow.value) return` guard above would wedge shut forever,
        // exactly the "stuck" symptom this whole timeout pass fixes.
        busyFlow.value = true
        val tickFailure = try {
            pullAllVisibleDevices()
        } finally {
            busyFlow.value = false
        }
        // Self-clearing: a tick with no failures (including one where everything is already
        // synced and there was nothing to attempt) wipes out any earlier stale warning,
        // instead of it sticking around after the connection has actually recovered.
        autoWarningFlow.value = tickFailure

        pushIfNeeded(auto = true)
    }

    /** Pulls from every currently-visible BLE device — no attach step, no per-role limit, no
     *  race-label matching: anything Mule can see, it pulls from. This device's own data is
     *  never "pulled" at all (see [MuleRepository.pushToServer]'s own doc) — pushIfNeeded's own
     *  push call reads it fresh straight from the source, so there's nothing self-specific to
     *  do here. Folds fresh [DeviceInfo] into [discoveredFlow] as it goes so the
     *  nearby-devices list's red/green status stays current even on ticks that don't end up
     *  pulling anything. A device that simply couldn't be reached this tick doesn't set the
     *  returned failure message — that's tracked per-device instead (see [markUnreachable] /
     *  [DiscoveredDevice.consecutiveFailures], shown as a "(missed N)"/"(unreachable)" suffix
     *  on that device's own row — a single shared banner naming only the last unreachable
     *  device of however many was misleading when more than one had actually dropped out).
     *
     *  Beyond each visible peer's own race, this also walks its [DeviceInfo.relayEntries] —
     *  whatever *that* peer is separately holding for other, genuinely different origin
     *  devices — and pulls those the exact same way, just tagged with [PullRequest.originDeviceId]
     *  instead of implicit (see [MuleRepository.pullFrom]'s own doc). The resume cursor for a
     *  relay entry is looked up by its true origin id/race label
     *  ([MuleRepository.lastPulledLineNumber]), never by which peer is currently offering it —
     *  that's the entire loop-prevention mechanism this feature relies on: once this device
     *  already holds an origin's data up to line N, it will never re-request anything below N
     *  from *any* neighbor, direct or relayed, so redundant transfer (and any risk of data
     *  bouncing indefinitely around a multi-mule mesh) is bounded to at most one wasted
     *  "nothing new" round-trip per redundant path, with no hop-count/TTL/visited-device-list
     *  needed. The one required guard is skipping an entry whose origin is *this* device itself
     *  — never re-pull my own data handed back to me by a mule that happened to relay it.
     *  Relay-derived rows land in [relayFlow], rebuilt fresh every tick (see its own doc) rather
     *  than folded into [discoveredFlow] — see [DiscoveredDevice.relayedViaDeviceName]'s doc for
     *  why that separation matters. [dedupRelayRows] drops a relay row the moment its origin
     *  becomes directly BLE-visible, so the same source never doubles up in the list.
     *
     *  Returns a failure message from the last device whose *pull itself* failed (having
     *  already proven reachable via a successful DeviceInfo read) this tick, or null if every
     *  attempt succeeded (including "nothing to pull"). */
    private suspend fun pullAllVisibleDevices(): String? {
        var tickFailure: String? = null
        val myDeviceId = muleRepository.myDeviceId()
        val relayRows = mutableMapOf<String, DiscoveredDevice>()
        for ((key, device) in discoveredFlow.value.toList()) {
            val freshInfo = runCatching { muleRepository.readDeviceInfo(device.requiredAdvertisement) }.getOrNull()
            if (freshInfo == null) {
                markUnreachable(key, device)
                continue
            }
            val since = muleRepository.lastPulledLineNumber(freshInfo.deviceId, freshInfo.raceLabel)
            mergeDeviceInfo(key, device, freshInfo, since)
            if (freshInfo.lastLineNumber - since > 0) {
                val result = runCatching {
                    muleRepository.pullFrom(
                        device.requiredAdvertisement,
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

            for (entry in relevantRelayEntries(myDeviceId, freshInfo.relayEntries)) {
                val relayKey = "relay:${entry.originDeviceId}:${entry.originRaceLabel}"
                val relaySince = muleRepository.lastPulledLineNumber(entry.originDeviceId, entry.originRaceLabel)
                val outstanding = (entry.lastLineNumber - relaySince).coerceAtLeast(0).toInt()
                relayRows[relayKey] = DiscoveredDevice(
                    deviceKey = relayKey,
                    advertisement = null,
                    deviceId = entry.originDeviceId,
                    deviceName = entry.originDeviceName,
                    raceLabel = entry.originRaceLabel,
                    unsyncedCount = outstanding,
                    relayedViaDeviceName = freshInfo.deviceName,
                )
                if (entry.lastLineNumber - relaySince <= 0) continue
                val relayResult = runCatching {
                    muleRepository.pullFrom(
                        device.requiredAdvertisement,
                        sourceRaceLabel = entry.originRaceLabel,
                        sourceDeviceId = entry.originDeviceId,
                        sourceDeviceName = entry.originDeviceName,
                        sinceLineNumber = relaySince,
                        requestOriginDeviceId = entry.originDeviceId,
                        requestOriginRaceLabel = entry.originRaceLabel,
                    )
                }
                relayResult.onFailure { tickFailure = "Auto-pull failed: ${it.message}" }
                relayResult.onSuccess {
                    val newSince = muleRepository.lastPulledLineNumber(entry.originDeviceId, entry.originRaceLabel)
                    val newOutstanding = (entry.lastLineNumber - newSince).coerceAtLeast(0).toInt()
                    relayRows[relayKey] = relayRows.getValue(relayKey).copy(unsyncedCount = newOutstanding)
                }
            }
        }
        val directDeviceIds = discoveredFlow.value.values.mapNotNull { it.deviceId }.toSet()
        relayFlow.value = dedupRelayRows(directDeviceIds, relayRows)
        return tickFailure
    }

    /** Immediate one-off pull-from-everything-visible + push, regardless of the stopped
     *  flag — doesn't itself resume auto-sync, it's just "do it right now". `pushIfNeeded`
     *  (called with auto=false below) always sets `statusMessageFlow` itself to the real
     *  outcome ("Pushed N records", "Push failed: ...", "Nothing to push", etc.) — don't
     *  stomp on that with a blanket "success" message afterwards, or a genuine push failure
     *  becomes invisible to the operator. */
    fun forceSyncNow() {
        engineScope.launch {
            // No bluetoothOff/serverSyncOff guard here — same reasoning as
            // autoPullAndPushIfArmed: this degrades gracefully (pullAllVisibleDevices' BLE loop
            // is naturally a no-op with an empty discoveredFlow, and pushIfNeeded checks
            // serverSyncOff itself), rather than needing to know about every combination here.
            //
            // try/finally — see autoPullAndPushIfArmed's own doc for why: without it, a stuck
            // device (bounded now, but still worth the backstop) would leave this button
            // permanently disabled (isBusy never clears) rather than just this one tick failing.
            busyFlow.value = true
            try {
                pullAllVisibleDevices()
            } finally {
                busyFlow.value = false
            }
            pushIfNeeded(auto = false)
            autoWarningFlow.value = null
        }
    }

    // Deliberately no local-only unsyncedCount shortcut here (there used to be one, skipping
    // the network round-trip on an auto tick whenever every local row already *looked*
    // synced) — pushToServer() itself always re-checks the server's actual stored state (see
    // its own doc) and only marks a row synced once that fresh check confirms it, which is
    // exactly what catches a server-side file that's gone missing (deleted, corrupted, a
    // restored-from-backup server, ...): a row this device already believes is synced would
    // otherwise never get re-sent, since the local unsyncedCount gate would keep skipping the
    // one check that could ever notice the server no longer has it. So every sync attempt,
    // automatic or manual, always runs the real reconciliation once logged in — this is a
    // deliberate trade of a bit of extra network chatter (a status check per distinct race
    // label this device has ever held, every ~10s) for self-healing against server data loss
    // without needing an operator to notice and hit Force Sync.
    private suspend fun pushIfNeeded(auto: Boolean) {
        if (muleRepository.serverSyncOff.first()) {
            if (!auto) statusMessageFlow.value = "Can't push while server sync is off"
            return
        }
        if (!muleRepository.isLoggedIn.first()) {
            if (!auto) statusMessageFlow.value = "Push failed: not logged in"
            return
        }
        // try/finally, same reasoning as the pull-phase callers above.
        busyFlow.value = true
        val result = try {
            runCatching { muleRepository.pushToServer() }
        } finally {
            busyFlow.value = false
        }
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

/**
 * Filters a peer's relay manifest down to entries actually worth acting on from my own point
 * of view — the one loop-prevention guard this design needs beyond the delta-cursor comparison
 * itself (see [MuleSyncEngine.pullAllVisibleDevices]'s own doc for why that comparison alone
 * already makes redundant re-transfer of anything already held a no-op regardless of path):
 * never treat my own data, handed back to me by a mule that happens to be relaying it, as
 * something worth pulling. Pulled out as a pure function so this guard is directly testable
 * without standing up MuleSyncEngine's full BLE dependency graph.
 */
internal fun relevantRelayEntries(myDeviceId: String, relayEntries: List<RelayManifestEntry>): List<RelayManifestEntry> =
    relayEntries.filter { it.originDeviceId != myDeviceId }

/**
 * Drops a relay-only row the instant its origin becomes directly BLE-visible — otherwise the
 * same source would show twice in the Mule Mode device list, once as a real (`isSelf = false`,
 * real [DiscoveredDevice.advertisement]) entry and once as a stale "(via X)" one, the moment
 * both the origin phone and the relaying mule are simultaneously in range. Pulled out as a pure
 * function for the same directly-testable reason as [relevantRelayEntries].
 */
internal fun dedupRelayRows(
    directDeviceIds: Set<String>,
    relayRows: Map<String, DiscoveredDevice>,
): Map<String, DiscoveredDevice> = relayRows.filterValues { it.deviceId !in directDeviceIds }
