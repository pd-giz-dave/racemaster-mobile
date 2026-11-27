package mobile.racemaster.ui.countmode

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import mobile.racemaster.data.beacon.BeaconScanDecoder
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.PeerSeenRecordEntity
import mobile.racemaster.data.mule.BluetoothStateRepository
import mobile.racemaster.data.mule.BtPollingStatus
import mobile.racemaster.data.mule.LocationStateRepository
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.mule.ProgressRepository
import mobile.racemaster.data.mule.ServerStatus
import mobile.racemaster.data.mule.ServerStatusRepository
import mobile.racemaster.data.mule.ServerStatusState
import mobile.racemaster.data.mule.toHistoryAction
import mobile.racemaster.data.peersync.PeerGroupDetector
import mobile.racemaster.data.peersync.PeerSyncEngine
import mobile.racemaster.data.peersync.PeerSyncRepository
import mobile.racemaster.data.repository.CountBeaconStatus
import mobile.racemaster.data.repository.CountModeRepository
import mobile.racemaster.data.repository.LineSyncState
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.latestCountEventRowByBeacon
import mobile.racemaster.data.repository.lineSyncState
import mobile.racemaster.data.repository.linesWithAnySync
import mobile.racemaster.data.repository.mergedStatusByBeacon
import mobile.racemaster.data.repository.parseBeaconCount
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import mobile.racemaster.ui.bibsmode.displayName
import mobile.racemaster.ui.btPollingStatusFlow
import mobile.racemaster.ui.deviceNameFlow

// TODO.md "the ejected lines should follow the same colouring scheme as the seen lines and not
// just be red" — syncState mirrors SeenBeaconUi's own: this device's own local row's sync status
// when its latest local COUNT_EJECTED/COUNT_UNEJECTED row is actually what the currently-shown
// (possibly peer-merged) [status] reflects, null otherwise — see recomputeOutstanding's own doc
// for exactly when that is (a peer's own later eject/uneject leaves this device with no local row
// matching the merged status, same "local is the source" gate SeenBeaconUi.syncState follows).
data class OutstandingBeaconUi(
    val bibNumber: Int,
    val beaconNumber: Int,
    val status: CountBeaconStatus? = null,
    val currentlyVisible: Boolean = false,
    val syncState: LineSyncState? = null,
)

// originDeviceName is non-null exactly for a same-group peer's own report of this beacon that
// THIS device has never itself recorded (see recomputeOutstanding) — same-mode peer-sync
// feature, Phase 4. Null means an ordinary local COUNT_SEEN row, same as before this field
// existed. A peer-sourced entry has no local row to target, so CountModeScreen must not offer
// its own "Eject" link for one (ejectBeacon would just silently no-op against it).
//
// TODO.md "count mode lines need to follow the red->orange->green colour scheme" — syncState is
// this device's own local row's sync status (see recomputeOutstanding), null for a peer-sourced
// entry (originDeviceName != null): a peer's own report has no local sync lifecycle of this
// device's own to show, same reasoning withholding the Eject link above already follows.
data class SeenBeaconUi(val bibNumber: Int?, val beaconNumber: Int, val originDeviceName: String? = null, val syncState: LineSyncState? = null)

// TODO.md "need to be able to see the peer lines pulled from nearby devices in the history (as a
// diagnostic aid)" — the raw feed [peerStatusRowsFlow] already pulls for mergedStatusByBeacon's
// own merge, also surfaced here for display. Defined in this package (not racehistory) simply
// because this was its first use; RaceHistoryDetailViewModel imports both this and
// [peerActivityFrom] directly rather than duplicating them — see TODO.md's own "move the peer
// activity view from the count mode screen to the history screen" entry for why that screen, not
// this one, is its real display home now (CountModeScreen keeps only a one-line count — see
// CountModeUiState.peerActivityCount). [actionLabel] reuses HistoryAction.displayName() via
// row.toHistoryAction() (the row's own action string is the wire format, e.g. "CountEjected" —
// see SyncRecordMapping's own doc, not the enum's .name — HistoryAction.valueOf against it
// always failed silently here, same bug mergedStatusByBeacon's own doc describes).
data class PeerActivityLineUi(
    val originDeviceName: String,
    val actionLabel: String,
    val beaconNumber: Int?,
    val bibNumber: Int?,
    val timestampMillis: Long,
)

// Pure, directly testable (like isSkippedAsStale/staleDeletionSummary in RaceHistoryViewModel) —
// newest first, since this is a diagnostic log an operator scans top-down for "what just arrived."
internal fun peerActivityFrom(rows: List<PeerSeenRecordEntity>): List<PeerActivityLineUi> =
    rows.sortedByDescending { it.timestampMillis }.map { row ->
        PeerActivityLineUi(
            originDeviceName = row.originDeviceName,
            actionLabel = row.toHistoryAction().displayName(),
            beaconNumber = row.beaconNumber,
            bibNumber = row.bibNumber,
            timestampMillis = row.timestampMillis,
        )
    }

