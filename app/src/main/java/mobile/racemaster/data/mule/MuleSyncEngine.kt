package mobile.racemaster.data.mule

import android.util.Log
import com.juul.kable.Advertisement
import com.juul.kable.GattRequestRejectedException
import com.juul.kable.GattStatusException
import com.juul.kable.NotConnectedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import mobile.racemaster.data.db.dao.PulledSourceSummary
import mobile.racemaster.data.db.entity.KnownDeviceEntity
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.CpModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.TimeModeRepository
import mobile.racemaster.data.settings.AppMode
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
 *  is meaningless for an origin it's never actually connected to.
 *
 *  [isStale] marks a synthetic row built from [MuleRepository.knownDevices] rather than a live
 *  BLE scan result — a device this phone has identified before but can't currently see (see
 *  [previouslySeenDevices]). It carries no real [advertisement], no live
 *  unreachable/consecutiveFailures tracking (nothing to track — there's no current connection
 *  attempt), and [lastReachableAtMillis] means "last resolved at all" rather than "last
 *  reachable this session". Folded into the same sorted list [mobile.racemaster.ui.mulemode.MuleModeScreen] renders (rather
 *  than a separate section) so a device that's gone quiet sinks toward the bottom in place,
 *  differentiated visually by its own "last seen" text instead of a race label/sync status. */
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
    // Internal bookkeeping only (not rendered — relayedViaDeviceName is what the UI shows) —
    // the relaying peer's own discoveredFlow key, so pullAllVisibleDevices can tell "this relay
    // row came from a peer skipped this tick under shouldConnect's gate, carry it forward
    // unchanged" apart from "this peer was actually re-checked this tick, replace its rows with
    // whatever it reports now" without needing a display-name match (two peers could share a
    // name). See pullAllVisibleDevices' own doc.
    val relayedViaDeviceKey: String? = null,
    val isStale: Boolean = false,
    // The [DeviceInfo.lastLineNumber] this device last actually confirmed via a real GATT
    // connect — as opposed to whatever the scan-response payload currently advertises, which
    // is only ever a cheap, non-authoritative hint (see MuleGattProfile.AdvertisedIdentity's
    // own doc). Null until the very first successful read; see [shouldConnect].
    val confirmedLineNumber: Long? = null,
    // When [confirmedLineNumber] was last actually confirmed — 0 (never) until the first
    // successful read. Deliberately left untouched by a failed read (see markUnreachable) so a
    // flaky/unreachable device keeps looking "due for a real check" every tick, exactly as
    // before this whole gating mechanism existed — the optimization below only ever applies to
    // a healthy, unchanged device.
    val lastRealReadAtMillis: Long = 0L,
    // When this device's data (its own race directly, or — for a relay row — whatever it's
    // relaying) was last actually pulled — as opposed to [lastRealReadAtMillis], which also
    // bumps on a real read that found nothing new to pull. Null until the first successful
    // pull, or for a row nothing has ever been pulled from at all (self; a stale/previously-seen
    // row — see [mobile.racemaster.ui.mulemode.MuleModeViewModel]'s own doc on why those aren't
    // populated). Left for [mobile.racemaster.ui.mulemode.MuleModeViewModel] to fill in from
    // [mobile.racemaster.data.db.dao.PulledSourceSummary] (see [withLastPulledAtMillis]) rather
    // than tracked here directly — MuleRepository.pullFrom is this engine's own single call
    // site for every pull, direct or relayed alike, and PulledSourceSummary already reports
    // this per origin from there with no extra bookkeeping needed in this class.
    val lastPulledAtMillis: Long? = null,
    // The most recent connect-attempt failure against this device, classified by
    // [describeConnectFailure] — "timeout", "GATT error 133", etc. — shown alongside the
    // "(missed N)"/"(unreachable)" suffix on its own row (see MuleModeScreen) so a repeat field
    // occurrence is diagnosable from the phone itself rather than needing a laptop+logcat.
    // Cleared (null) the moment a read actually succeeds — see [mergeDeviceInfo] — so it never
    // shows a stale reason once the device is genuinely reachable again.
    val lastFailureReason: String? = null,
)

// discoveredFlow (see below) only ever holds devices that came from a live BLE scan result
// (see startScan()) — the self entry is added separately, purely at uiState's final merge
// step, and is never inserted into discoveredFlow itself. So every DiscoveredDevice reached
// through discoveredFlow — the only thing every BLE-specific call site below operates on —
// is guaranteed to carry a real advertisement, unlike the nullable-in-general field.
private val DiscoveredDevice.requiredAdvertisement: Advertisement
    get() = advertisement ?: error("Expected a BLE-scanned device, got the self entry")

