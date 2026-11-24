package mobile.racemaster.data.peersync

import android.util.Log
import com.juul.kable.Advertisement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.mule.MuleGattProfile
import mobile.racemaster.data.mule.MuleRepository
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

    // Caps how many of this device's own same-group peer connects run at once — same reasoning as
    // MuleSyncEngine's own connectSemaphore, just scoped to this engine's own, much smaller job
    // (typically 1-2 same-group peers, never the whole visible device list).
    private val connectSemaphore = Semaphore(MAX_CONCURRENT_PEER_CONNECTS)

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
        nearbyPeersFlow.value = emptyMap()
        hasSameGroupPeerNearbyFlow.value = false
        nearbyPeerNamesFlow.value = emptyList()
        fullDeviceNamesFlow.value = emptyMap()
    }

    // Discovery — a decoded advertised identity from any nearby RaceMaster phone (filtered to
    // MuleGattProfile.SERVICE_UUID by scanForDevices itself), tracked purely from scan callbacks,
    // no GATT connect involved. Retries on failure (e.g. Bluetooth off) rather than giving up for
    // the rest of this engine's life, unlike collectAdvertisements-style one-shot consumers
    // elsewhere in this app that are fine just stopping once their own short-lived ViewModel goes
    // away — this engine is meant to run for the whole process's life.
    private fun startDiscoveryLoop() {
        engineScope.launch {
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
    private fun startPullLoop() {
        engineScope.launch {
            while (isActive) {
                delay(PULL_INTERVAL)
                val context = ownContextFlow.value
                val groupKey = context.groupKey
                val mode = context.mode
                if (groupKey == null || mode == null) continue
                val peers = nearbyPeersFlow.value.values.filter { peerGroupDetector.groupKeyOf(it.decoded) == groupKey }
                if (peers.isEmpty()) continue
                runCatching {
                    withTimeout(PULL_TICK_TIMEOUT) {
                        for (peer in peers) {
                            connectSemaphore.withPermit { pullFromPeerIfNeeded(peer, groupKey, mode) }
                        }
                    }
                }.onFailure { Log.w(TAG, "peer-sync pull tick failed", it) }
            }
        }
    }

    private suspend fun pullFromPeerIfNeeded(peer: NearbyPeer, groupKey: String, mode: HistoryMode) {
        val info = runCatching { muleRepository.readDeviceInfo(peer.advertisement) }
            .onFailure { Log.w(TAG, "peer-sync readDeviceInfo failed", it) }
            .getOrNull() ?: return
        // Captures the peer's own full device name regardless of whether there's anything new to
        // pull below — see fullDeviceNamesFlow's own doc for why display prefers this over the
        // advertised identity's truncated one.
        if (info.deviceName.isNotBlank()) {
            fullDeviceNamesFlow.update { it + (peer.decoded.shortDeviceId to info.deviceName) }
        }
        val since = peerSyncRepository.lastMergedLineNumber(info.deviceId)
        if (info.lastLineNumber <= since) return
        runCatching {
            muleRepository.pullForPeerSync(peer.advertisement, since) { records ->
                peerSyncRepository.mergeRecords(groupKey, mode, info.deviceId, info.deviceName, records)
            }
        }.onFailure { Log.w(TAG, "peer-sync pull failed: deviceId=${info.deviceId}", it) }
    }

    companion object {
        private const val TAG = "PeerSyncEngine"
        private const val MAX_CONCURRENT_PEER_CONNECTS = 1
        private val DISCOVERY_RETRY_DELAY = 10.seconds
        private val PRUNE_INTERVAL = 5.seconds
        // A peer not re-sighted within this long is dropped from the nearby set — generous
        // relative to Mule's own advertise interval so a single missed scan window doesn't
        // flicker a genuinely-still-nearby peer in and out.
        private val PEER_PRESENCE_TIMEOUT = 15.seconds
        private val PULL_INTERVAL = 1.seconds
        private val PULL_TICK_TIMEOUT = 20.seconds
    }
}
