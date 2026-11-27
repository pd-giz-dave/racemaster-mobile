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
import mobile.racemaster.data.mule.BleConnectionLog
import mobile.racemaster.data.mule.BluetoothStateRepository
import mobile.racemaster.data.mule.MuleGattProfile
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.mule.SyncRecord
import mobile.racemaster.data.mule.toSyncRecord
import mobile.racemaster.data.repository.CountModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.data.settings.toHistoryMode

/**
 * Fast same-mode peer sync: lets several phones running the *same* mode for the *same* race (e.g.
 * several Count phones covering a start pen, several Bibs/Finish phones at the finish) converge on
 * shared state fast while nearby. Restricted, mesh-wide, to exactly one bidirectional pair at a
 * time (TODO.md: "restrict peers to 2... one as central one as peripheral... the rest not in the
 * loop") — never a per-device fan-out: with 2+ same-group peers in range of each other, every one
 * of them shows a picker (see [peerChoices]/[setPeerSelected]), and only once two of them have
 * explicitly, mutually picked each other (see [resolvePairingOutcome]) does that one pair start
 * syncing; whichever other phones aren't part of it are told so (see [excludedNotice]) rather than
 * silently sitting idle. For the one confirmed pair, the two devices deterministically negotiate
 * which one dials (see [ownShortDeviceId]'s own call site in [startPullLoop]) so there is ever
 * only one BLE connection for it, carrying a bidirectional, ack-less (sequence-numbered,
 * gap-detected-and-resent rather than redone-from-scratch — see [MuleGattProfile.PeerHello]/
 * [MuleGattProfile.PeerSince]/[MuleGattProfile.ResendRequest]) exchange of both sides' new history
 * rows, merged into [PeerSyncRepository] on whichever side receives them.
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
 * - Exchanges a selected peer's rows via [MuleRepository.exchangePeerData], never
 *   [MuleRepository.pullFrom]/[MuleRepository.pull] — that pair always stages whatever it
 *   receives into this device's own relay-to-server inbox, which would wrongly turn every Count
 *   phone into a server relay for its peers' data (see [MuleRepository.exchangePeerData]'s own
 *   doc).
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
    private val bleConnectionLog: BleConnectionLog,
    // Only needed for the Part 3 push direction's own countStatusOnly query (mirrors
    // PeripheralSyncService.computeCountStatusEventsPayload's identical use) — every other
    // dependency here predates that and is unchanged.
    private val countModeRepository: CountModeRepository,
) {
    // Same reasoning as MuleSyncEngine's own identically-named field: a background engine that
    // talks to arbitrary other phones over BLE must never let a stray uncaught exception take
    // down the whole app.
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught exception in PeerSyncEngine — swallowed to avoid crashing the app", throwable)
    }
    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    private data class NearbyPeer(val advertisement: Advertisement, val decoded: MuleGattProfile.AdvertisedIdentity, val lastSeenAtMillis: Long)

    // raceId/raceStartedAtMillis are fetched once here (reusing computeOwnContextFlow's own
    // observeRace call) rather than separately per peer per tick in pullFromPeerIfNeeded's own
    // Part 3 push-direction query — both are needed there (raceId to query
    // raceRepository/countModeRepository against, raceStartedAtMillis for toSyncRecord).
    private data class OwnContext(val raceId: Long?, val groupKey: String?, val mode: HistoryMode?, val raceStartedAtMillis: Long?)

    private val nearbyPeersFlow = MutableStateFlow<Map<String, NearbyPeer>>(emptyMap())
    private val ownContextFlow = MutableStateFlow(OwnContext(null, null, null, null))

    private val hasSameGroupPeerNearbyFlow = MutableStateFlow(false)
    val hasSameGroupPeerNearby: StateFlow<Boolean> = hasSameGroupPeerNearbyFlow.asStateFlow()

    // Same-group peers' own full device names (e.g. "swift-puffin"), for on-screen display
    // (CountModeScreen/EntryModeHeaderInfo). Sorted/distinct purely for stable, de-duplicated
    // display — nothing here depends on ordering.
    private val nearbyPeerNamesFlow = MutableStateFlow<List<String>>(emptyList())
    val nearbyPeerNames: StateFlow<List<String>> = nearbyPeerNamesFlow.asStateFlow()

    /** One nearby same-group peer, as offered to the operator whenever more than one of them is
     *  nearby at once and no confirmed pair has settled yet — see [peerChoices]'s own doc. */
    data class SelectablePeer(val shortDeviceId: Long, val name: String, val isSelected: Boolean)

    // This device's own explicit (or, with exactly one nearby, auto-) pick — see
    // setPeerSelected/recomputeOwnPick. Only this device's HALF of the handshake: the actual
    // synced-with partner additionally requires that peer to report the identical pick back (see
    // resolvePairingOutcome) — TODO.md's "restrict peers to 2"/"one as central one as
    // peripheral... the rest not in the loop" is a MESH-WIDE constraint (exactly one bidirectional
    // pair may exist among any set of mutually-visible same-group phones), not a per-device
    // fan-out cap, so one side's own pick alone is never enough to start syncing. Keyed by
    // AdvertisedIdentity.shortDeviceId — same reasoning as fullDeviceNamesFlow/peerBackoffState's
    // own key choice, stable across a peer's advertised BLE address rotating.
    private val ownPickFlow = MutableStateFlow<Long?>(null)

    // This device's own current pick, read by PeripheralSyncService when building its own
    // DeviceInfo response (see MuleGattProfile.DeviceInfo.peerPickedShortDeviceId's own doc) —
    // the mechanism that lets every other nearby peer learn this device's pick via a DeviceInfo
    // read it already performs for other reasons, with no dedicated characteristic or extra
    // connect of its own needed.
    val ownPickedPeerId: Long? get() = ownPickFlow.value

    // Every nearby same-group peer's own last-reported pick (from its own
    // DeviceInfo.peerPickedShortDeviceId), used to resolve whether a confirmed, MUTUAL pair
    // exists — see resolvePairingOutcome. A null value means that peer reported no pick (or
    // hasn't been read yet). Refreshed by startPairingDiscoveryLoop while undecided, and — at
    // zero extra connect cost — by pullFromPeerIfNeeded's own regular read once this device is
    // actually paired with whoever it's tracking here.
    private val peerPicksFlow = MutableStateFlow<Map<Long, Long?>>(emptyMap())

    // The two-sided, confirmed result of the above — see PairingOutcome's own doc.
    private val pairingOutcomeFlow = MutableStateFlow<PairingOutcome>(PairingOutcome.Undecided)

    // This device's own confirmed sync partner, once resolved — the only thing startPullLoop
    // actually acts on (never the raw, one-sided ownPickFlow above, which the other side hasn't
    // necessarily reciprocated yet).
    private val confirmedPartnerIdFlow = MutableStateFlow<Long?>(null)

    // The subset of nearbyPeerNames this device is actually syncing with (at most one, now that
    // a pair is mesh-wide rather than a per-device fan-out) — what every mode screen's
    // "Nearby: ..." line displays in place of the raw nearbyPeerNames, so a merely-visible-but-
    // not-yet-confirmed peer isn't shown as though it were already converging.
    private val selectedPeerNamesFlow = MutableStateFlow<List<String>>(emptyList())
    val selectedPeerNames: StateFlow<List<String>> = selectedPeerNamesFlow.asStateFlow()

    // Non-empty only while genuinely undecided (more than one same-group peer nearby, no
    // confirmed pair resolved yet) — the inline picker UI (RaceProgressSummary/
    // EntryModeHeaderInfo) only ever renders then, exactly the "shown only when there's an
    // actual choice" behavior confirmed via this feature's own AskUserQuestion earlier in its
    // design. Lets every operator in a 3+-phone group see and explicitly agree on the same one
    // pair; see excludedNotice for what a phone that isn't part of it sees instead.
    private val peerChoicesFlow = MutableStateFlow<List<SelectablePeer>>(emptyList())
    val peerChoices: StateFlow<List<SelectablePeer>> = peerChoicesFlow.asStateFlow()

    /** Shown once this device has confirmed (via [resolvePairingOutcome]) that some OTHER mutual
     *  pair has formed among the nearby same-group peers, excluding it — see TODO.md's "the
     *  dropped ones should detect they've been dropped and exit count mode". Carries the two
     *  paired devices' own display names purely for that message. This engine has no navigation
     *  of its own to perform — the screen's own dismiss action on this notice is expected to be
     *  the exact same stopMode()+onHome() the existing "Stop Mode" dialog already uses (confirmed
     *  via this feature's own AskUserQuestion), which naturally clears this device's own
     *  OwnContext and lets this flow settle back to null on its own. */
    data class ExcludedNotice(val pairedName1: String, val pairedName2: String)
    private val excludedNoticeFlow = MutableStateFlow<ExcludedNotice?>(null)
    val excludedNotice: StateFlow<ExcludedNotice?> = excludedNoticeFlow.asStateFlow()

    /** Sets (or clears) this device's own pick — always replaces whatever was previously picked,
     *  since exactly one pick (or none) is ever meaningful now; see [ownPickFlow]'s own doc for
     *  why this alone doesn't yet start any syncing. Only meaningful while [peerChoices] is
     *  non-empty — with one or zero nearby, [startDetectionLoop]'s own auto-pick (see
     *  [recomputeOwnPick]) already settles it and there's nothing for the operator to toggle. */
    fun setPeerSelected(shortDeviceId: Long, selected: Boolean) {
        ownPickFlow.value = if (selected) shortDeviceId else null
    }

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
        startPairingDiscoveryLoop()
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
        ownPickFlow.value = null
        peerPicksFlow.value = emptyMap()
        pairingOutcomeFlow.value = PairingOutcome.Undecided
        confirmedPartnerIdFlow.value = null
        selectedPeerNamesFlow.value = emptyList()
        peerChoicesFlow.value = emptyList()
        excludedNoticeFlow.value = null
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
    private data class DetectionTick(
        val context: OwnContext,
        val peers: Map<String, NearbyPeer>,
        val fullNames: Map<Long, String>,
        val ownPick: Long?,
        val peerPicks: Map<Long, Long?>,
    )

    private fun startDetectionLoop() {
        engineScope.launch {
            combine(computeOwnContextFlow(), nearbyPeersFlow, fullDeviceNamesFlow, ownPickFlow, peerPicksFlow) { context, peers, fullNames, ownPick, peerPicks ->
                DetectionTick(context, peers, fullNames, ownPick, peerPicks)
            }.collect { (context, peers, fullNames, ownPick, peerPicks) ->
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

                    // Part 1 — exactly one mesh-wide, mutually-agreed pair, never a per-device
                    // fan-out: one matching peer per shortDeviceId (matchingKeys can hold several
                    // BLE-address keys for the same logical peer across an address rotation —
                    // collapsing to shortDeviceId first is what makes recomputeOwnPick/
                    // resolvePairingOutcome's own id-based logic meaningful).
                    val matchingPeerByShortId = matchingKeys.mapNotNull { key -> peers[key]?.decoded }.associateBy { it.shortDeviceId }
                    val nearbyIds = matchingPeerByShortId.keys
                    val newOwnPick = recomputeOwnPick(ownPick, nearbyIds)
                    if (newOwnPick != ownPick) {
                        ownPickFlow.value = newOwnPick
                    }
                    // Drops any cached pick for a peer no longer nearby — stale data would
                    // otherwise let resolvePairingOutcome "confirm" a pair against a peer that's
                    // since gone out of range.
                    val prunedPeerPicks = peerPicks.filterKeys { it in nearbyIds }
                    if (prunedPeerPicks != peerPicks) {
                        peerPicksFlow.value = prunedPeerPicks
                    }
                    fun nameFor(id: Long): String {
                        val decoded = matchingPeerByShortId[id]
                        return (decoded?.let { fullNames[it.shortDeviceId] ?: it.deviceName }).takeUnless { it.isNullOrBlank() } ?: id.toString()
                    }
                    val outcome = resolvePairingOutcome(ownShortDeviceId(), newOwnPick, prunedPeerPicks)
                    // Behind the same Setup Options "Bluetooth logging" toggle every other routine
                    // BLE trace line in this file already uses (see bleConnectionLog.log's own
                    // doc) — confirms whether this device's own pairingOutcome is actually
                    // settling and staying Paired, or flapping back to Undecided/Excluded tick to
                    // tick (which would explain a confirmed partner's connect traffic looking
                    // identical to startPairingDiscoveryLoop's own, with exchangePeerData rarely
                    // if ever actually reached) — confirmed useful chasing a live "paired but not
                    // converging" report, kept for next time rather than ripped out.
                    if (outcome != pairingOutcomeFlow.value) {
                        bleConnectionLog.log("peer-sync", "pairing outcome changed: ${pairingOutcomeFlow.value} -> $outcome (ownPick=$newOwnPick peerPicks=$prunedPeerPicks)")
                    }
                    pairingOutcomeFlow.value = outcome
                    confirmedPartnerIdFlow.value = (outcome as? PairingOutcome.Paired)?.partnerId
                    excludedNoticeFlow.value = (outcome as? PairingOutcome.Excluded)?.let { ExcludedNotice(nameFor(it.pairedA), nameFor(it.pairedB)) }
                    selectedPeerNamesFlow.value = (outcome as? PairingOutcome.Paired)?.let { listOf(nameFor(it.partnerId)) } ?: emptyList()
                    peerChoicesFlow.value = if (nearbyIds.size > 1 && outcome is PairingOutcome.Undecided) {
                        nearbyIds.map { id -> SelectablePeer(id, nameFor(id), id == newOwnPick) }.sortedBy { it.name }
                    } else {
                        emptyList()
                    }
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
                    flowOf(OwnContext(null, null, null, null))
                } else {
                    raceRepository.observeRace(raceId).map { race ->
                        // groupKey uses the plain (bucket-#2) mapping — Pen/Close both resolve to
                        // HistoryMode.BIBS, same as any other Bibs location, with location itself
                        // (not this mode) already doing the partitioning via
                        // PeerSyncRepository.groupKey. OwnContext.mode below is the separate,
                        // location-aware (bucket-#1) mapping countStatusOnly/mergeRecords need,
                        // so a Pen-located race still drives Count Mode's own pull/cache path —
                        // see AppMode.toHistoryMode's two overloads for why these must differ.
                        val groupKey = race?.let { peerGroupDetector.ownGroupKey(it.label, it.location, mode.toHistoryMode()) }
                        val dataMode = mode.toHistoryMode(race?.location.orEmpty())
                        OwnContext(raceId, groupKey, dataMode, race?.timeModeStartedAtMillis)
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
    // This device's own shortDeviceId — cached after first computation (settingsRepository's own
    // getOrCreateDeviceId is a one-time-generated, thereafter-stable value for the life of the
    // install, so there's nothing to invalidate). Used only for Part 2's role negotiation below.
    @Volatile
    private var cachedOwnShortDeviceId: Long? = null

    private suspend fun ownShortDeviceId(): Long =
        cachedOwnShortDeviceId ?: MuleGattProfile.shortDeviceId(settingsRepository.getOrCreateDeviceId()).also { cachedOwnShortDeviceId = it }

    // Refreshes peerPicksFlow by reading every nearby same-group peer's own DeviceInfo — the one
    // new source of connect traffic this feature adds, and deliberately bounded to the brief
    // negotiation window before an outcome settles (see the Undecided guard below): once this
    // device is Paired, pullFromPeerIfNeeded's own regular read of its confirmed partner already
    // keeps peerPicksFlow fresh for that one peer at zero extra connect cost, so there is nothing
    // left worth spending a connect on here. And once Excluded, there's nothing for this device
    // to do at all until the operator dismisses excludedNotice — confirmed live as a real,
    // disruptive bug when this only guarded on Paired: the excluded device kept connecting to
    // BOTH of the confirmed pair's phones every PAIRING_DISCOVERY_INTERVAL forever (it never
    // reaches Paired itself, so a Paired-only guard never stops it), repeatedly colliding with
    // their own now-legitimate central/peripheral traffic (the exact "Disconnect detected"
    // mutual-collision signature this whole codebase has otherwise worked hard to eliminate) and
    // starving their real exchange of a clean enough connection window to ever complete. Runs
    // independently of startPullLoop's own connect — that one only ever dials this device's
    // eventual CONFIRMED partner (Part 2's role negotiation), which is exactly what this loop
    // doesn't yet know while still undecided.
    private fun startPairingDiscoveryLoop() {
        engineScope.launch {
            while (isActive) {
                delay(PAIRING_DISCOVERY_INTERVAL)
                if (pairingOutcomeFlow.value !is PairingOutcome.Undecided) continue
                val groupKey = ownContextFlow.value.groupKey ?: continue
                val peers = nearbyPeersFlow.value.values.filter { peerGroupDetector.groupKeyOf(it.decoded) == groupKey }
                for (peer in peers) {
                    runCatching { muleRepository.readDeviceInfo(peer.advertisement) }
                        .onSuccess { info ->
                            peerPicksFlow.update { it + (peer.decoded.shortDeviceId to info.peerPickedShortDeviceId.takeIf { id -> id != 0L }) }
                            // Same capture pullFromPeerIfNeeded already does — without this, the
                            // picker (which only ever renders during this undecided phase, before
                            // that function ever runs for anyone) is stuck showing the advertised
                            // identity's own name, truncated to
                            // MuleGattProfile.ADVERTISED_NAME_MAX_BYTES (5 bytes) — "mighty-bunting"
                            // as "might" — confirmed live as genuinely unreadable to an operator.
                            if (info.deviceName.isNotBlank()) {
                                fullDeviceNamesFlow.update { it + (peer.decoded.shortDeviceId to info.deviceName) }
                            }
                        }
                        .onFailure { e -> Log.w(TAG, "peer-pick discovery readDeviceInfo failed: shortDeviceId=${peer.decoded.shortDeviceId}", e) }
                }
            }
        }
    }

    private fun startPullLoop() {
        engineScope.launch {
            while (isActive) {
                delay(PULL_INTERVAL + Random.nextLong(PULL_JITTER_RANGE_MS).milliseconds)
                val context = ownContextFlow.value
                val groupKey = context.groupKey
                val mode = context.mode
                val raceId = context.raceId
                if (groupKey == null || mode == null || raceId == null) continue
                // Part 1: only this device's own CONFIRMED (mutually-agreed — see
                // resolvePairingOutcome) partner is ever connected to, never a raw, one-sided
                // pick that peer hasn't reciprocated, and never more than the single mesh-wide
                // pair TODO.md's "restrict peers to 2" actually means (see ownPickFlow's own
                // doc) — so there is at most one peer to consider here now, not a list.
                val partnerId = confirmedPartnerIdFlow.value ?: continue
                val peer = nearbyPeersFlow.value.values.firstOrNull {
                    peerGroupDetector.groupKeyOf(it.decoded) == groupKey && it.decoded.shortDeviceId == partnerId
                } ?: continue
                // Part 2: central/peripheral role negotiation — this device only ever dials its
                // confirmed partner if it's central for it (lower shortDeviceId wins), eliminating
                // the mutual-dial collision this engine's whole backoff mechanism below was
                // otherwise only ever mitigating the symptom of. If this device is NOT central
                // for it, no outbound attempt is made at all; it's expected to dial in instead,
                // where PeripheralSyncService's own PEER_HELLO/PEER_PUSH_DATA handlers take over —
                // see this class's own doc for why that's an accepted assumption (both sides of a
                // real pair always run the same build in this fleet).
                val ownId = ownShortDeviceId()
                if (ownId >= peer.decoded.shortDeviceId) continue
                // Skip a peer still in its post-collision cooldown — see peerBackoffState's own
                // doc and pullFromPeerIfNeeded's readDeviceInfo failure branch for why this
                // exists and where it's set. Residual (non-mutual-dial) contention only now — see
                // Part 2's own doc above — but still a reasonable backstop.
                if ((peerBackoffState[peer.decoded.shortDeviceId]?.backoffUntilMillis ?: 0L) > System.currentTimeMillis()) continue
                runCatching {
                    withTimeout(PULL_PEER_TIMEOUT) { pullFromPeerIfNeeded(peer, groupKey, mode, raceId, context.raceStartedAtMillis) }
                }.onFailure { Log.w(TAG, "peer-sync pull tick failed: shortDeviceId=${peer.decoded.shortDeviceId}", it) }
            }
        }
    }

    private suspend fun pullFromPeerIfNeeded(peer: NearbyPeer, groupKey: String, mode: HistoryMode, raceId: Long, raceStartedAtMillis: Long?) {
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
                val backoffMs = Random.nextLong(rangeMs)
                peerBackoffState[peer.decoded.shortDeviceId] = PeerBackoffState(System.currentTimeMillis() + backoffMs, failures)
                bleConnectionLog.warn(
                    "peer-collision",
                    "readDeviceInfo failed: shortDeviceId=${peer.decoded.shortDeviceId} consecutiveFailures=$failures " +
                        "backoffMs=$backoffMs error=$e",
                )
            }
            .getOrNull() ?: return
        // A successful connect resets this peer's backoff — exponential backoff only makes sense
        // relative to a *run* of consecutive failures; carrying a doubled window over into an
        // otherwise-healthy link would just add needless latency to every future pull.
        if (peerBackoffState.remove(peer.decoded.shortDeviceId) != null) {
            bleConnectionLog.log("peer-collision", "recovered: shortDeviceId=${peer.decoded.shortDeviceId}")
        }
        // Captures the peer's own full device name regardless of whether there's anything new to
        // pull below — see fullDeviceNamesFlow's own doc for why display prefers this over the
        // advertised identity's truncated one.
        if (info.deviceName.isNotBlank()) {
            fullDeviceNamesFlow.update { it + (peer.decoded.shortDeviceId to info.deviceName) }
        }
        // Keeps peerPicksFlow fresh for this confirmed partner at zero extra connect cost — see
        // that field's own doc for why startPairingDiscoveryLoop deliberately skips it once
        // Paired. Lets a defection (this partner's own pick moving elsewhere) still be noticed
        // and re-resolved on the very next detection tick.
        peerPicksFlow.update { it + (peer.decoded.shortDeviceId to info.peerPickedShortDeviceId.takeIf { id -> id != 0L }) }
        // Part 3: one bidirectional, ack-less (gap-detected/resent at the chunk level, not
        // redone-from-scratch) exchange over this one connection, replacing both today's
        // page-at-a-time pull loop AND the separate dial this peer used to make back toward this
        // device for its own data — Part 2's role negotiation already guarantees this device is
        // the dialer for this peer, so that mirror-image connection never happens at all now; the
        // peripheral's own PEER_PUSH_DATA_CHARACTERISTIC_UUID write handler
        // (PeripheralSyncService.mergePeerPushPayload) is what lets this device's own new records
        // still reach it over this same connection, with no second dial needed.
        //
        // Count Mode alone pulls/pushes through the countStatusOnly cursor pair
        // (lastMergedCountStatusLineNumber/DeviceInfo.lastCountStatusLineNumber), instead of the
        // general lastMergedLineNumber/DeviceInfo.lastLineNumber pair every other mode (today,
        // just Bibs) still uses — see PullRequest.countStatusOnly's own doc for why: confirmed
        // live that the general path transfers every routine Ping/etc. line alongside the real
        // seen/ejected events, and a plain MAX(lineNumber) cursor with no action filter can get
        // stuck past a real, still-unmerged COUNT_EJECTED row the moment any later, irrelevant
        // row's lineNumber gets merged first.
        val countStatusOnly = mode == HistoryMode.COUNT
        val myLastLineNumber = peerSyncRepository.lastMergedLineNumber(info.deviceId)
        val myLastCountStatusLineNumber = peerSyncRepository.lastMergedCountStatusLineNumber(info.deviceId)
        val expectPeerPullData = if (countStatusOnly) {
            myLastCountStatusLineNumber < info.lastCountStatusLineNumber
        } else {
            myLastLineNumber < info.lastLineNumber
        }
        // Behind the Bluetooth logging toggle, same as pairingOutcomeFlow's own new trace line
        // above — pins down exactly what cursor comparison this device made and whether it
        // expected anything to pull, so a successful but no-op exchange is distinguishable from
        // one that never ran at all (exchangePeerData's own success path previously logged
        // nothing at all).
        bleConnectionLog.log(
            "peer-sync",
            "exchange starting: shortDeviceId=${peer.decoded.shortDeviceId} deviceId=${info.deviceId} " +
                "countStatusOnly=$countStatusOnly myLastLineNumber=$myLastLineNumber peerLastLineNumber=${info.lastLineNumber} " +
                "myLastCountStatusLineNumber=$myLastCountStatusLineNumber peerLastCountStatusLineNumber=${info.lastCountStatusLineNumber} " +
                "expectPeerPullData=$expectPeerPullData",
        )
        var myRecordsCount = -1
        var peerRecordsCount = -1
        runCatching {
            muleRepository.exchangePeerData(
                peer.advertisement,
                countStatusOnly = countStatusOnly,
                expectPeerPullData = expectPeerPullData,
                myLastLineNumber = myLastLineNumber,
                myLastCountStatusLineNumber = myLastCountStatusLineNumber,
                myRecordsSince = { since ->
                    val records = if (countStatusOnly) {
                        countModeRepository.countStatusEventsSince(raceId, since, maxRecords = null).map { it.toSyncRecord(raceStartedAtMillis) }
                    } else {
                        raceRepository.getHistorySinceLineNumber(raceId, since).map { it.toSyncRecord(raceStartedAtMillis) }
                    }
                    myRecordsCount = records.size
                    bleConnectionLog.log("peer-sync", "push myRecordsSince: since=$since countStatusOnly=$countStatusOnly found=${records.size}")
                    records
                },
                onPeerRecordsReceived = { records ->
                    peerRecordsCount = records.size
                    peerSyncRepository.mergeRecords(groupKey, mode, info.deviceId, info.deviceName, records)
                },
            )
        }.onSuccess {
            bleConnectionLog.log("peer-sync", "exchange ok: shortDeviceId=${peer.decoded.shortDeviceId} pushed=$myRecordsCount pulled=$peerRecordsCount")
        }.onFailure { Log.w(TAG, "peer-sync exchange failed: deviceId=${info.deviceId}", it) }
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

        // How often startPairingDiscoveryLoop re-reads every nearby same-group peer's own
        // DeviceInfo while undecided — see that function's own doc. Deliberately slower than
        // PULL_INTERVAL (this is a short, rare negotiation window, not steady-state syncing) but
        // still fast enough that two operators tapping their picks within a couple of seconds of
        // each other see the pairing resolve promptly rather than waiting out a long poll.
        private val PAIRING_DISCOVERY_INTERVAL = 2.seconds

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

/** Part 1's pure "zero-touch for the trivial case" auto-pick, used by
 *  [PeerSyncEngine.startDetectionLoop]: keeps [previousPick] if it's still among [nearbyIds];
 *  otherwise auto-picks the sole nearby peer when there's exactly one (the entire "two phones in
 *  range of each other" case — no operator action needed), or clears to null whenever there's a
 *  genuine choice to make (more than one nearby) or nobody nearby at all. A top-level function,
 *  not a method, so it's directly testable with no [PeerSyncEngine] instance needed — same
 *  convention [mobile.racemaster.data.mule.BleConnectionLog]'s own `trimOldestHalf` already
 *  sets. */
internal fun recomputeOwnPick(previousPick: Long?, nearbyIds: Set<Long>): Long? {
    if (previousPick != null && previousPick in nearbyIds) return previousPick
    return nearbyIds.singleOrNull()
}

/** The two-sided result of comparing every nearby same-group peer's own reported pick against
 *  this device's own — see [resolvePairingOutcome]. */
internal sealed class PairingOutcome {
    object Undecided : PairingOutcome()
    data class Paired(val partnerId: Long) : PairingOutcome()
    data class Excluded(val pairedA: Long, val pairedB: Long) : PairingOutcome()
}

/** Resolves whether a confirmed, MUTUAL pair exists among [selfId] (with its own [selfPick]) and
 *  every nearby peer's own reported pick in [peerPicks]. TODO.md's "restrict peers to 2" means
 *  exactly one bidirectional pair may exist mesh-wide among any set of mutually-visible
 *  same-group phones, with everyone else excluded — so a one-sided pick alone (this device's or
 *  anyone else's) is never enough; both sides of a pair must report picking each other. Returns
 *  [PairingOutcome.Paired] with [selfId]'s own partner if [selfId] is part of such a pair,
 *  [PairingOutcome.Excluded] naming whichever OTHER mutual pair has already formed if one has
 *  (so the UI can tell the operator exactly why this device was left out — TODO.md's "the
 *  dropped ones should detect they've been dropped"), or [PairingOutcome.Undecided] if no mutual
 *  pair exists anywhere yet. A top-level, pure function for the same testability reasons
 *  [recomputeOwnPick] documents. */
internal fun resolvePairingOutcome(selfId: Long, selfPick: Long?, peerPicks: Map<Long, Long?>): PairingOutcome {
    val allPicks = peerPicks + (selfId to selfPick)
    val mutualPairs = allPicks.entries
        .mapNotNull { (id, pick) -> pick?.takeIf { allPicks[it] == id }?.let { partner -> setOf(id, partner) } }
        .distinct()
    val ownPair = mutualPairs.firstOrNull { selfId in it }
    if (ownPair != null) return PairingOutcome.Paired((ownPair - selfId).first())
    val otherPair = mutualPairs.firstOrNull() ?: return PairingOutcome.Undecided
    val (a, b) = otherPair.toList()
    return PairingOutcome.Excluded(a, b)
}