// See CountModeViewModel's own race-progress collector — packed into one small holder rather
// than combine()'s own 6th/7th argument, same Triple/Pair-packing reasoning BibsModeViewModel's
// own raceAndEntriesFlow doc gives (kotlinx coroutines' typed combine() overloads only go up to 5).
private data class RaceProgressInfo(
    val raceLabel: String = "",
    val raceLocation: String = "",
    val unsyncedCount: Int = 0,
    val lastSyncedAtMillis: Long? = null,
    val serverStatus: ServerStatusState = ServerStatusState(ServerStatus.UNKNOWN, null),
)

data class CountModeUiState(
    val uniqueBeaconsSeen: Int = 0,
    val outstanding: List<OutstandingBeaconUi> = emptyList(),
    val seen: List<SeenBeaconUi> = emptyList(),
    val totalIssued: Int = 0,
    // Gates both the top bar's "Stop" button and the body's "Reset" button (see
    // CountModeViewModel.resetCountMode's own doc) — same reasoning as
    // CloseModeUiState.raceId/canStopCloseMode: Stop (clearing the race's mode back to null —
    // see RaceRepository.stopMode) is the only way to move this race out of Count mode once it's
    // set up, and Reset does that same thing before additionally resetting the tally.
    // canStopCountMode additionally requires the race's own CURRENT mode/location to actually be
    // Bibs-at-Pen (Count no longer has its own AppMode value — see AppMode's own doc on the
    // Count/Close amalgamation) — this screen is only reached via Routes.BIBS_MODE's own
    // location dispatch now (Setup Race), which is itself only reached once the race's mode is
    // Bibs and its location is Pen, so this is effectively always true in practice — kept as a
    // defensive guard (same reasoning CloseModeUiState.canStopCloseMode documents) rather than
    // removed outright.
    val raceId: Long? = null,
    val canStopCountMode: Boolean = false,
    // Same feedback RaceProgressSummary already shows for Bibs/Time Mode (and now Close Mode) —
    // Count had none of its own before; see CountModeScreen's own RaceProgressSummary call.
    val raceLabel: String = "",
    val raceLocation: String = "",
    val unsyncedCount: Int = 0,
    val lastSyncedAtMillis: Long? = null,
    val serverStatus: ServerStatusState = ServerStatusState(ServerStatus.UNKNOWN, null),
    // TODO.md "move the peer activity view from the count mode screen to the history screen...
    // but leave a feedback line on the count mode screen on the number of lines received" — the
    // raw PeerActivityLineUi list itself now lives on RaceHistoryDetailScreen instead (see
    // RaceHistoryDetailViewModel); this is just its count, for CountModeScreen's own one-line
    // feedback.
    val peerActivityCount: Int = 0,
)

