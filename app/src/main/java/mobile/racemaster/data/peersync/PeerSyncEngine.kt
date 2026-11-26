package mobile.racemaster.data.peersync

import android.util.Log
import com.juul.kable.Advertisement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.mule.BluetoothStateRepository
import mobile.racemaster.data.mule.MuleGattProfile
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.mule.SyncRecord
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.data.settings.toHistoryMode

/**
 * Fast same-mode peer sync: lets several phones running the *same* mode for the *same* race (e.g.
 * several Count phones covering a start pen, several Bibs/Finish phones at the finish) converge on
 * shared state fast while nearby, by pulling full history rows directly from each other over BLE
 * and merging them into [PeerSyncRepository].
 *
 * Deliberately its own standalone engine, not a part of
 * [mobile.racemaster.data.mule.MuleSyncEngine] — an earlier version of this feature was built as
 * an extension of Mule Mode's own scan/pull machinery, which meant it only ever ran while this
 * device also had Mule syncing enabled (a separate, Mule-relay-specific setting most Count/Bibs
 * stations have no reason to turn on) and quietly stopped the moment Mule's own engine stopped
 * (e.g. the operator hitting Exit). Neither is remotely what "two Count phones at the pen should
 * notice each other" needs. This engine instead:
 *
 * - Uses [MuleRepository.scanForDevices] directly for discovery — the same shared scan session
 *   Mule Mode/BeaconScanEngine already use (see that function's own doc for why it's one shared
 *   native scan, not a second independent one), but subscribed to on its own, with no dependency
 *   on [mobile.racemaster.data.mule.MuleSyncEngine]'s own discoveredFlow/connect bookkeeping.
 * - Pulls a same-group peer's rows via [MuleRepository.pullForPeerSync], never
 *   [MuleRepository.pullFrom] — that call always stages whatever it receives into this device's
 *   own relay-to-server inbox, which would wrongly turn every Count phone into a server relay for
 *   its peers' data (see [MuleRepository.pullForPeerSync]'s own doc).
 * - Is started/stopped as its own independent sibling of [mobile.racemaster.data.mule.MuleSyncEngine]
 *   from [mobile.racemaster.data.mule.PeripheralSyncService] (see that class's own `onCreate`),
 *   so it keeps running in the background for the whole process's life exactly as that engine
 *   does, without the two having any dependency on each other at all.
 *
 * This independence is also why it lives in its own `data.peersync` package rather than
 * `data.mule`: Mule Mode's own transport is due to change (TODO.md's "Mule mode tweak" — a local
 * HTTP server replacing its own BLE relay machinery), and this feature has nothing to do with that
 * change — it only needs the two phones' own GATT connection, which keeps existing regardless.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerSyncEngine(
    private val muleRepository: MuleRepository,
    private val raceRepository: RaceRepository,
    private val settingsRepository: SettingsRepository,
    private val peerGroupDetector: PeerGroupDetector,
    private val peerSyncRepository: PeerSyncRepository,
    private val bluetoothStateRepository: BluetoothStateRepository,
) {
    // Same reasoning as MuleSyncEngine's own identically-named field: a background engine that
    // talks to arbitrary other phones over BLE must never let a stray uncaught exception take
    // down the whole app.
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught exception in PeerSyncEngine — swallowed to avoid crashing the app", throwable)
    }
    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    private data class NearbyPeer(val advertisement: Advertisement, val decoded: MuleGattProfile.AdvertisedIdentity, val lastSeenAtMillis: Long)
    private data class OwnContext(val groupKey: String?, val mode: HistoryMode?)

    private val nearbyPeersFlow = MutableStateFlow<Map<String, NearbyPeer>>(emptyMap())
    private val ownContextFlow = MutableStateFlow(OwnContext(null, null))

    private val hasSameGroupPeerNearbyFlow = MutableStateFlow(false)
    val hasSameGroupPeerNearby: StateFlow<Boolean> = hasSameGroupPeerNearbyFlow.asStateFlow()

    // Same-group peers' own full device names (e.g. "swift-puffin"), for on-screen display
    // (CountModeScreen/EntryModeHeaderInfo). Sorted/distinct purely for stable, de-duplicated
    // display — nothing here depends on ordering.
    private val nearbyPeerNamesFlow = MutableStateFlow<List<String>>(emptyList())
    val nearbyPeerNames: StateFlow<List<String>> = nearbyPeerNamesFlow.asStateFlow()

    // A peer's full device name, as read from its own DeviceInfo during a GATT pull (see
    // pullFromPeerIfNeeded) — keyed by AdvertisedIdentity.shortDeviceId rather than
    // nearbyPeersFlow's own volatile BLE-address key, so it survives that address rotating (see
    // shortDeviceId's own doc) and stays matchable against whatever's currently in
    // nearbyPeersFlow. The advertised identity's own deviceName is truncated to
    // MuleGattProfile.ADVERTISED_NAME_MAX_BYTES (5 bytes) to fit the legacy 31-byte scan-response
    // budget — "swift-puffin" arrives as "swift" — so nearbyPeerNamesFlow below prefers this full
    // name once a GATT pull has actually read it, falling back to the truncated advertised one
    // only until that first successful read happens.
    private val fullDeviceNamesFlow = MutableStateFlow<Map<Long, String>>(emptyMap())

    // This device's own same-group peer connects are bounded app-wide by
    // MulePullClient.sharedConnectSemaphore now, not by anything this engine owns itself —
    // shared with MuleSyncEngine's connects too, since both ultimately contend for the same
    // physical radio. This engine used to hold its own, separate Semaphore(1) here; up to two
    // concurrent connects (one from each engine, to two different peers) could run at once as a
    // result — sharing one pool in MulePullClient itself closes that.

    // Per-peer cooldown after a failed readDeviceInfo — see pullFromPeerIfNeeded's own doc (the
    // readDeviceInfo failure branch) for the real, confirmed-in-the-field collision this exists
    // to break up: two phones' independent ~1s pull loops connecting to EACH OTHER at virtually
    // the same instant. Keyed by AdvertisedIdentity.shortDeviceId — same reasoning as
    // fullDeviceNamesFlow's own key choice, stable across this peer's advertised BLE address
    // rotating between ticks. A flat random window only has to get unlucky once in a row to
    // collide again — binary exponential backoff (consecutiveFailures doubling the window each
    // repeat failure, reset to 0 by any success) is the standard answer to exactly this kind of
    // shared-medium collision (the same idea Ethernet's own CSMA/CD backoff is built on): two
    // peers that keep colliding fan their retry times apart geometrically instead of re-drawing
    // from the same fixed range every time.
    private val peerBackoffState = mutableMapOf<Long, PeerBackoffState>()

    private data class PeerBackoffState(val backoffUntilMillis: Long, val consecutiveFailures: Int)

    @Volatile
    private var started = false

    /** Idempotent — starts this engine's discovery/pull loops exactly once for the life of the
     *  process, however many times this is called. Called from
     *  [mobile.racemaster.data.mule.PeripheralSyncService.onCreate], alongside (not nested inside)
     *  that same call's own `muleSyncEngine.start()` — see this class's own doc for why these are
     *  deliberately independent siblings. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        startDiscoveryLoop()
        startDetectionLoop()
        startPullLoop()
    }

    /** Tears down every loop [start] set up — see [mobile.racemaster.data.mule.MuleSyncEngine.stop]'s
     *  own doc for why this needs to be restartable (a later [start] relaunches into a fresh
     *  scope) rather than a one-way shutdown. */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        engineScope.cancel()
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
        discoveryScanJob = null
        nearbyPeersFlow.value = emptyMap()
        hasSameGroupPeerNearbyFlow.value = false
        nearbyPeerNamesFlow.value = emptyList()
        fullDeviceNamesFlow.value = emptyMap()
        peerBackoffState.clear()
    }

    // The actual discovery scan subscription — see startDiscoveryLoop's own doc for what it
    // does. Split out into its own cancellable job (rather than a bare unconditional loop
    // launched straight from startDiscoveryLoop, as this used to be) so startBluetoothOffGateLoop
    // below can start/stop it in response to SettingsRepository.bluetoothOff, same as
    // MuleSyncEngine.startScan/stopScan already do for that engine's own scan.
    private var discoveryScanJob: Job? = null

    private fun startDiscoveryScan() {
        if (discoveryScanJob?.isActive == true) return
        discoveryScanJob = engineScope.launch {
            while (isActive) {
                try {
                    muleRepository.scanForDevices().collect { advertisement ->
                        val decoded = muleRepository.decodeAdvertisedIdentity(advertisement) ?: return@collect
                        val key = advertisement.identifier
                        nearbyPeersFlow.update { it + (key to NearbyPeer(advertisement, decoded, System.currentTimeMillis())) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "peer-sync discovery scan failed — retrying", e)
                }
                delay(DISCOVERY_RETRY_DELAY)
            }
        }
    }

    private fun stopDiscoveryScan() {
        discoveryScanJob?.cancel()
        discoveryScanJob = null
    }

    // Discovery — a decoded advertised identity from any nearby RaceMaster phone (filtered to
    // MuleGattProfile.SERVICE_UUID by scanForDevices itself), tracked purely from scan callbacks,
    // no GATT connect involved. Retries on failure rather than giving up for the rest of this
    // engine's life, unlike collectAdvertisements-style one-shot consumers elsewhere in this app
    // that are fine just stopping once their own short-lived ViewModel goes away — this engine is
    // meant to run for the whole process's life.
    private fun startDiscoveryLoop() {
        startBluetoothOffGateLoop()
        // A peer that's gone out of range (or stopped advertising a race/mode) must eventually
        // drop out of nearbyPeersFlow on its own — nothing else here ever removes an entry.
        engineScope.launch {
            while (isActive) {
                delay(PRUNE_INTERVAL)
                val cutoff = System.currentTimeMillis() - PEER_PRESENCE_TIMEOUT.inWholeMilliseconds
                nearbyPeersFlow.update { peers -> peers.filterValues { it.lastSeenAtMillis >= cutoff } }
            }
        }
    }

    // SettingsRepository.bluetoothOff's own doc already states its intended contract: "no
    // scanning, no advertising, not answerable by any other Mule over BLE" — e.g. so
    // BeaconProgrammerViewModel can set this true while programming a beacon and have every
    // other BLE actor in the app genuinely go quiet while it does. PeripheralSyncService and
    // MuleSyncEngine already honored it; this engine didn't check it at all until now, so
    // setting it true only ever stopped two of the app's three/four BLE actors — this closes
    // that gap by gating this engine's own discovery scan subscription on the exact same flag,
    // rather than introducing a second, parallel kill switch. Stopping the scan subscription
    // (not just discarding its results) is what actually lets MulePullClient's shared
    // sharedAdvertisements flow tear down the real native scan session once nothing is left
    // subscribed to it (see that flow's own WhileSubscribed doc) — simply clearing
    // nearbyPeersFlow without unsubscribing would leave the radio listening regardless.
    // startPullLoop needs no separate change for this: once nearbyPeersFlow is cleared below,
    // its own existing `if (peers.isEmpty()) continue` already guarantees zero connects.
    //
    // Also gates on bluetoothStateRepository.isEnabled() (the OS radio itself, not this app's
    // own bluetoothOff toggle) — confirmed in the field as a real crash, not just a missed
    // scan: MulePullClient.scanForDevices' underlying Kable scan throws
    // UnmetRequirementException synchronously on first subscription if the OS radio is off
    // (e.g. a fresh phone whose Bluetooth has never been turned on), and that throw happens
    // inside MulePullClient's own scanScope — a different coroutine scope than this engine's,
    // so neither this loop's surrounding try/catch nor engineScope's own exceptionHandler ever
    // sees it; it reaches scanScope with no handler installed and takes the whole process down.
    // MuleSyncEngine.startScan already guarded its own subscription the same way for the same
    // reason — this engine and BeaconScanEngine were the two gaps that let a first-ever
    // subscription happen while the radio was genuinely off.
    private fun startBluetoothOffGateLoop() {
        engineScope.launch {
            while (isActive) {
                if (settingsRepository.bluetoothOff.first() || !bluetoothStateRepository.isEnabled()) {
                    stopDiscoveryScan()
                    nearbyPeersFlow.value = emptyMap()
                    hasSameGroupPeerNearbyFlow.value = false
                    nearbyPeerNamesFlow.value = emptyList()
                    fullDeviceNamesFlow.value = emptyMap()
                } else {
                    startDiscoveryScan()
                }
                delay(BLUETOOTH_OFF_CHECK_INTERVAL)
            }
        }
    }

    // Recomputes hasSameGroupPeerNearbyFlow and this device's own LOW_LATENCY scan switch every
    // time either this device's own convergence group changes (a race/mode switch, or the
    // operator's own kill switch — SettingsRepository.peerSyncOff) or the nearby-peer map changes
    // (a new sighting, a refreshed advertisement, or one aging out via the pruning loop above).
    private fun startDetectionLoop() {
        engineScope.launch {
            combine(computeOwnContextFlow(), nearbyPeersFlow, fullDeviceNamesFlow) { context, peers, fullNames ->
                Triple(context, peers, fullNames)
            }.collect { (context, peers, fullNames) ->
                    ownContextFlow.value = context
                    val decodedByKey = peers.mapValues { (_, peer) -> peer.decoded }
                    val matchingKeys = peerGroupDetector.sameGroupPeerKeys(context.groupKey, decodedByKey)
                    val nearby = matchingKeys.isNotEmpty()
                    if (hasSameGroupPeerNearbyFlow.value != nearby) {
                        Log.i(TAG, "same-group peer nearby: $nearby (ownGroupKey=${context.groupKey})")
                        hasSameGroupPeerNearbyFlow.value = nearby
                        muleRepository.setLowLatencyScanning(nearby)
                    }
                    nearbyPeerNamesFlow.value = matchingKeys
                        .mapNotNull { key ->
                            val decoded = peers[key]?.decoded ?: return@mapNotNull null
                            (fullNames[decoded.shortDeviceId] ?: decoded.deviceName).takeIf { it.isNotBlank() }
                        }
                        .distinct()
                        .sorted()
                }
        }
    }

    // This device's own current convergence group (key + mode) — see PeerGroupDetector.ownGroupKey's
    // own doc for why that function takes plain values rather than this engine's own
    // raceRepository/settingsRepository directly. Both null while there's no active race, no mode
    // chosen (nothing to converge on yet), or SettingsRepository.peerSyncOff is on — this is the
    // feature's one kill switch, gating every downstream effect (detection, the LOW_LATENCY
    // switch, and the pull loop below) from this one place.
    private fun computeOwnContextFlow(): Flow<OwnContext> =
        combine(
            settingsRepository.activeRaceId,
            settingsRepository.appMode,
            settingsRepository.peerSyncOff,
        ) { raceId, mode, peerSyncOff -> Triple(raceId, mode, peerSyncOff) }
            .distinctUntilChanged()
            .flatMapLatest { (raceId, mode, peerSyncOff) ->
                if (raceId == null || mode == null || peerSyncOff) {
                    flowOf(OwnContext(null, null))
                } else {
                    val historyMode = mode.toHistoryMode()
                    raceRepository.observeRace(raceId).map { race ->
                        OwnContext(race?.let { peerGroupDetector.ownGroupKey(it.label, it.location, historyMode) }, historyMode)
                    }
                }
            }

    // The actual convergence: for every currently-nearby same-group peer, connects and pulls
    // whatever rows it holds past this device's own last-merged cursor for it. Ticks fast
    // (PULL_INTERVAL) but is cheap to skip (no radio op) on a tick with no same-group peer nearby
    // — mirrors MuleSyncEngine's own FAST_SYNC_INTERVAL reasoning, just scoped to this engine's own
    // much narrower job rather than shared with Mule's general-purpose relay tick.
    //
    // The per-tick delay below adds a small random PULL_JITTER on top of the fixed PULL_INTERVAL.
    // Added while chasing a field-confirmed status=133 (Android's generic GATT error) pattern
    // between two real phones, on the theory that two devices' independent ~1s loops could stay
    // phase-locked and keep colliding by connecting to each other at the same moment. Kept
    // (harmless either way — it's a single small jitter added once per *tick*, not per
    // connection, so unlike the reconnect-cooldown idea PULL_JITTER_RANGE_MS's own doc already
    // flags as tried-and-reverted, it can't compound across the several connections one tick can
    // already make), but it turned out NOT to be the real cause of that status=133 pattern: see
    // PeripheralSyncService.refreshAdvertisedIdentity's own doc for the actual root cause
    // (that device's own advertised BLE address getting rotated out from under this engine by an
    // unrelated feature) and the fix that addresses it directly.
    private fun startPullLoop() {
        engineScope.launch {
            while (isActive) {
                delay(PULL_INTERVAL + Random.nextLong(PULL_JITTER_RANGE_MS).milliseconds)
                val context = ownContextFlow.value
                val groupKey = context.groupKey
                val mode = context.mode
                if (groupKey == null || mode == null) continue
                val peers = nearbyPeersFlow.value.values.filter { peerGroupDetector.groupKeyOf(it.decoded) == groupKey }
                if (peers.isEmpty()) continue
                // One budget PER PEER, not one shared across the whole loop — see
                // PULL_PEER_TIMEOUT's own doc for the real, confirmed-in-the-field bug this fixes:
                // a single shared withTimeout here used to let an early peer's own retries eat
                // into (and eventually truncate, mid-connect) a later peer's turn. Each peer is
                // also its own runCatching, not one wrapping the whole loop, so one peer timing
                // out still lets every other nearby peer get its own attempt this tick.
                for (peer in peers) {
                    // Skip a peer still in its post-collision cooldown — see peerBackoffState's
                    // own doc and pullFromPeerIfNeeded's readDeviceInfo failure branch for why
                    // this exists and where it's set.
                    if ((peerBackoffState[peer.decoded.shortDeviceId]?.backoffUntilMillis ?: 0L) > System.currentTimeMillis()) continue
                    runCatching {
                        withTimeout(PULL_PEER_TIMEOUT) { pullFromPeerIfNeeded(peer, groupKey, mode) }
                    }.onFailure { Log.w(TAG, "peer-sync pull tick failed: shortDeviceId=${peer.decoded.shortDeviceId}", it) }
                }
            }
        }
    }

    private suspend fun pullFromPeerIfNeeded(peer: NearbyPeer, groupKey: String, mode: HistoryMode) {
        val info = runCatching { muleRepository.readDeviceInfo(peer.advertisement) }
            .onFailure { e ->
                Log.w(TAG, "peer-sync readDeviceInfo failed", e)
                // Confirmed in the field, 1:1 against real two/three-phone sessions: Android's
                // own BLE stack logs (gatt_api.cc) show every single status=133 central-connect
                // failure this engine saw lining up, to the millisecond, with that same remote
                // address simultaneously landing on THIS device's own GATT server
                // (onServerConnectionState status=0) — i.e. that peer's own pull loop was
                // connecting to us at the exact same instant we were connecting to it. A
                // controller can't establish both directions of a link between the same two
                // devices at once; one side wins, the other gets this failure. With every
                // engine's own pull loop running on an uncoordinated ~1s+jitter cadence and no
                // visibility into the other device's schedule, two phones polling each other
                // this fast collide often — more so with a third phone in the mesh, since there
                // are now three such pairs instead of one. A randomized cooldown, applied
                // independently by each side of a colliding pair, is what actually breaks the
                // lockstep: it doesn't need clock sync between phones (a same-instant slot
                // assignment would), just makes it unlikely both sides retry at a close enough
                // time to collide again right away. Doubles with each consecutive failure against
                // this same peer (see PeerBackoffState's own doc) rather than re-drawing from the
                // same fixed window every time, so a pair that's unlucky enough to collide more
                // than once fans its retries apart geometrically instead of staying just as likely
                // to collide a third time as it was to collide a second.
                val priorFailures = peerBackoffState[peer.decoded.shortDeviceId]?.consecutiveFailures ?: 0
                val failures = (priorFailures + 1).coerceAtMost(MAX_BACKOFF_DOUBLINGS)
                val rangeMs = (COLLISION_BACKOFF_BASE_MS shl failures).coerceAtMost(COLLISION_BACKOFF_MAX_MS)
                peerBackoffState[peer.decoded.shortDeviceId] =
                    PeerBackoffState(System.currentTimeMillis() + Random.nextLong(rangeMs), failures)
            }
            .getOrNull() ?: return
        // A successful connect resets this peer's backoff — exponential backoff only makes sense
        // relative to a *run* of consecutive failures; carrying a doubled window over into an
        // otherwise-healthy link would just add needless latency to every future pull.
        peerBackoffState.remove(peer.decoded.shortDeviceId)
        // Captures the peer's own full device name regardless of whether there's anything new to
        // pull below — see fullDeviceNamesFlow's own doc for why display prefers this over the
        // advertised identity's truncated one.
        if (info.deviceName.isNotBlank()) {
            fullDeviceNamesFlow.update { it + (peer.decoded.shortDeviceId to info.deviceName) }
        }
        // A single lineNumber-cursor delta pull, same mechanism for every mode — in steady state
        // (the overwhelmingly common case) a peer's own backlog against this device is just
        // whatever it recorded since the last tick, a handful of records at most, so this loop
        // runs its body once and stops. PEER_SYNC_PAGE_SIZE/PEER_SYNC_MAX_PAGES_PER_TICK only
        // matter for a genuinely large backlog (first contact, or a peer rejoining after a long
        // gap): confirmed in the field that one unbounded request for a large backlog is
        // unreliable (a single BLE connection surviving enough chunked notifications to deliver
        // it all at once kept failing with NotConnectedException against a real multi-phone
        // session), while small bounded pages reliably land. The cursor is re-read fresh every
        // iteration (not a locally-tracked value) so this always resumes from whatever a
        // just-landed page durably committed — no separate progress-tracking state needed, and a
        // page that fails mid-loop leaves exactly what already landed, nothing more.
        //
        // Count Mode alone pulls through PullRequest.countStatusOnly, against its own
        // lastMergedCountStatusLineNumber/DeviceInfo.lastCountStatusLineNumber cursor pair,
        // instead of the general lastMergedLineNumber/DeviceInfo.lastLineNumber one every other
        // mode (today, just Bibs) still uses — see countStatusOnly's own doc for why: confirmed
        // live that the general path transfers every routine Ping/etc. line alongside the real
        // seen/ejected events, and a plain MAX(lineNumber) cursor with no action filter can get
        // stuck past a real, still-unmerged COUNT_EJECTED row the moment any later, irrelevant
        // row's lineNumber gets merged first.
        val countStatusOnly = mode == HistoryMode.COUNT
        var pagesThisTick = 0
        while (pagesThisTick < PEER_SYNC_MAX_PAGES_PER_TICK) {
            val since = if (countStatusOnly) {
                peerSyncRepository.lastMergedCountStatusLineNumber(info.deviceId)
            } else {
                peerSyncRepository.lastMergedLineNumber(info.deviceId)
            }
            val targetLineNumber = if (countStatusOnly) info.lastCountStatusLineNumber else info.lastLineNumber
            if (since >= targetLineNumber) break
            var page: List<SyncRecord>? = null
            val succeeded = runCatching {
                muleRepository.pullForPeerSync(peer.advertisement, since, maxRecords = PEER_SYNC_PAGE_SIZE, countStatusOnly = countStatusOnly) { records -> page = records }
            }.onFailure { Log.w(TAG, "peer-sync pull failed: deviceId=${info.deviceId}, since=$since", it) }.isSuccess
            if (!succeeded) break
            val records = page.orEmpty()
            if (records.isNotEmpty()) {
                peerSyncRepository.mergeRecords(groupKey, mode, info.deviceId, info.deviceName, records)
            }
            pagesThisTick++
            if (records.size < PEER_SYNC_PAGE_SIZE) break
        }
    }

    companion object {
        private const val TAG = "PeerSyncEngine"
        private val DISCOVERY_RETRY_DELAY = 10.seconds
        // Matches MuleSyncEngine's own BLUETOOTH_CHECK_INTERVAL — see startBluetoothOffGateLoop's
        // own doc for what this polls.
        private val BLUETOOTH_OFF_CHECK_INTERVAL = 3_000.milliseconds
        private val PRUNE_INTERVAL = 5.seconds
        // A peer not re-sighted within this long is dropped from the nearby set — generous
        // relative to Mule's own advertise interval so a single missed scan window doesn't
        // flicker a genuinely-still-nearby peer in and out.
        private val PEER_PRESENCE_TIMEOUT = 15.seconds
        private val PULL_INTERVAL = 1.seconds
        // See startPullLoop's own doc. Upper bound (exclusive) for the random per-tick jitter
        // added on top of PULL_INTERVAL, in milliseconds — comparable in size to PULL_INTERVAL
        // itself, generous enough to actually desynchronize two colliding devices' phases within
        // a handful of ticks, nowhere near large enough to risk the kind of compounding the
        // reverted reconnect-cooldown idea hit (that one was 10 SECONDS, per connection).
        private const val PULL_JITTER_RANGE_MS = 750L

        // pullFromPeerIfNeeded's own bounded-page catch-up loop — see its own doc. Small enough
        // that one page's own chunked BLE transfer reliably survives a single connection even
        // under field-observed flakiness, same reasoning MuleGattProfile.PullRequest.maxRecords's
        // own doc gives.
        private const val PEER_SYNC_PAGE_SIZE = 25

        // Caps how many pages pullFromPeerIfNeeded will fetch for one peer within a single
        // PULL_PEER_TIMEOUT-bounded tick — a large backlog (first contact, or a peer rejoining
        // after a long gap) catches up over several ticks instead of risking this one tick's own
        // connect+pull+RECONNECT_SETTLE_DELAY overhead, repeated per page, running past
        // PULL_PEER_TIMEOUT. Each already-landed page is durably committed via mergeRecords before
        // the next one starts, so stopping early here only means "continue next tick", never lost
        // progress.
        private const val PEER_SYNC_MAX_PAGES_PER_TICK = 3

        // peerBackoffState's own cooldown window after a readDeviceInfo failure — see that
        // field's own doc and pullFromPeerIfNeeded's readDeviceInfo failure branch for the
        // simultaneous-mutual-connect collision this exists to break up. Deliberately much wider
        // than PULL_JITTER_RANGE_MS above (which only desynchronizes *steady-state* phase, not a
        // just-collided pair) and randomized independently per device rather than derived from
        // wall-clock time, so it needs no clock synchronization between phones to work: each side
        // of a colliding pair draws its own random cooldown, so the odds both land close enough
        // to collide again right away drop fast with range size, without betting on both phones'
        // clocks agreeing on what "the same instant" even means. BASE is the window after the
        // *first* failure (consecutiveFailures=1 -> shl 1 -> 2x base); MAX_BACKOFF_DOUBLINGS
        // caps how many times it can double (at 6, the window tops out at base*64=160s, already
        // well past COLLISION_BACKOFF_MAX_MS below, so that cap is really what bounds it in
        // practice — the doubling cap just stops the left-shift itself from growing unbounded).
        private const val COLLISION_BACKOFF_BASE_MS = 2_500L
        private const val COLLISION_BACKOFF_MAX_MS = 30_000L
        private const val MAX_BACKOFF_DOUBLINGS = 6

        // Confirmed in the field as a real, previously-misdiagnosed-as-hardware-flakiness bug:
        // this used to be PULL_TICK_TIMEOUT (20s), wrapped around the *entire* for-loop over every
        // nearby peer in one shared withTimeout — fine with exactly one nearby peer (which is all
        // two-phone testing ever exercised), but readDeviceInfo's own documented worst case alone
        // (MulePullClient.READ_DEVICE_INFO_ATTEMPTS=3 retries, each up to CONNECT_TIMEOUT=10s,
        // plus its own settle delays and inter-attempt READ_DEVICE_INFO_RETRY_DELAY) already runs
        // to ~30+ seconds for a SINGLE struggling peer — more than the old budget by itself. With
        // a second nearby peer (this app's first real 3-phone field test), the first peer's own
        // retries routinely ate the entire shared 20s, and whatever connect was in flight for
        // whichever peer the clock ran out on got cancelled mid-handshake by withTimeout —
        // confirmed directly against swift-puffin's own raw Bluetooth stack log
        // (gatt_api.cc's GATT_CancelConnect firing within milliseconds to ~2s of a fresh
        // GATT_Connect/allocated gatt_if, far too fast to be a real link-layer failure), which
        // then surfaced several layers up as NotConnectedException/TimeoutCancellationException
        // and read as BLE hardware flakiness. Sized the same generous way
        // MuleSyncEngine.OVERALL_TICK_TIMEOUT (90s) already is for one device's own full
        // connect+retry+pull sequence: readDeviceInfo's own ~30s worst case, plus the ordinary
        // delta pull's or Count Mode's snapshot pull's own pull() call (MulePullClient.
        // PULL_TIMEOUT=15s) — comfortably covers both for one peer without truncating a
        // connection that's still making real progress.
        private val PULL_PEER_TIMEOUT = 60.seconds
    }
}
