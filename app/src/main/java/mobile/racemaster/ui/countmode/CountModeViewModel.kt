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
import mobile.racemaster.data.beacon.BeaconScanDecoder
import mobile.racemaster.data.beacon.DetectionTracking
import mobile.racemaster.data.beacon.shouldCommit
import mobile.racemaster.data.beacon.updatedDetectionTracking
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.mule.ProgressRepository
import mobile.racemaster.data.repository.CountBeaconStatus
import mobile.racemaster.data.repository.CountModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.parseBeaconCount
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import mobile.racemaster.ui.deviceNameFlow

data class OutstandingBeaconUi(
    val bibNumber: Int,
    val beaconNumber: Int,
    val status: CountBeaconStatus? = null,
    val currentlyVisible: Boolean = false,
)

data class SeenBeaconUi(val bibNumber: Int?, val beaconNumber: Int)

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
    // canStopCountMode additionally requires the race's own CURRENT mode to actually be Count —
    // this screen is only reached via Routes.COUNT_MODE now (Setup Race), which is itself only
    // navigated to once the race's mode already is Count (see
    // mobile.racemaster.ui.beaconops.BeaconOperationsScreen's own doc for why the Beacons hub's
    // old standing "Count" shortcut, which could reach this screen while some other mode was
    // active, was dropped), so this is effectively always true in practice now — kept as a
    // defensive guard (same reasoning CloseModeUiState.canStopCloseMode documents) rather than
    // removed outright.
    val raceId: Long? = null,
    val canStopCountMode: Boolean = false,
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
 * Reuses [BeaconScanEngine]'s own already-extracted, already-tested pure debounce functions
 * ([updatedDetectionTracking]/[shouldCommit]) rather than that engine itself — this is a sibling,
 * not a shared instance, since the dedup key (beacon number, not resolved bib — a beacon can be
 * counted before its bib pairing is known, per the doc's own "a count of unique beacons seen"),
 * the gating mode, and the write target (COUNT_SEEN rows via [CountModeRepository], not Bibs
 * entries) all differ from Bibs' own checkpoint detection.
 *
 * Reached only via Setup Race's own Count mode (`Routes.COUNT_MODE`) — unlike
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
) : ViewModel() {
    private val _uiState = MutableStateFlow(CountModeUiState())
    val uiState: StateFlow<CountModeUiState> = _uiState.asStateFlow()

    private val raceIdFlow: StateFlow<Long?> = settingsRepository.activeRaceId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Echoed under the title (TODO.md) — same "Device name: ..." convention every other mode's
    // own RaceProgressSummary already shows; Count has no such shared header, so this screen
    // reads it directly.
    val deviceName: StateFlow<String?> = deviceNameFlow(settingsRepository)

    private val detectionTracking = ConcurrentHashMap<Int, DetectionTracking>()

    // Guards against a duplicate COUNT_SEEN row for the same beacon — now that
    // REQUIRED_CONSECUTIVE_READINGS is 1, every single advertisement from an already-committing
    // beacon re-triggers shouldCommit instantly, re-entering commitDetection before the first
    // call's db.withTransaction has completed and fed back through seenBeaconNumbers (which only
    // updates once that write's own Flow re-emits — strictly after the fact, not synchronously).
    // Marked synchronously, before commitDetection's own first suspension point, so a whole burst
    // of 200ms-spaced advertisements arriving while one commit is still in flight only ever lets
    // the first one through; cleared once that attempt finishes (success or failure) so a
    // legitimate later recount (e.g. after an eject/uneject round-trip) is never blocked by it.
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
    // countModeRepository.observeStatusByBeacon — see that function's own doc for why this can't
    // be derived from seenBeaconNumbers' own (fold-collapsed) rows. commitDetection's own "stay
    // excluded until explicitly let back in" gate reads this map directly; WAS_EJECTED is purely
    // informational there (gates nothing).
    @Volatile
    private var statusByBeacon: Map<Int, CountBeaconStatus> = emptyMap()

    init {
        viewModelScope.launch { collectAdvertisements() }
        viewModelScope.launch { tickCurrentlyVisible() }
        viewModelScope.launch {
            raceIdFlow.flatMapLatest { raceId ->
                if (raceId == null) flowOf(null) else raceRepository.observeRace(raceId).map { it?.mode }
            }.collectLatest { mode ->
                _uiState.update { it.copy(canStopCountMode = mode == AppMode.COUNT.name) }
            }
        }
        viewModelScope.launch {
            raceIdFlow.collectLatest { raceId ->
                detectionTracking.clear()
                lastDetectedAtMillis.clear()
                inFlightCommits.clear()
                _uiState.update { it.copy(raceId = raceId) }
                if (raceId == null) {
                    bibByBeaconNumber = emptyMap()
                    seenBeaconNumbers = emptySet()
                    statusByBeacon = emptyMap()
                    _uiState.update {
                        it.copy(outstanding = emptyList(), seen = emptyList(), totalIssued = 0, uniqueBeaconsSeen = 0)
                    }
                    return@collectLatest
                }
                combine(
                    progressRepository.observeIssuedBeacons(raceId),
                    countModeRepository.observeCounts(raceId),
                    countModeRepository.observeStatusByBeacon(raceId),
                ) { issued, rows, status -> Triple(issued, rows, status) }.collect { (issued, rows, status) ->
                    bibByBeaconNumber = issued.associate { it.beaconNumber!! to it.bibNumber }
                    seenBeaconNumbers = rows.filter { it.action == HistoryAction.COUNT_SEEN }
                        .mapNotNull { parseBeaconCount(it.note) }.toSet()
                    statusByBeacon = status
                    _uiState.update { it.copy(totalIssued = issued.size) }
                    recomputeOutstanding()
                }
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

    private fun recomputeOutstanding() {
        val now = System.currentTimeMillis()
        val outstanding = bibByBeaconNumber.entries
            .filter { (beaconNumber, _) -> beaconNumber !in seenBeaconNumbers }
            .map { (beaconNumber, bibNumber) ->
                OutstandingBeaconUi(
                    bibNumber = bibNumber,
                    beaconNumber = beaconNumber,
                    status = statusByBeacon[beaconNumber],
                    currentlyVisible = (lastDetectedAtMillis[beaconNumber]?.let { now - it <= VISIBLE_WINDOW_MILLIS }) ?: false,
                )
            }
            .sortedBy { it.bibNumber }
        val seen = seenBeaconNumbers
            .map { beaconNumber -> SeenBeaconUi(bibByBeaconNumber[beaconNumber], beaconNumber) }
            .sortedBy { it.bibNumber ?: Int.MAX_VALUE }
        _uiState.update {
            it.copy(outstanding = outstanding, seen = seen, uniqueBeaconsSeen = seenBeaconNumbers.size)
        }
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
        val now = System.currentTimeMillis()
        lastDetectedAtMillis[beaconNumber] = now
        val updated = detectionTracking.compute(beaconNumber) { _, existing -> updatedDetectionTracking(existing, now, DEBOUNCE_GAP_MILLIS) }!!
        if (shouldCommit(updated, REQUIRED_CONSECUTIVE_READINGS)) {
            detectionTracking.remove(beaconNumber)
            viewModelScope.launch { commitDetection(beaconNumber) }
        }
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
     *  must currently be seen or WAS_EJECTED; a no-op otherwise. The UI only ever offers this
     *  from the seen list or a WAS_EJECTED tag, both behind their own confirm dialog. */
    fun ejectBeacon(beaconNumber: Int) {
        if (beaconNumber !in seenBeaconNumbers && statusByBeacon[beaconNumber] != CountBeaconStatus.WAS_EJECTED) return
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { countModeRepository.recordEject(raceId, beaconNumber) }
    }

    /** The operator's own "let this one back in" (TODO.md) — [beaconNumber] must currently be
     *  EJECTED; a no-op otherwise (the UI only ever offers this via the outstanding list's own
     *  "ejected" link, behind its own confirm dialog). Once this returns, [beaconNumber] is a
     *  normal outstanding beacon again — the next detection recounts it exactly like one that was
     *  never ejected at all; only its WAS_EJECTED tag (see [CountBeaconStatus]) marks it as having
     *  been ejected before, until that happens. */
    fun unejectBeacon(beaconNumber: Int) {
        if (statusByBeacon[beaconNumber] != CountBeaconStatus.EJECTED) return
        val raceId = raceIdFlow.value ?: return
        viewModelScope.launch { countModeRepository.recordUneject(raceId, beaconNumber) }
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
        private const val DEBOUNCE_GAP_MILLIS = 5_000L

        // 1, not BeaconScanEngine's own 3 (direct feedback: Count's only question is "did this
        // runner turn up at the start," not a timed/photo-finish split — unlike a checkpoint
        // entry, a spurious single reading costs nothing worse than counting someone a few
        // seconds before their actual walk-up, so there's nothing here worth trading detection
        // latency for). Combined with the shared scanner's own SCAN_MODE_BALANCED duty cycle
        // (see CloseModeViewModel's own STALE_AFTER_MILLIS doc for why that's not continuous),
        // requiring 3 consecutive readings per beacon made this screen's "seen" list visibly slow
        // to populate once several beacons were all close together and competing for the same
        // scan windows — any single in-range reading now commits immediately instead.
        private const val REQUIRED_CONSECUTIVE_READINGS = 1

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
                )
            }
        }
    }
}