/**
 * Pre-start headcount — docs/beacon-detection-plan.md's Count mode. Detection itself is purely
 * passive: no tap, no per-beacon confirmation, just a running "unique beacons seen" count as
 * runners walk past the phone. The operator can still eject an already-seen beacon directly —
 * [ejectBeacon] (TODO.md: a runner leaves and comes back later having forgotten their kit, or
 * the operator needs to un-count one entered by mistake) — and an ejected beacon is NOT let back
 * in just because it's detected again: [commitDetection] leaves it out of the count entirely
 * until the operator explicitly calls [unejectBeacon] (TODO.md's own "selecting the ejected link
 * should un-eject it"; an earlier version of this screen instead auto-recounted it after a fixed
 * cooldown, confirmed not to work operationally and dropped — see [HistoryAction.COUNT_EJECTED]/
 * [HistoryAction.COUNT_UNEJECTED]'s own docs). Both [ejectBeacon] and [unejectBeacon] sit behind
 * their own confirm dialog in [CountModeScreen] (TODO.md: "so the operator can check they hit
 * the right one") — a WAS_EJECTED beacon's own tag doubles as a re-eject link, confirmed the
 * same way, since [ejectBeacon] already accepts either a currently-seen or a WAS_EJECTED beacon. [lastDetectedAtMillis] is a separate, purely
 * cosmetic live-presence signal (TODO.md: "an ejected beacon that is visible should be
 * highlighted") — it never gates recording, only [OutstandingBeaconUi.currentlyVisible]. ViewModel-
 * scoped (not a [mobile.racemaster.data.mule.PeripheralSyncService]-hosted
 * process-lifetime engine like [mobile.racemaster.data.beacon.BeaconScanEngine]) — a pre-race
 * headcount is a short, actively-supervised task, the same reasoning
 * [mobile.racemaster.ui.closemode.CloseModeViewModel]'s own scan loop already follows; see the
 * plan's own doc for why this is the one genuinely judgment-call design choice here (easy to
 * promote to a process-lifetime engine later if operators background the screen mid-count).
 *
 * A sibling of [BeaconScanEngine], not a shared instance — the dedup key (beacon number, not
 * resolved bib — a beacon can be counted before its bib pairing is known, per the doc's own "a
 * count of unique beacons seen"), the gating mode, and the write target (COUNT_SEEN rows via
 * [CountModeRepository], not Bibs entries) all differ from Bibs' own checkpoint detection.
 * [handleBeacon] commits on the very first in-range reading, no confidence threshold — direct
 * feedback that Count's only question is "did this runner turn up at the start," not a timed
 * split, so a spurious single reading costs nothing worse than counting someone a few seconds
 * early; [BeaconScanEngine] dropped the same former 3-reading debounce for the same reason (see
 * its own doc).
 *
 * Reached only via Setup Race's own Bibs mode at the "Pen" location (`Routes.BIBS_MODE`'s own
 * location dispatch — see RacemasterNavHost's own doc) — unlike
 * [mobile.racemaster.ui.closemode.CloseModeViewModel], there is no standing Beacons-hub shortcut
 * to this screen any more (TODO.md: Count only ever makes sense against a race already set up
 * for it, unlike Close — see [mobile.racemaster.ui.beaconops.BeaconOperationsScreen]'s own doc).
 * Recording a count itself never branches on `race.mode` — it just records COUNT_SEEN rows
 * against whatever `activeRaceId` is; the top bar's own Stop button (see
 * [CountModeUiState.canStopCountMode]) still checks the race's current mode, mostly as a
 * defensive guard now that the mismatched-mode path is unreachable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CountModeViewModel(
    private val muleRepository: MuleRepository,
    private val countModeRepository: CountModeRepository,
    private val progressRepository: ProgressRepository,
    private val raceRepository: RaceRepository,
    private val settingsRepository: SettingsRepository,
    private val peerGroupDetector: PeerGroupDetector,
    private val peerSyncRepository: PeerSyncRepository,
    peerSyncEngine: PeerSyncEngine,
    bluetoothStateRepository: BluetoothStateRepository,
    locationStateRepository: LocationStateRepository,
    private val serverStatusRepository: ServerStatusRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CountModeUiState())
    val uiState: StateFlow<CountModeUiState> = _uiState.asStateFlow()

    private val raceIdFlow: StateFlow<Long?> = settingsRepository.activeRaceId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Echoed under the title (TODO.md) — same "Device name: ..." convention every other mode's
    // own RaceProgressSummary already shows; Count has no such shared header, so this screen
    // reads it directly.
    val deviceName: StateFlow<String?> = deviceNameFlow(settingsRepository)

    // Same-group peers currently nearby over BLE — PeerSyncEngine is independent of this
    // ViewModel's own lifecycle (see its own doc), so this just exposes its already-live
    // StateFlow directly rather than re-deriving anything.
    val nearbyPeerNames: StateFlow<List<String>> = peerSyncEngine.nearbyPeerNames

    // Same Bluetooth/Location status Bibs/Time Mode already show (see BtPollingStatusLine's own
    // doc) — Count has no such line of its own today; this is what makes a Location-off phone's
    // own silently-empty nearbyPeerNames above explicable from this screen alone, rather than
    // looking like an unexplained bug.
    val btPollingStatus: StateFlow<BtPollingStatus> = btPollingStatusFlow(bluetoothStateRepository, locationStateRepository, settingsRepository)

    // Guards against a duplicate COUNT_SEEN row for the same beacon — every single advertisement
    // from an already-committing beacon re-triggers commitDetection instantly (there's no
    // debounce any more — see handleBeacon's own doc), re-entering it before the first call's
    // db.withTransaction has completed and fed back through seenBeaconNumbers (which only updates
    // once that write's own Flow re-emits — strictly after the fact, not synchronously). Marked
    // synchronously, before commitDetection's own first suspension point, so a whole burst of
    // 200ms-spaced advertisements arriving while one commit is still in flight only ever lets the
    // first one through; cleared once that attempt finishes (success or failure) so a legitimate
    // later recount (e.g. after an eject/uneject round-trip) is never blocked by it.
    private val inFlightCommits = ConcurrentHashMap.newKeySet<Int>()

    // Every beacon's own last-detected instant, regardless of debounce/commit state — purely for
    // the live "currently visible" highlight (TODO.md), recomputed into
    // CountModeUiState.outstanding's own currentlyVisible flag by the ticker in init{}. Never
    // read by commitDetection/recordCount — detection gating is unaffected by this map.
    private val lastDetectedAtMillis = ConcurrentHashMap<Int, Long>()

    private var bibByBeaconNumber: Map<Int, Int> = emptyMap()

    // Mirrors countModeRepository.observeCounts(raceId)'s own rows (already fold-aware — an
    // ejected-and-not-yet-un-ejected beacon's own COUNT_SEEN row is excluded — see
    // CountModeRepository.observeCounts' own doc) — the single source of truth for "already
    // counted," same reasoning BeaconScanEngine.recordedBibs documents, just keyed by beacon
    // number instead of resolved bib.
    @Volatile
    private var seenBeaconNumbers: Set<Int> = emptySet()

    // Each beacon's own EJECTED/WAS_EJECTED status, read separately from
    // countModeRepository.observeStatusEvents/statusByBeacon — see that function's own doc for why
    // this can't be derived from seenBeaconNumbers' own (fold-collapsed) rows. commitDetection's
    // own "stay excluded until explicitly let back in" gate reads this map directly; WAS_EJECTED
    // is purely informational there (gates nothing).
    @Volatile
    private var statusByBeacon: Map<Int, CountBeaconStatus> = emptyMap()

    // Same-mode peer-sync feature, Phase 4: every beacon a same-group Count peer has reported,
    // mapped to its most recent reporting peer's own device name — see peerSeenBeaconsFlow's own
    // doc. Folded into seenBeaconNumbers' union in recomputeOutstanding, same reasoning
    // statusByBeacon documents for why this is a second, separately-updated field rather than
    // baked into seenBeaconNumbers itself: the two have genuinely different provenance (this
    // device's own fold-aware local rows vs. another device's raw reports) that recomputeOutstanding
    // still needs to tell apart (a peer-only entry has no local row for Eject to target).
    @Volatile
    private var peerSeenOriginByBeacon: Map<Int, String> = emptyMap()

    // This device's own local COUNT_SEEN row per beacon (not just its beacon number, the way
    // seenBeaconNumbers reduces it) — recomputeOutstanding needs the row itself (lineNumber,
    // syncedAtMillis) to compute each one's own LineSyncState for the "Seen" list's new
    // red->orange->green colouring (TODO.md). A peer-sourced entry has no row here at all —
    // SeenBeaconUi.syncState stays null for one, same reasoning its own doc gives for withholding
    // the Eject link.
    @Volatile
    private var localSeenRowsByBeacon: Map<Int, HistoryLineEntity> = emptyMap()

    // This device's own latest local COUNT_SEEN/COUNT_EJECTED/COUNT_UNEJECTED row per beacon (see
    // latestCountEventRowByBeacon's own doc) — recomputeOutstanding's own counterpart to
    // localSeenRowsByBeacon above, for the "Ejected"/"Not Seen" list's new red->orange->green
    // colouring (TODO.md). Unlike localSeenRowsByBeacon, this is read against statusByBeacon's own
    // merged result rather than used unconditionally — see recomputeOutstanding's own doc for why.
    @Volatile
    private var localStatusRowByBeacon: Map<Int, HistoryLineEntity> = emptyMap()

    // Every lineNumber with at least one LineSyncEntity row (relayed to somebody, sink or not) —
    // see the top-level linesWithAnySync() function's own doc. The other half lineSyncState
    // needs alongside each row's own syncedAtMillis. Named with its own "Set" suffix (unlike
    // that function's identically-named parameter elsewhere in this codebase) just to keep this
    // field visually distinct from the function that computes it, used right below.
    @Volatile
    private var linesWithAnySyncSet: Set<Long> = emptySet()

    init {
        viewModelScope.launch { collectAdvertisements() }
        viewModelScope.launch { tickCurrentlyVisible() }
        // Same feedback RaceProgressSummary already derives for Bibs/Time Mode — a separate,
        // independent collector (not folded into the combine below) since it's unrelated to the
        // counting logic that drives, matching this class's own established "separate collectors
        // each update their own slice of _uiState" convention (see canStopCountMode's own
        // collector just below for the precedent).
        viewModelScope.launch {
            raceIdFlow.flatMapLatest { raceId ->
                if (raceId == null) {
                    flowOf(RaceProgressInfo())
                } else {
                    combine(
                        raceRepository.observeRace(raceId),
                        countModeRepository.observeUnsyncedCount(raceId),
                        combine(countModeRepository.observeLastSyncedAtMillis(raceId), serverStatusRepository.state) { lastSyncedAtMillis, serverStatus ->
                            lastSyncedAtMillis to serverStatus
                        },
                    ) { race, unsyncedCount, (lastSyncedAtMillis, serverStatus) ->
                        RaceProgressInfo(race?.label.orEmpty(), race?.location.orEmpty(), unsyncedCount, lastSyncedAtMillis, serverStatus)
                    }
                }
            }.collectLatest { info ->
                _uiState.update {
                    it.copy(
                        raceLabel = info.raceLabel,
                        raceLocation = info.raceLocation,
                        unsyncedCount = info.unsyncedCount,
                        lastSyncedAtMillis = info.lastSyncedAtMillis,
                        serverStatus = info.serverStatus,
                    )
                }
            }
        }
        viewModelScope.launch {
            raceIdFlow.flatMapLatest { raceId ->
                if (raceId == null) flowOf(null) else raceRepository.observeRace(raceId).map { it?.mode to it?.location }
            }.collectLatest { modeAndLocation ->
                val (mode, location) = modeAndLocation ?: (null to null)
                _uiState.update {
                    it.copy(canStopCountMode = mode == AppMode.BIBS.name && location.equals("Pen", ignoreCase = true))
                }
            }
        }
        viewModelScope.launch {
            raceIdFlow.collectLatest { raceId ->
                lastDetectedAtMillis.clear()
                inFlightCommits.clear()
                _uiState.update { it.copy(raceId = raceId) }
                if (raceId == null) {
                    bibByBeaconNumber = emptyMap()
                    seenBeaconNumbers = emptySet()
                    statusByBeacon = emptyMap()
                    peerSeenOriginByBeacon = emptyMap()
                    localSeenRowsByBeacon = emptyMap()
                    localStatusRowByBeacon = emptyMap()
                    linesWithAnySyncSet = emptySet()
                    _uiState.update {
                        it.copy(outstanding = emptyList(), seen = emptyList(), totalIssued = 0, uniqueBeaconsSeen = 0)
                    }
                    return@collectLatest
                }
                combine(
                    progressRepository.observeIssuedBeacons(raceId),
                    // Paired rather than a 6th top-level combine argument — kotlinx coroutines'
                    // typed combine() overloads only go up to 5, same reasoning RaceProgressInfo's
                    // own collector above already follows.
                    combine(countModeRepository.observeCounts(raceId), raceRepository.observeLineSyncs(raceId)) { rows, lineSyncs -> rows to lineSyncs },
                    countModeRepository.observeStatusEvents(raceId),
                    peerSeenBeaconsFlow(raceId),
                    peerStatusRowsFlow(raceId),
                ) { issued, (rows, lineSyncs), statusEvents, peerSeen, peerStatusRows ->
                    bibByBeaconNumber = issued.associate { it.beaconNumber!! to it.bibNumber }
                    localSeenRowsByBeacon = rows.filter { it.action == HistoryAction.COUNT_SEEN }
                        .mapNotNull { row -> parseBeaconCount(row.note)?.let { it to row } }.toMap()
                    seenBeaconNumbers = localSeenRowsByBeacon.keys
                    linesWithAnySyncSet = linesWithAnySync(lineSyncs)
                    // TODO.md "if a beacon is ejected it must be flagged as such on all phones
                    // counting beacons" — merged across this device's own events and a same-group
                    // peer's own reported events, see mergedStatusByBeacon's own doc.
                    statusByBeacon = mergedStatusByBeacon(statusEvents, peerStatusRows)
                    localStatusRowByBeacon = latestCountEventRowByBeacon(statusEvents)
                    peerSeenOriginByBeacon = peerSeen
                    _uiState.update { it.copy(totalIssued = issued.size, peerActivityCount = peerStatusRows.size) }
                    recomputeOutstanding()
                }.collect { }
            }
        }
    }

    // Recomputes CountModeUiState.outstanding's own currentlyVisible flag every
    // VISIBLE_RECOMPUTE_INTERVAL_MILLIS for as long as this ViewModel is alive — a beacon drops
    // out of "currently visible" once it's genuinely been quiet for VISIBLE_WINDOW_MILLIS,
    // without needing a dedicated event for "it just went out of range" the way detectionTracking
    // itself has none either.
    private suspend fun tickCurrentlyVisible() {
        while (viewModelScope.isActive) {
            recomputeOutstanding()
            delay(VISIBLE_RECOMPUTE_INTERVAL_MILLIS)
        }
    }

    // Same-mode peer-sync feature, Phase 4: a beacon a same-group peer has reported counts as
    // converged state just as much as this device's own local COUNT_SEEN row does — the entire
    // point of this feature is that several Count phones share one combined picture of who's
    // been counted, not each its own separate tally (see the design doc's own framing). Folded
    // into outstanding/seen/uniqueBeaconsSeen here (display only); peerSeenOriginByBeacon never
    // feeds commitDetection's own "already seen" gate (handleBeacon/commitDetection are
    // untouched) — this device still writes its own local COUNT_SEEN row if it also physically
    // detects a beacon a peer already reported, exactly as before this feature existed. That's a
    // deliberate choice, not an oversight: a local row is this device's own append-only ledger
    // entry, independent of whatever any peer has or hasn't reported.
    private fun recomputeOutstanding() {
        val now = System.currentTimeMillis()
        val peerSeen = peerSeenOriginByBeacon
        // peerSeen (observeLatestOriginByBeacon) tags a beacon by whichever peer row is most
        // recent for it regardless of action — a peer's own COUNT_EJECTED row for a beacon it
        // previously counted makes that row the most recent one, so without this subtraction the
        // beacon would wrongly stay in unionSeen (and so out of `outstanding`) even though
        // statusByBeacon (merged the same way, see mergedStatusByBeacon) already says EJECTED —
        // which must always revert a beacon to outstanding, same as a local-only eject already
        // does via its own fold dropping the COUNT_SEEN row entirely.
        val ejectedBeacons = statusByBeacon.filterValues { it == CountBeaconStatus.EJECTED }.keys
        val unionSeen = (seenBeaconNumbers + peerSeen.keys) - ejectedBeacons
        val outstanding = bibByBeaconNumber.entries
            .filter { (beaconNumber, _) -> beaconNumber !in unionSeen }
            .map { (beaconNumber, bibNumber) ->
                val status = statusByBeacon[beaconNumber]
                // TODO.md "the ejected lines should follow the same colouring scheme as the seen
                // lines" — only when this device's own latest local row actually matches the
                // currently-shown (possibly peer-merged) status: a peer's own later eject/uneject
                // leaves this device with no local row for that status at all, so there's nothing
                // of this device's own to report a sync state for — same reasoning
                // SeenBeaconUi.syncState's own doc gives for a peer-sourced Seen entry.
                val localRow = localStatusRowByBeacon[beaconNumber]
                val localStatus = when (localRow?.action) {
                    HistoryAction.COUNT_EJECTED -> CountBeaconStatus.EJECTED
                    HistoryAction.COUNT_UNEJECTED -> CountBeaconStatus.WAS_EJECTED
                    else -> null
                }
                val syncState = if (localRow != null && localStatus == status) {
                    lineSyncState(localRow.syncedAtMillis, localRow.lineNumber in linesWithAnySyncSet)
                } else {
                    null
                }
                OutstandingBeaconUi(
                    bibNumber = bibNumber,
                    beaconNumber = beaconNumber,
                    status = status,
                    currentlyVisible = (lastDetectedAtMillis[beaconNumber]?.let { now - it <= VISIBLE_WINDOW_MILLIS }) ?: false,
                    syncState = syncState,
                )
            }
            .sortedBy { it.bibNumber }
        val seen = unionSeen
            .map { beaconNumber ->
                // A beacon this device has ALSO recorded locally is shown as its own local entry
                // (originDeviceName null), even if a peer happens to have reported it too — only
                // a peer-ONLY beacon (no local row at all) is tagged with where it came from.
                val originDeviceName = if (beaconNumber in seenBeaconNumbers) null else peerSeen[beaconNumber]
                // TODO.md "count mode lines need to follow the red->orange->green colour scheme"
                // — only for this device's own local row (originDeviceName null); a peer-sourced
                // entry has nothing of this device's own to report a sync status for.
                val syncState = if (originDeviceName == null) {
                    localSeenRowsByBeacon[beaconNumber]?.let { row -> lineSyncState(row.syncedAtMillis, row.lineNumber in linesWithAnySyncSet) }
                } else {
                    null
                }
                SeenBeaconUi(bibByBeaconNumber[beaconNumber], beaconNumber, originDeviceName, syncState)
            }
            .sortedBy { it.bibNumber ?: Int.MAX_VALUE }
        _uiState.update {
            it.copy(outstanding = outstanding, seen = seen, uniqueBeaconsSeen = unionSeen.size)
        }
    }

    // This device's own current convergence-group key (see PeerGroupDetector.ownGroupKey's own
    // doc) scoped to [raceId], re-resolved every time the race's own label changes — null (same
    // as PeerGroupDetector's own "nothing to converge on yet" contract) folds straight through to
    // an empty peer map via flatMapLatest below, so every downstream read stays "local only"
    // exactly as it behaved before this feature existed. Passes HistoryMode.BIBS, not COUNT, to
    // ownGroupKey — that's the bucket-#2 (peer-discovery) mapping every Bibs location (including
    // Pen) now advertises/decodes as (see AppMode.toHistoryMode's own doc), with location itself
    // still correctly partitioning it. The *data* reads below stay keyed on HistoryMode.COUNT —
    // that's the separate, unaffected bucket-#1 tag PeerSyncEngine's own pull/merge path uses.
    private fun peerSeenBeaconsFlow(raceId: Long): Flow<Map<Int, String>> =
        raceRepository.observeRace(raceId)
            .map { race -> race?.let { peerGroupDetector.ownGroupKey(it.label, it.location, HistoryMode.BIBS) } }
            .distinctUntilChanged()
            .flatMapLatest { groupKey ->
                if (groupKey == null) flowOf(emptyMap()) else peerSyncRepository.observeLatestOriginByBeacon(groupKey, HistoryMode.COUNT)
            }

    // TODO.md "if a beacon is ejected it must be flagged as such on all phones counting beacons":
    // the raw peer rows mergedStatusByBeacon needs, same groupKey derivation as
    // peerSeenBeaconsFlow above (duplicated rather than shared — see BibsModeViewModel's own
    // observeLatestOriginByBib/observeGroup pair for the same already-established "two separate
    // reads of the same underlying group, for two different purposes" precedent).
    private fun peerStatusRowsFlow(raceId: Long): Flow<List<PeerSeenRecordEntity>> =
        raceRepository.observeRace(raceId)
            .map { race -> race?.let { peerGroupDetector.ownGroupKey(it.label, it.location, HistoryMode.BIBS) } }
            .distinctUntilChanged()
            .flatMapLatest { groupKey ->
                if (groupKey == null) flowOf(emptyList()) else peerSyncRepository.observeGroup(groupKey, HistoryMode.COUNT)
            }

    private suspend fun collectAdvertisements() {
        try {
            muleRepository.scanForAdvertisements().collect { advertisement ->
                val beacon = BeaconScanDecoder.decodeFromAdvertisement(advertisement) ?: return@collect
                handleBeacon(beacon.beaconNumber, beacon.estimatedDistanceMeters)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "beacon scan failed", e)
        }
    }

    private fun handleBeacon(beaconNumber: Int, distanceMeters: Double) {
        // Beacon 000 is the sweep — never counted (TODO.md).
        if (beaconNumber == 0) return
        // Nothing issued for this race yet (progress.json hasn't synced, or this course isn't
        // using Beacons at all) — recording COUNT_SEEN rows against a total of 0 only produced a
        // confusing "0 of 0" with every beacon logged under a null bib, so Count mode is a no-op
        // until there's an actual expectation list to count against.
        if (bibByBeaconNumber.isEmpty()) return
        if (distanceMeters > COUNT_MAX_DISTANCE_METERS) return
        lastDetectedAtMillis[beaconNumber] = System.currentTimeMillis()
        viewModelScope.launch { commitDetection(beaconNumber) }
    }

    private suspend fun commitDetection(beaconNumber: Int) {
        if (beaconNumber in seenBeaconNumbers) return
        // Ejected and not yet explicitly let back in (unejectBeacon) — stays excluded regardless
        // of how many times it's detected; TODO.md: the earlier cooldown-based auto-recount
        // "does not work operationally" and is gone (see this class' own doc).
        if (statusByBeacon[beaconNumber] == CountBeaconStatus.EJECTED) return
        // See inFlightCommits' own doc — must run before this function's first suspension point
        // below, so it's ordered against any other commitDetection call for this same beacon
        // still queued on this same (Main.immediate) dispatcher, not racing it.
        if (!inFlightCommits.add(beaconNumber)) return
        try {
            val raceId = raceIdFlow.value ?: return
            val bib = progressRepository.getBibForBeacon(raceId, beaconNumber)
            // The seen tally itself is deliberately not computed here from seenBeaconNumbers —
            // see CountModeRepository.recordCount's own doc for why that used to race when two
            // beacons committed close together.
            countModeRepository.recordCount(raceId, beaconNumber, bib, bibByBeaconNumber.size)
        } finally {
            inFlightCommits.remove(beaconNumber)
        }
    }

    /** "Eject" one seen beacon (TODO.md), or re-eject one already tagged WAS_EJECTED (TODO.md:
     *  the outstanding list's own "was-ejected" tag is itself a re-eject link) — [beaconNumber]
     *  must currently be seen (by this device OR a same-group peer, see [statusByBeacon]/
     *  [peerSeenOriginByBeacon] — TODO.md: "a bib must be ejectable no matter what phone did the
     *  initial detection") or WAS_EJECTED; a no-op otherwise. The UI only ever offers this from
     *  the seen list or a WAS_EJECTED tag, both behind their own confirm dialog. */
    fun ejectBeacon(beaconNumber: Int) {
        val unionSeen = seenBeaconNumbers + peerSeenOriginByBeacon.keys
        if (beaconNumber !in unionSeen && statusByBeacon[beaconNumber] != CountBeaconStatus.WAS_EJECTED) return
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch {
            countModeRepository.recordEject(
                raceId,
                beaconNumber,
                fallbackBibNumber = bibByBeaconNumber[beaconNumber],
                // Already verified above against the peer-merged union/status, so this device's
                // own lack of a local COUNT_SEEN row for a peer-only beacon is not itself a
                // reason to refuse — see recordEject's own doc.
                allowWithoutLocalRecord = true,
            )
        }
    }

    /** The operator's own "let this one back in" (TODO.md) — [beaconNumber] must currently be
     *  EJECTED (by this device OR a same-group peer — same TODO.md reasoning [ejectBeacon] itself
     *  documents, applied symmetrically to un-eject); a no-op otherwise (the UI only ever offers
     *  this via the outstanding list's own "ejected" link, behind its own confirm dialog). Once
     *  this returns, [beaconNumber] is a normal outstanding beacon again — the next detection
     *  recounts it exactly like one that was never ejected at all; only its WAS_EJECTED tag (see
     *  [CountBeaconStatus]) marks it as having been ejected before, until that happens. */
    fun unejectBeacon(beaconNumber: Int) {
        if (statusByBeacon[beaconNumber] != CountBeaconStatus.EJECTED) return
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch {
            countModeRepository.recordUneject(
                raceId,
                beaconNumber,
                fallbackBibNumber = bibByBeaconNumber[beaconNumber],
                allowWithoutLocalRecord = true,
            )
        }
    }

    /** "Stop" — see [CountModeUiState.canStopCountMode]'s own doc for why this is gated on the
     *  race's own current mode actually being Count. Writes a COUNT_STOPPED row first when
     *  beacons are still outstanding (see [HistoryAction.COUNT_STOPPED]'s own doc) — nothing to
     *  say if every issued beacon was already seen, since COUNT_COMPLETE already said so. */
    fun stopMode() {
        if (!_uiState.value.canStopCountMode) return
        val raceId = raceIdFlow.value ?: return
        val outstandingCount = _uiState.value.outstanding.size
        viewModelScope.launch {
            if (outstandingCount > 0) {
                countModeRepository.recordStopped(raceId, outstandingCount)
            }
            raceRepository.stopMode(raceId)
        }
    }

    /** "Reset" — TODO.md's own "the count screen needs a reset option, so it can be restarted"
     *  entry. Does what [stopMode] does first (same outstanding-count/gating/COUNT_STOPPED
     *  write), then additionally closes out the current Count segment via
     *  [RaceRepository.closeCurrentSegment] — the same generic, mode-agnostic RESET-row write
     *  every other mode's own Reset button already uses (see [HistoryAction.RESET]'s own doc),
     *  which the web app's own `buildSegmentView()`/`currentSegment()` already treats like any
     *  other Reset with zero server-side changes needed. Unlike [stopMode] alone — which leaves
     *  the segment open, so a beacon already seen still counts as seen if Count is started again
     *  later — this makes [CountModeRepository.observeCounts]/[CountModeRepository.recordCount]
     *  (both already HistoryFold.currentSegmentRows-scoped) see a genuinely empty tally next
     *  time, including every ejected/was-ejected tag (TODO.md: this is also now the one and only
     *  "start the count over" action — a dedicated "Eject All" used to exist alongside it, judged
     *  redundant once Reset already covers that case, and dropped). [CountModeScreen] itself must
     *  exit right after calling this (same as it already does for Stop) — this ViewModel keeps
     *  scanning for as long as it's alive, and would otherwise immediately re-detect and
     *  re-record the very beacons just reset. */
    fun resetCountMode() {
        if (!_uiState.value.canStopCountMode) return
        val raceId = raceIdFlow.value ?: return
        val outstandingCount = _uiState.value.outstanding.size
        viewModelScope.launch {
            if (outstandingCount > 0) {
                countModeRepository.recordStopped(raceId, outstandingCount)
            }
            raceRepository.stopMode(raceId)
            raceRepository.closeCurrentSegment(raceId, HistoryMode.COUNT)
        }
    }

    companion object {
        private const val TAG = "CountModeViewModel"

        // Same close-range policy cap BeaconScanEngine uses for its own checkpoint detection
        // (docs/beacon-detection-plan.md: "only close-range beacons should be counted") — start
        // identical, tune against real hardware like every other distance constant here.
        private const val COUNT_MAX_DISTANCE_METERS = 3.0

        // Purely cosmetic (TODO.md's own "highlighted" ask, not a detection/gating concern) — how
        // long since a beacon's last advertisement before it stops counting as "currently
        // visible," and how often that's re-evaluated. Generous relative to DEBOUNCE_GAP_MILLIS so
        // the highlight doesn't flicker off between individual advertisements.
        private const val VISIBLE_WINDOW_MILLIS = 15_000L
        private const val VISIBLE_RECOMPUTE_INTERVAL_MILLIS = 3_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                CountModeViewModel(
                    container.muleRepository,
                    container.countModeRepository,
                    container.progressRepository,
                    container.raceRepository,
                    container.settingsRepository,
                    container.peerGroupDetector,
                    container.peerSyncRepository,
                    container.peerSyncEngine,
                    container.bluetoothStateRepository,
                    container.locationStateRepository,
                    container.serverStatusRepository,
                )
            }
        }
    }
}