/**
 * Owns this device's entire background sync job — started once, for the life of the process,
 * via [start] (called from [PeripheralSyncService.onCreate]), independent of whether the
 * operator is actually looking at the Mule Mode screen or even in Mule mode at all.
 *
 * Two roles live here, and only one of them is mode-gated (see [startBluetoothStateLoop]'s own
 * doc): *scanning* for nearby peers and actively pulling from them only runs while this phone's
 * own [SettingsRepository.appMode] is [AppMode.MULE] — a
 * Time/Bibs/CP phone is a pure BT source (advertising and serving GATT reads only, via
 * [PeripheralSyncService], unaffected by this class at all) rather than also actively pulling
 * from every other visible peer for no benefit, which used to mean every phone in the field
 * tripled total BLE connect volume for a typical one-Time/one-Bibs/one-Mule setup. This device's
 * own unsynced data still gets pushed straight to the server every tick regardless of mode (see
 * [pushIfNeeded]) — that path never depended on scanning or [discoveredFlow] at all.
 *
 * Earlier in this app's history, scanning/pulling ran unconditionally on every phone regardless
 * of mode, specifically so a single phone could record Time or Bibs *and* act as a Mule for
 * every other nearby device at the same time. That hybrid dual-duty capability is gone now —
 * confirmed with the app's owner as never actually used in practice (field setups always use a
 * separate dedicated phone per role), so it wasn't preserved. If that ever changes, the mode
 * gate in [startBluetoothStateLoop] is the one place to revisit — everything else here (pulling,
 * pushing, relay handling) is unaffected either way.
 *
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
    private val cpModeRepository: CpModeRepository,
    private val settingsRepository: SettingsRepository,
) {
    // A background engine that talks to arbitrary other phones over BLE regardless of what
    // screen (if any) is currently showing must never let a stray uncaught exception take
    // down the whole app — the operator could be mid-race on Time or Bibs mode. Individual
    // call sites below still catch what they can anticipate; this is the last-resort net.
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught exception in MuleSyncEngine — swallowed to avoid crashing the app", throwable)
    }
    // var, not val — stop() cancels this scope's Job outright (launching on a cancelled scope
    // fails immediately), so a later start() needs a fresh one to relaunch into. See stop()'s
    // own doc for why this needs to be restartable at all rather than a one-way shutdown.
    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    private val discoveredFlow = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    // Origins known only transitively, via some other Mule's relay manifest — kept separate
    // from discoveredFlow (rather than folded straight in) so discoveredFlow's own documented
    // guarantee that every entry carries a real Advertisement (see requiredAdvertisement above)
    // stays true; MuleModeViewModel merges this in alongside discoveredFlow/selfDevice for
    // display, the same way it already folds selfDevice in. Refreshed every
    // pullAllVisibleDevices() tick: a relaying peer actually reconnected to that tick has its
    // rows replaced with whatever it reports right now (so an origin it no longer offers, or
    // that's dropped off discoveredFlow entirely, promptly disappears), while a peer skipped
    // this tick under shouldConnect's gate keeps its previously reported rows unchanged rather
    // than them flickering out just because that peer wasn't worth reconnecting to yet — see
    // [DiscoveredDevice.relayedViaDeviceKey] and pullAllVisibleDevices' own doc.
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
    // When the currently-running scanJob actually started — see startBluetoothStateLoop's own
    // doc for why this exists: one continuous Kable scan session is left running unbounded for
    // as long as this phone stays in Mule mode with Bluetooth on, and a single real Mule session
    // was confirmed in the field (TODO.md's Sony-Mule reliability investigation, via
    // `adb shell dumpsys bluetooth_manager`) to still be running the *same* scan session 22
    // minutes in, having delivered 23,328 raw scan callbacks — every BLE advertisement in range
    // the whole time, unfiltered at the OS level (see MulePullClient.scanForDevices' own doc for
    // why it can't be filtered there instead). That timeline lines up with repeated
    // discoverServices() hangs (10s+ with zero callback) logged over the same window. 0L (never
    // started) until the first startScan() actually launches one.
    private var scanStartedAtMillis: Long = 0L

    // Caps how many GATT connects (first-sighting resolves and pullAllVisibleDevices' own
    // periodic re-checks alike) this engine has in flight at once, app-wide. Before this
    // existed, every newly-sighted peer got its own unthrottled connect the instant it was
    // seen — fine for one or two peers, but several phones sitting near each other (the
    // common case: a Bibs, a Time, and a Mule phone all at the finish line) all get sighted
    // in the same scan burst, so their connects landed on the radio simultaneously. Android
    // BLE centrals only support a handful of concurrent GATT links (chipset-dependent, often
    // ~4-7) shared with this phone's own simultaneous scanning/advertising/GATT-server roles,
    // so that stampede saturated it, connects piled up until MulePullClient's own connect
    // timeout, and devices sat at "Discovering…" indefinitely — confirmed in testing as
    // "sometimes works" with 3 phones, "never resolves" with 6.
    private val connectSemaphore = Semaphore(MAX_CONCURRENT_CONNECTS)

    @Volatile
    private var started = false

    val discoveredDevices: StateFlow<Map<String, DiscoveredDevice>> = discoveredFlow.asStateFlow()
    val relayDevices: StateFlow<Map<String, DiscoveredDevice>> = relayFlow.asStateFlow()
    val statusMessage: StateFlow<String?> = statusMessageFlow.asStateFlow()
    val autoWarning: StateFlow<String?> = autoWarningFlow.asStateFlow()
    val bluetoothWarning: StateFlow<String?> = bluetoothWarningFlow.asStateFlow()
    // Forwarded rather than duplicated — PeripheralSyncService (this device's advertising
    // side) is the one that actually records failures/successes into it, since only it drives
    // startAdvertising(); this engine has no visibility into that itself, but MuleModeViewModel
    // already sources every other Mule Mode warning from here, so it's exposed here too rather
    // than adding a second repository reference to the ViewModel just for this one field.
    val advertisingWarning: StateFlow<String?> = bluetoothStateRepository.advertisingWarning
    // Forwarded the same way advertisingWarning just above is, for the same reason — recorded by
    // PeripheralSyncService (the only thing with visibility into incoming GATT connections/acks),
    // exposed here purely so MuleModeViewModel doesn't need a second repository reference.
    val lastWebAppSeenAtMillis: StateFlow<Long?> = bluetoothStateRepository.lastWebAppSeenAtMillis
    val lastWebAppPushedAtMillis: StateFlow<Long?> = bluetoothStateRepository.lastWebAppPushedAtMillis
    // Forwarded the same way advertisingWarning/lastWebAppSeenAtMillis above are — this engine
    // is what actually records every attempt (see its own recordConnectAttempt call sites), but
    // the aggregation itself lives on bluetoothStateRepository since that's the one thing every
    // BLE-attempting part of this app (PeripheralSyncService included) already shares.
    val connectHealth: StateFlow<ConnectHealth> = bluetoothStateRepository.connectHealth
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
                    cpModeRepository.observeUnsyncedCount(raceId),
                    muleRepository.deviceName,
                ) { race, unsyncedSplits, unsyncedEntries, unsyncedCpEntries, deviceName ->
                    DiscoveredDevice(
                        deviceKey = SELF_DEVICE_KEY,
                        advertisement = null,
                        deviceId = SELF_DEVICE_KEY,
                        deviceName = deviceName.orEmpty(),
                        raceLabel = race?.label.orEmpty(),
                        // Time, Bibs, and CP are independently-scoped counts (a HistoryLineEntity
                        // row has exactly one mode), so summing them is safe here — unlike the
                        // BLE-pulled path below, there's no risk of double-counting the same
                        // line under two different reads.
                        unsyncedCount = unsyncedSplits + unsyncedEntries + unsyncedCpEntries,
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

    /** Tears down every loop [start] set up — the central-side counterpart to
     *  [PeripheralSyncService]'s own advertising/GATT-server teardown in its `onDestroy()`,
     *  called from there so both radio roles actually stop together. Without this, this
     *  engine's scan/auto-sync/Bluetooth-state loops (all launched into [engineScope], which
     *  [start] never itself cancels) kept running for the rest of the process's life even once
     *  the service hosting them was destroyed — confirmed in the field as Bluetooth staying
     *  visibly active (still scanning, still connecting out to peers) after the operator
     *  confirmed Exit from the mode picker, since that path finishes the Activity but doesn't
     *  put the whole process down. Cancels [engineScope] itself (cheaper and more thorough than
     *  hunting down every individual loop's own Job — [startAutoSyncLoop]/[startBluetoothStateLoop]
     *  don't even keep theirs around) and replaces it with a fresh one so a later [start] — the
     *  service restarting because the operator relaunched the app — has somewhere live to
     *  relaunch into; launching more coroutines on an already-cancelled scope would otherwise
     *  fail immediately. */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        engineScope.cancel()
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
        scanJob = null
    }

    // Polls rather than registering a BluetoothAdapter.ACTION_STATE_CHANGED receiver — this
    // engine already polls for the auto-sync loop on a similar cadence, so one more simple
    // loop is more consistent with the rest of this file than a second, receiver-based
    // mechanism would be. Re-tries startScan() (a no-op if it's already running) on every
    // tick, which is what actually recovers scanning once the operator turns Bluetooth back
    // on — nothing else here would otherwise notice and restart it.
    //
    // Also the source/sink role gate: only a phone actually in Mule mode scans for and
    // actively connects out to other devices at all — a Time/Bibs/CP phone stays purely a BT
    // *source* (advertising and serving GATT reads, via PeripheralSyncService, completely
    // unaffected by this — only the scanning/central role lives here) rather than also
    // running the full active-puller role for no benefit. In a full n-to-n mesh every phone
    // independently scanned, connected out to, and pulled from every other visible peer —
    // tripling total system-wide BLE connect volume for a typical 3-phone field setup (one
    // Time, one Bibs, one Mule) versus only the Mule phone actively pulling, which is most of
    // what made the connect stampede / contention issues chased earlier this session as bad
    // as they were. A phone only ever needs to actively pull if it's the one responsible for
    // relaying data onward (to other mules, a Web-Bluetooth-connected browser, or the HTTP
    // server) — a pure source has nothing to gain from also scanning. pushToServer()/
    // pushIfNeeded() (this device's own data straight to the HTTP server whenever it
    // personally has internet+login) is entirely unaffected either way — it never reads
    // discoveredFlow, so a source phone keeps pushing its own data directly exactly as before.
    private fun startBluetoothStateLoop() {
        engineScope.launch {
            while (isActive) {
                val isMule = settingsRepository.appMode.first() == AppMode.MULE
                if (muleRepository.bluetoothOff.first() || !isMule) {
                    // Either an explicit operator choice, or this phone simply isn't the
                    // active BT puller right now — tearing down the scan here (rather than
                    // leaving it running and just discarding results) is what stops this phone
                    // showing up as "still discovering" to itself and lets startScan() rebuild
                    // discoveredFlow from scratch the next time it becomes relevant again,
                    // same as re-entering Mule Mode already does. No warning either way — ease
                    // out of scanning is the intended, ordinary state for a source phone, not
                    // a problem to flag.
                    stopScan()
                    discoveredFlow.value = emptyMap()
                    bluetoothWarningFlow.value = null
                } else if (bluetoothStateRepository.isEnabled()) {
                    // A scan already running past SCAN_REFRESH_INTERVAL is deliberately torn
                    // down and immediately restarted, not left alone — see scanStartedAtMillis'
                    // own doc for the field evidence behind this (a single unbroken scan session
                    // observed 22 minutes in, 23K+ callbacks, correlated with repeated
                    // discoverServices() hangs). discoveredFlow itself is left untouched across
                    // the restart (unlike the mode-exit branch above) — every already-resolved
                    // device keeps its accumulated state; only the underlying OS/Kable scan
                    // session itself is replaced with a fresh one.
                    if (scanJob?.isActive == true && System.currentTimeMillis() - scanStartedAtMillis >= SCAN_REFRESH_INTERVAL.inWholeMilliseconds) {
                        Log.d(TAG, "restarting scan after ${SCAN_REFRESH_INTERVAL.inWholeSeconds}s to avoid one unbounded scan session for the whole Mule session")
                        stopScan()
                    }
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
        scanStartedAtMillis = System.currentTimeMillis()
        scanJob = engineScope.launch {
            try {
                muleRepository.scanForDevices().collect { advertisement ->
                    val address = advertisement.identifier
                    val decoded = muleRepository.decodeAdvertisedIdentity(advertisement)
                    // Matched by identity (including a deviceId key it's since been merged
                    // into), not by map key directly — a device rescanned under the same
                    // address must keep its accumulated state (unsyncedCount, confirmedLineNumber,
                    // reachability history, ...), never get re-added from scratch. The
                    // shortDeviceId check alongside the address check is what keeps that true
                    // across a BLE address rotation too (see matchesShortDeviceId's own doc) —
                    // address alone used to miss that case entirely, both before a device's
                    // first resolve (an endless trail of "Discovering…" ghosts, one per
                    // rotation) and after (a resolved device's rotation spawning a duplicate
                    // ghost alongside its already-green row).
                    val existingKey = discoveredFlow.value.entries.firstOrNull { (_, existing) ->
                        existing.requiredAdvertisement.identifier == address ||
                            (decoded != null && matchesShortDeviceId(existing, decoded.shortDeviceId))
                    }?.key
                    if (existingKey != null) {
                        // Already tracked — still refresh the stored advertisement itself
                        // (a fresh scan callback can carry an updated scan-response payload,
                        // e.g. this peer's lastLineNumber has since advanced) so shouldConnect
                        // below is always deciding off the latest advertised hint, not
                        // whatever this device's very first sighting happened to carry. Only
                        // the *first-ever* sighting (the else branch) triggers a real connect —
                        // deviceId/relayCount/pollIntervalMs only ever come from an actual
                        // DeviceInfo read, never the scan-response payload.
                        discoveredFlow.value = discoveredFlow.value +
                            (existingKey to discoveredFlow.value.getValue(existingKey).copy(advertisement = advertisement))
                        return@collect
                    }
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

    /** True when [existing] is the same physical phone [shortDeviceId] was just decoded from
     *  — either because [existing] is already resolved (compares against a fingerprint of its
     *  confirmed [DiscoveredDevice.deviceId], the authoritative value) or, pre-resolve, because
     *  its own last-stored advertisement happens to decode to the same fingerprint. This is
     *  what lets [startScan] recognize a phone across a BLE address rotation (Android's
     *  periodic random-address privacy feature) without a fresh GATT connect first — see
     *  [MuleGattProfile.shortDeviceId]'s own doc for why this fingerprint is stable where the
     *  address isn't, and startScan's own doc for the ghost-pileup this fixes. */
    private fun matchesShortDeviceId(existing: DiscoveredDevice, shortDeviceId: Long): Boolean {
        existing.deviceId?.let { return MuleGattProfile.shortDeviceId(it) == shortDeviceId }
        val existingDecoded = muleRepository.decodeAdvertisedIdentity(existing.requiredAdvertisement)
        return existingDecoded?.shortDeviceId == shortDeviceId
    }

    // Every phone running Mule Mode runs its own independent instance of this loop, all sharing
    // the identical, unjittered AUTO_SYNC_INTERVAL — confirmed in the field as a real risk: if
    // several operators start Mule Mode within moments of each other (a common real case right
    // before a race), their loops settle into whatever relative phase they happened to start at
    // and never drift apart on their own, since nothing here ever re-randomizes a fixed, repeated
    // delay. That means their scan/advertise/GATT radio activity can keep landing on the same
    // instant, tick after tick, rather than just occasionally — a standing, avoidable source of
    // contention on top of whatever this phone's own connectSemaphore is already managing for
    // its own connects. Re-randomizing the delay on every single iteration (not just once at
    // startup) is what actually breaks that lock: two loops that started in phase would still be
    // in phase after only one jittered wait, so this has to be recomputed every time around, same
    // reasoning FIRST_SIGHTING_JITTER already applies to a newly-discovered device's own first
    // connect, just applied continuously here instead of once.
    private fun startAutoSyncLoop() {
        engineScope.launch {
            while (isActive) {
                val jitter = Random.nextDouble(-1.0, 1.0) * AUTO_SYNC_JITTER_FRACTION
                delay(AUTO_SYNC_INTERVAL * (1.0 + jitter))
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
        // Several peers can all be first-sighted within the same scan burst (the common
        // "several phones already sitting near each other" case) — each spawns its own
        // independent launch{} in startScan(), so without this jitter they'd all queue on
        // connectSemaphore in the same instant and release in lockstep every time a permit
        // frees, rather than spreading naturally across FIRST_SIGHTING_JITTER's window.
        delay(Random.nextLong(FIRST_SIGHTING_JITTER.inWholeMilliseconds))
        val device = discoveredFlow.value[key] ?: return
        // Falls back to the raw key (a BLE address, pre-resolve) when there's no name yet —
        // see BluetoothStateRepository.recordConnectAttempt's own doc for what this identifies.
        val peerLabel = device.deviceName.ifBlank { key }
        // Shares connectSemaphore with pullAllVisibleDevices' own connects — see that
        // function's own comment at its withPermit call for why one bound has to cover both.
        // Temporary/diagnostic logging around this connect — see MulePullClient.scanForDevices'
        // own doc for the matching raw-scan-level logging; together these two spots make a
        // silently-swallowed runCatching failure (previously invisible from outside the app)
        // distinguishable from a device that never scans in at all.
        Log.d(TAG, "first-sighting connect attempt: key=$key")
        val result = connectSemaphore.withPermit {
            runCatching { muleRepository.readDeviceInfo(device.requiredAdvertisement) }
                .also { bluetoothStateRepository.recordConnectAttempt(it.isSuccess, peerLabel) }
                .onFailure { Log.w(TAG, "first-sighting connect failed: key=$key", it) }
        }
        val info = result.getOrNull()
        if (info == null) {
            markUnreachable(key, device, result.exceptionOrNull())
            return
        }
        Log.d(TAG, "first-sighting connect succeeded: key=$key deviceId=${info.deviceId} deviceName=${info.deviceName}")
        val since = muleRepository.lastPulledLineNumber(info.deviceId, info.raceLabel)
        mergeDeviceInfo(key, device, info, since)
    }

    /** "Forget" a device — purges any live entry for it from [discoveredFlow]/[relayFlow]
     *  right now (rather than waiting out [markUnreachable]'s own drop threshold, which for a
     *  device that's *never* resolved can still take up to [UNRESOLVED_DROP_THRESHOLD] — see
     *  its own doc for why that's already shorter than a resolved device's, but still not
     *  instant), and deletes its persisted [MuleRepository.knownDevices] roster entry. [key] is
     *  whichever identity [mobile.racemaster.ui.mulemode.MuleModeScreen] had on hand for that row: a resolved device's own
     *  `deviceId` (works for both a still-visible direct entry, since [mergeDeviceInfo] always
     *  keys a resolved entry by its deviceId, and a relay-only entry, matched by
     *  [DiscoveredDevice.deviceId] below rather than its composite `relay:` map key), or an
     *  unresolved ghost's raw BLE address (its only identity — [DiscoveredDevice.deviceId] is
     *  null for those). [MuleRepository.forgetKnownDevice] is always safe to call either way —
     *  see its own doc.
     *
     *  Deliberately not a permanent block/ignore-list: this only clears *current* state. A
     *  direct device is still advertising, so the next [startScan] callback re-discovers it
     *  from scratch (back to "Discovering…") rather than it staying gone — same "reset, not
     *  block" facility already established for Setup Server's "No Server" and Race Details'
     *  "Clear race". */
    fun forgetDevice(key: String) {
        discoveredFlow.value = discoveredFlow.value.filterValues { it.deviceKey != key && it.deviceId != key }
        relayFlow.value = relayFlow.value.filterValues { it.deviceId != key }
        engineScope.launch { muleRepository.forgetKnownDevice(key) }
    }

    /** Folds a freshly-read [DeviceInfo] into [discoveredFlow], keyed by the stable
     *  `deviceId` rather than [oldKey] (a BLE address) — merging into any entry already
     *  tracked under that deviceId instead of creating a duplicate, and overwriting (not
     *  accumulating — see [DiscoveredDevice]'s own doc for why) its outstanding-line count
     *  with this fresh one (info.lastLineNumber minus [lastPulledLineNumber], the delta
     *  already computed by the caller). A successful read is by definition proof of
     *  reachability, so it always clears [DiscoveredDevice.unreachable] and
     *  [DiscoveredDevice.consecutiveFailures], and bumps [DiscoveredDevice.lastReachableAtMillis]
     *  to now. Also upserts [MuleRepository.knownDevices]' persisted roster entry for it —
     *  every successful resolve is exactly the moment this device's name is actually known,
     *  regardless of how long it then stays live in [discoveredFlow] for. */
    private suspend fun mergeDeviceInfo(oldKey: String, device: DiscoveredDevice, info: DeviceInfo, lastPulledLineNumber: Long) {
        val newKey = info.deviceId
        val current = discoveredFlow.value
        val base = current[newKey] ?: device
        val outstanding = (info.lastLineNumber - lastPulledLineNumber).coerceAtLeast(0).toInt()
        val now = System.currentTimeMillis()
        val merged = base.copy(
            deviceKey = newKey,
            advertisement = device.advertisement,
            deviceId = newKey,
            deviceName = info.deviceName,
            raceLabel = info.raceLabel,
            unsyncedCount = outstanding,
            lastReachableAtMillis = now,
            consecutiveFailures = 0,
            unreachable = false,
            lastFailureReason = null,
            confirmedLineNumber = info.lastLineNumber,
            lastRealReadAtMillis = now,
        )
        discoveredFlow.value = (current - oldKey - newKey) + (newKey to merged)
        muleRepository.recordDeviceSeen(newKey, info.deviceName)
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
     *  operator stepped out of range briefly. [cause] is whatever [MulePullClient.readDeviceInfo]
     *  actually threw this time — classified via [describeConnectFailure] and stored on the
     *  surviving entry so its row can show *why*, not just that it failed (see
     *  [DiscoveredDevice.lastFailureReason]'s own doc). */
    private fun markUnreachable(key: String, device: DiscoveredDevice, cause: Throwable? = null) {
        val now = System.currentTimeMillis()
        val dropThreshold = if (device.deviceId == null) UNRESOLVED_DROP_THRESHOLD else UNREACHABLE_DROP_THRESHOLD
        if (now - device.lastReachableAtMillis >= dropThreshold.inWholeMilliseconds) {
            // Temporary/diagnostic — see refreshDeviceInfo/pullAllVisibleDevices' own matching
            // log lines. A device stuck at "Discovering…" that keeps getting re-added by a
            // fresh scan callback (see startScan's own doc on BLE address rotation) shows up
            // here as repeated drops under the *same* deviceKey only if the address itself
            // didn't rotate — a genuinely new key each time means this line alone won't catch
            // it, but the raw-scan logging in MulePullClient will.
            Log.d(TAG, "dropping (unresolved=${device.deviceId == null}): key=$key deviceName=${device.deviceName}")
            discoveredFlow.value = discoveredFlow.value - key
            return
        }
        val failures = device.consecutiveFailures + 1
        discoveredFlow.value = discoveredFlow.value + (
            key to device.copy(
                consecutiveFailures = failures,
                unreachable = failures >= UNREACHABLE_FAILURE_THRESHOLD,
                lastFailureReason = cause?.let(::describeConnectFailure) ?: device.lastFailureReason,
            )
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
            // A last-resort ceiling on top of MulePullClient's own per-operation timeouts —
            // this engine's while(isActive) { delay(...); autoPullAndPushIfArmed() } loop
            // (see startAutoSyncLoop) is one coroutine, so if this call ever failed to return
            // (any unbounded suspend anywhere in its call chain, present or future), not just
            // this tick's push but every future tick — pull and push alike — would wedge
            // forever, with nothing left to recover it. Deliberately generous: with several
            // peers queued behind connectSemaphore's small permit pool, a legitimate tick can
            // take a while without that being a bug.
            try {
                withTimeout(OVERALL_TICK_TIMEOUT) { pullAllVisibleDevices() }
            } catch (e: TimeoutCancellationException) {
                "Auto-sync timed out this cycle — will retry"
            }
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
     *  Beyond each visible peer's own race, this also walks its relay manifest (fetched via
     *  [MuleRepository.pullRelayManifest] whenever [DeviceInfo.relayCount] says there's one —
     *  see [RelayManifestEntry]'s own doc for why that's a separate pull rather than a
     *  [DeviceInfo] field) — whatever *that* peer is separately holding for other, genuinely
     *  different origin
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
     *  attempt succeeded (including "nothing to pull").
     *
     *  Deliberately does NOT do a real GATT connect+[DeviceInfo] read for every visible device
     *  on every single tick anymore — that unconditional "connect to everyone every
     *  [AUTO_SYNC_INTERVAL]" load is what this whole redesign exists to eliminate (see
     *  [shouldConnect]'s own doc). [force] (from [forceSyncNow]) bypasses that gate entirely,
     *  unconditionally re-checking every visible device right now — exactly matching this
     *  function's pre-existing behavior and [forceSyncNow]'s own "do it right now" contract. */
    private suspend fun pullAllVisibleDevices(force: Boolean = false): String? {
        var tickFailure: String? = null
        val myDeviceId = muleRepository.myDeviceId()
        // Seeded from last tick's relay rows (see the loop below and its final assembly) —
        // under the new connect-gating, most peers are skipped on most ticks, so relayFlow can
        // no longer be rebuilt purely from whatever this one tick happens to touch (that used
        // to be safe when every peer was connected every tick). A skipped peer's previously
        // reported relay rows are carried forward unchanged; only a peer actually reconnected
        // this tick has its rows replaced with what it reports right now.
        val relayRows = relayFlow.value.filterValues { it.relayedViaDeviceKey in discoveredFlow.value.keys }.toMutableMap()
        val now = System.currentTimeMillis()
        for ((key, device) in discoveredFlow.value.toList()) {
            val decoded = muleRepository.decodeAdvertisedIdentity(device.requiredAdvertisement)
            // Cheap (a local DAO query, no BLE op) — checked ahead of shouldConnect so a
            // pending confirmation can force a reconnect on its own, rather than only ever
            // getting relayed back once some other condition (most likely the 60s
            // VERIFY_INTERVAL backstop) happens to trigger one anyway. Without this, a device
            // whose advertised counter has nothing left to advance (everything it has is
            // already pulled) could sit fully synced-to-Mule but stuck at "orange" for up to a
            // full VERIFY_INTERVAL before ever finding out its data reached a real sink.
            val pendingConfirmation = device.deviceId != null &&
                muleRepository.hasSinkConfirmationToRelay(device.deviceId, device.raceLabel)
            if (!shouldConnect(
                    device, decoded, now, VERIFY_INTERVAL.inWholeMilliseconds, force,
                    pendingConfirmation, CONFIRMATION_RELAY_INTERVAL.inWholeMilliseconds,
                )
            ) {
                continue
            }
            // Bounds how many devices this phone is actively GATT-connected to at once —
            // shared with refreshDeviceInfo's own first-sighting connects below, since an
            // unbounded pile of simultaneous connects (this loop's own connects included) is
            // what saturates the radio and stalls resolution entirely once more than a
            // handful of peers are visible at once (see MAX_CONCURRENT_CONNECTS's own doc).
            connectSemaphore.withPermit {
                // This peer is being freshly checked this tick — drop whatever relay rows it
                // contributed last time so they don't linger alongside (or diverge from) what it's
                // about to report now; replaced below if it still has any to offer.
                relayRows.entries.removeAll { it.value.relayedViaDeviceKey == key }
                // Every connect attempt below is against this same physical peer (device), even
                // the ones fetching a *relayed* origin's data — see
                // BluetoothStateRepository.recordConnectAttempt's own doc for what this
                // identifies and why it matters that it's this peer, not whichever origin the
                // data happens to be attributed to.
                val peerLabel = device.deviceName.ifBlank { key }
                // Temporary/diagnostic — see refreshDeviceInfo's own matching log lines.
                Log.d(TAG, "periodic connect attempt: key=$key deviceId=${device.deviceId}")
                // device.deviceId/raceLabel (already known — this device has resolved at least
                // once before) let this same connection also deliver any sink confirmation
                // already owed to it, if there is one — see MuleRepository.readDeviceInfo's own
                // doc for why that's now the only reliable way to deliver one: a *separate*
                // reconnect just for the confirmation, moments after this same read's own
                // connect, failed 100% of the time against a real device (always right at
                // CONNECT_TIMEOUT), even though this read's own connect kept succeeding fast and
                // reliably moments earlier. A genuine sink confirmation must reach the operator
                // watching that source regardless of which specific connection carried it.
                val periodicResult = runCatching {
                    muleRepository.readDeviceInfo(
                        device.requiredAdvertisement, device.deviceId, device.raceLabel,
                        // The read itself can succeed (this device stays green/reachable) even
                        // while its piggybacked sink-confirmation ack keeps failing underneath —
                        // see MulePullClient.readDeviceInfoOnce's own doc for why that's
                        // deliberately non-fatal. Routed into this function's own tickFailure
                        // (same channel as a failed pull below, not autoWarningFlow directly —
                        // autoPullAndPushIfArmed unconditionally overwrites autoWarningFlow with
                        // this function's return value right after it returns, so setting the
                        // flow itself from in here would just get clobbered) so a still-broken
                        // confirmation relay — TODO.md's Sony-Mule report — isn't silently
                        // invisible on screen the way it was before this existed.
                        onAckFailure = { reason -> tickFailure = "Confirmation relay to $peerLabel failed: $reason" },
                    )
                }
                    .also { bluetoothStateRepository.recordConnectAttempt(it.isSuccess, peerLabel) }
                    .onFailure { Log.w(TAG, "periodic connect failed: key=$key deviceId=${device.deviceId}", it) }
                val freshInfo = periodicResult.getOrNull()
                if (freshInfo == null) {
                    markUnreachable(key, device, periodicResult.exceptionOrNull())
                    return@withPermit
                }
                val since = muleRepository.lastPulledLineNumber(freshInfo.deviceId, freshInfo.raceLabel)
                mergeDeviceInfo(key, device, freshInfo, since)
                val hasNewData = freshInfo.lastLineNumber - since > 0
                Log.d(TAG, "pull decision: key=$key deviceId=${freshInfo.deviceId} lastLineNumber=${freshInfo.lastLineNumber} since=$since hasNewData=$hasNewData")
                if (hasNewData) {
                    val result = runCatching {
                        muleRepository.pullFrom(
                            device.requiredAdvertisement,
                            freshInfo.raceLabel,
                            freshInfo.deviceId,
                            freshInfo.deviceName,
                            since,
                        )
                    }.also { bluetoothStateRepository.recordConnectAttempt(it.isSuccess, peerLabel) }
                    result.onFailure { tickFailure = "Auto-pull failed: ${it.message}" }
                    result.onSuccess {
                        // Reflects the drop in outstanding lines immediately rather than waiting for
                        // the next periodic refresh, up to AUTO_SYNC_INTERVAL later.
                        runCatching { muleRepository.readDeviceInfo(device.requiredAdvertisement) }
                            .also { bluetoothStateRepository.recordConnectAttempt(it.isSuccess, peerLabel) }
                            .getOrNull()?.let {
                                val newSince = muleRepository.lastPulledLineNumber(it.deviceId, it.raceLabel)
                                mergeDeviceInfo(key, device, it, newSince)
                            }
                    }
                }

                // A separate, chunked pull rather than something freshInfo already carries — see
                // RelayManifestEntry's own doc for why DeviceInfo only ever reports a relayCount.
                // Only bothered with when that count says there's something to fetch, so a leaf
                // Time/Bibs/CP phone (always relayCount == 0) never pays this extra round trip.
                val relayEntries = if (freshInfo.relayCount > 0) {
                    runCatching { muleRepository.pullRelayManifest(device.requiredAdvertisement) }
                        .also { bluetoothStateRepository.recordConnectAttempt(it.isSuccess, peerLabel) }
                        .getOrElse { emptyList() }
                } else {
                    emptyList()
                }
                for (entry in relevantRelayEntries(myDeviceId, relayEntries)) {
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
                        relayedViaDeviceKey = key,
                    )
                    val relayHasPendingConfirmation = muleRepository.hasSinkConfirmationToRelay(entry.originDeviceId, entry.originRaceLabel)
                    if (entry.lastLineNumber - relaySince <= 0 && !relayHasPendingConfirmation) continue
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
                    }.also { bluetoothStateRepository.recordConnectAttempt(it.isSuccess, peerLabel) }
                    relayResult.onFailure { tickFailure = "Auto-pull failed: ${it.message}" }
                    relayResult.onSuccess {
                        val newSince = muleRepository.lastPulledLineNumber(entry.originDeviceId, entry.originRaceLabel)
                        val newOutstanding = (entry.lastLineNumber - newSince).coerceAtLeast(0).toInt()
                        relayRows[relayKey] = relayRows.getValue(relayKey).copy(unsyncedCount = newOutstanding)
                    }
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
                pullAllVisibleDevices(force = true)
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
        // Centralised in MuleGattProfile so every puller on this protocol (this engine's own
        // auto-sync loop included) shares one cadence rather than each hardcoding its own copy —
        // see MuleGattProfile.RECOMMENDED_POLL_INTERVAL_MS's own doc. This is the *reported*
        // value (also what DeviceInfo.pollIntervalMs advertises to every puller) — never jittered
        // itself; see startAutoSyncLoop's own doc for why the actual delay used internally below
        // is a randomized variant of this, not this constant directly.
        private val AUTO_SYNC_INTERVAL = MuleGattProfile.RECOMMENDED_POLL_INTERVAL_MS.milliseconds

        // How much startAutoSyncLoop's own steady-state tick is randomized by, each and every
        // iteration — see that function's own doc for why. Deliberately narrower than
        // FIRST_SIGHTING_JITTER below (12.5% of a 5s interval is ~625ms either way): this fires
        // forever for the whole lifetime of every phone's own engine, not just once per newly-
        // discovered device, so a wide swing here would itself become a standing source of
        // schedule variance on top of everything else already competing for this radio. Only
        // widen this after real field evidence shows the current spread isn't enough, not
        // preemptively — this loop's timing has a documented history of causing real crashes
        // when tuned too aggressively (see MAX_CONCURRENT_CONNECTS' own doc).
        private const val AUTO_SYNC_JITTER_FRACTION = 0.125

        // The periodic backstop behind shouldConnect's version-gate: even a device whose
        // advertised counter never seems to move still gets a real GATT connect+DeviceInfo
        // read this often, so (a) a relay manifest change — never reflected in the advertised
        // counter at all, see shouldConnect's own doc — is still eventually noticed, and (b) a
        // scan-response payload this device somehow never manages to read (a flaky chipset, an
        // older peer build predating this payload) doesn't leave that peer stuck relying solely
        // on `decoded == null`'s own fail-safe forever. Worst-case end-to-end propagation for a
        // relayed origin is therefore roughly 2x this value (one interval for the relaying
        // mule's own manifest to be re-checked, one more for whatever's pulling from *that*
        // mule to notice) — an accepted latency tradeoff against the BLE stack load this whole
        // gate exists to remove.
        private val VERIFY_INTERVAL = 60.seconds

        // The interval shouldConnect uses instead of VERIFY_INTERVAL when a device is owed a
        // sink-confirmation relay — shorter, so that doesn't sit for up to a full minute, but
        // still a real interval (not "every tick") — see shouldConnect's own doc for why an
        // unconditional every-tick force was a field-confirmed regression in a multi-phone mesh.
        private val CONFIRMATION_RELAY_INTERVAL = 15.seconds
        private val BLUETOOTH_CHECK_INTERVAL = 3_000.milliseconds

        // See scanStartedAtMillis' own doc for the field evidence this responds to. Picked as a
        // first experiment, not a tuned final value — short enough that an unbounded scan session
        // (and whatever it's doing to this phone's radio/controller over time) never gets anywhere
        // near the 22-minute/23K-callback state that correlated with repeated discoverServices()
        // hangs, long enough that restarting the scan itself (a brief gap where nothing is heard)
        // isn't a significant fraction of normal operation. Revisit with real data once this
        // either clears the hangs or doesn't.
        private val SCAN_REFRESH_INTERVAL = 90.seconds

        // Dropped from 2 to 1 (fully serializing every central-role connect) as a direct test —
        // TODO.md's Sony-Mule investigation traced Kable's own internal disconnect() (see
        // Connection.kt: it waits up to disconnectTimeout, 5s, for taskScope's own in-flight
        // tasks — including a discoverServices() call — to finish before it ever calls
        // gatt.disconnect()) logging "Timed out after 5s waiting for disconnect" back to the
        // exact same phenomenon behind the original diagnosis: discoverServices() occasionally
        // getting no OS callback at all, this time surfacing at the *disconnect* end of a
        // connection's life instead of the connect end (and, when it does, skipping the clean
        // disconnect handshake for a forceful close instead — plausibly compounding whatever
        // makes the next attempt's odds worse). This phone's own concurrent scanning,
        // advertising, and GATT-server roles (serving other phones' pulls) already compete for
        // one controller even at 2; fully serializing central-role connects is the cheapest,
        // most reversible way to test whether reduced contention is what lets discoverServices()
        // complete reliably. Revisit upward only once real data shows this actually helped.
        private const val MAX_CONCURRENT_CONNECTS = 1

        // See refreshDeviceInfo's own doc for why first-sighting connects need to be spread
        // out even with connectSemaphore already bounding how many run at once.
        private val FIRST_SIGHTING_JITTER = 2.seconds

        // See autoPullAndPushIfArmed's own doc for why this ceiling exists at all.
        private val OVERALL_TICK_TIMEOUT = 90.seconds
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
 * Classifies a failed [MulePullClient.readDeviceInfo] attempt into a short, operator-facing
 * label — shown alongside the "(missed N)"/"(unreachable)" suffix on a device's own row (see
 * [DiscoveredDevice.lastFailureReason]) so a repeat field occurrence (e.g. TODO.md's "Sony phone
 * claims they are unreachable" report) is diagnosable straight from the phone, without needing
 * `adb logcat`. Distinguishes the handful of causes this app's own BLE stack actually throws
 * (see MulePullClient.connectOrEvict/readDeviceInfoOnce and Kable's own exception hierarchy) —
 * most usefully [GattStatusException]'s numeric [GattStatusException.status], which is the one
 * piece of information that could actually tell "the peripheral's response never arrived" apart
 * from "the central's own GATT stack rejected something" the next time this happens. Pulled out
 * as a pure top-level function, matching this file's own [shouldConnect]/[relevantRelayEntries]
 * precedent, so it's directly testable without any real BLE plumbing.
 *
 * [MulePhaseTimeoutException] is checked ahead of the bare [TimeoutCancellationException] case —
 * it's the phase-tagged wrapper [MulePullClient.readDeviceInfoOnce] throws for exactly this kind
 * of failure (see that class's own doc), so a device whose reads keep timing out shows *which*
 * phase (connecting vs reading, most usefully) rather than just "timeout" — confirmed live as
 * the actual next question once "timeout" alone was already field-tested (TODO.md's Sony-Mule
 * report): every attempt reported plain "timeout", never a GATT status/rejection, which alone
 * doesn't say whether the connect handshake or the read response itself is what's not
 * completing. That phase tag is what then found the actual answer live: the Sony reported
 * "timeout (acking)" specifically — connect, MTU negotiation, and the real DeviceInfo read all
 * completed, only the trailing best-effort sink-confirmation ack write was hanging, which (before
 * MulePullClient.readDeviceInfoOnce's ack loop was made best-effort) was sinking the whole
 * already-successful read and re-marking a genuinely reachable device unreachable, every single
 * reconnect for as long as anything stayed owed to it.
 */
internal fun describeConnectFailure(cause: Throwable): String = when (cause) {
    is MulePhaseTimeoutException -> "timeout (${cause.phase})"
    is TimeoutCancellationException -> "timeout"
    is GattStatusException -> "GATT error ${cause.status}"
    is NotConnectedException -> "disconnected"
    is GattRequestRejectedException -> "rejected"
    else -> cause::class.simpleName ?: "unknown error"
}

/**
 * Decides whether [device] is worth a real, expensive GATT connect+[DeviceInfo] read this
 * tick, rather than doing that unconditionally for every visible device every
 * [MuleSyncEngine.Companion.AUTO_SYNC_INTERVAL] the way this engine used to — that
 * unconditional load (a full connect+read for every peer, every tick, whether or not anything
 * had actually changed) is what the field evidence motivating this whole redesign pointed at
 * (both this app's own PeripheralSyncService and the OS Bluetooth daemon crash-restarting under
 * it). [decoded] is [device]'s scan-response identity hint (see
 * [MuleGattProfile.AdvertisedIdentity]) for *this* tick — cheap to obtain (no BLE op, pure byte
 * parsing of an already-received advertisement), and connecting is only skipped when it's both
 * present and unchanged.
 *
 * Always true (never skips) for: [force] (an explicit "do it right now" bypass, e.g.
 * [MuleSyncEngine.forceSyncNow]); a device never yet resolved ([DiscoveredDevice.deviceId] or
 * [DiscoveredDevice.confirmedLineNumber] null — nothing to compare against yet); an undecodable
 * hint (missed scan window, or a peer running an older build that predates this payload
 * entirely — fail safe by connecting, exactly this device's pre-redesign behavior); or the
 * advertised counter having advanced past what was last confirmed.
 *
 * Otherwise gated on time since the last real read, against one of two intervals:
 * [verifyIntervalMillis] normally — the periodic backstop that also covers what the advertised
 * counter alone can't (relay-manifest changes; see [MuleSyncEngine.Companion.VERIFY_INTERVAL]'s
 * own doc) — or the shorter [confirmationRelayIntervalMillis] when [pendingConfirmation] is true
 * (this device already fully pulled, but owed a sink-confirmation relay it hasn't received
 * yet). Deliberately still an *interval*, not an unconditional "always true", even for a
 * pending confirmation: in a mesh of several phones each independently pulling from (and so
 * each independently owing a relay back to) the same source, forcing a reconnect on literally
 * every tick for as long as any confirmation stayed unrelayed caused a burst of simultaneous
 * reconnects converging on the same device right when the mesh was already busiest — confirmed
 * in the field as a regression, not an improvement, over just waiting for [verifyIntervalMillis].
 *
 * Pulled out as a pure top-level function (matching [relevantRelayEntries]/[dedupRelayRows]'s
 * own precedent) so this decision is directly testable without any BLE plumbing.
 */
internal fun shouldConnect(
    device: DiscoveredDevice,
    decoded: MuleGattProfile.AdvertisedIdentity?,
    nowMillis: Long,
    verifyIntervalMillis: Long,
    force: Boolean,
    pendingConfirmation: Boolean = false,
    confirmationRelayIntervalMillis: Long = verifyIntervalMillis,
): Boolean {
    if (force) return true
    val confirmedLineNumber = device.confirmedLineNumber
    if (device.deviceId == null || confirmedLineNumber == null) return true
    if (decoded == null) return true
    if (decoded.lastLineNumber > confirmedLineNumber) return true
    // A shorter interval than verifyIntervalMillis, not an unconditional "always true" —
    // see this parameter's own doc for why: in a mesh of several phones all independently
    // holding (and independently owing a relay for) the same source's data, forcing a
    // reconnect on literally every tick for as long as any confirmation stays unrelayed
    // caused a burst of simultaneous reconnects converging on the same device right when the
    // mesh was already busiest — confirmed in the field as things getting worse, not better.
    // Still bounded well below the full backstop so a confirmation doesn't sit for up to a
    // minute either.
    val effectiveIntervalMillis = if (pendingConfirmation) confirmationRelayIntervalMillis else verifyIntervalMillis
    return nowMillis - device.lastRealReadAtMillis >= effectiveIntervalMillis
}

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

/**
 * [MuleRepository.knownDevices] rows worth folding into MuleModeScreen's Nearby Devices list as
 * stale entries (see [DiscoveredDevice.isStale]) — everything in the persisted roster except a
 * device that's already showing up live (direct or relayed), which would otherwise show twice.
 * Pulled out as a pure function for the same directly-testable reason as [dedupRelayRows].
 */
internal fun previouslySeenDevices(
    known: List<KnownDeviceEntity>,
    liveDeviceIds: Set<String>,
): List<KnownDeviceEntity> = known.filterNot { it.deviceId in liveDeviceIds }

/**
 * Merges each [PulledSourceSummary]'s own [PulledSourceSummary.lastPulledAtMillis] into the
 * matching live device row — matched by deviceId + raceLabel, the same pairing
 * [PulledSourceSummary] itself groups by, so a relay row (whose data comes from a genuinely
 * different origin than whatever peer is relaying it) still gets its own origin's pull time,
 * not the relaying peer's. Pulled out as a pure top-level function (matching
 * [previouslySeenDevices]'s own precedent) so [mobile.racemaster.ui.mulemode.MuleModeViewModel]'s
 * combine() block stays a plain wiring step. A row with no matching summary (self; nothing ever
 * pulled from it) is returned unchanged, leaving [DiscoveredDevice.lastPulledAtMillis] at its
 * default null.
 */
internal fun withLastPulledAtMillis(
    devices: List<DiscoveredDevice>,
    summaries: List<PulledSourceSummary>,
): List<DiscoveredDevice> {
    val lastPulledAtByOrigin = summaries.associate { (it.sourceDeviceId to it.sourceRaceLabel) to it.lastPulledAtMillis }
    return devices.map { device ->
        val deviceId = device.deviceId ?: return@map device
        val lastPulledAtMillis = lastPulledAtByOrigin[deviceId to device.raceLabel] ?: return@map device
        device.copy(lastPulledAtMillis = lastPulledAtMillis)
    }
}
