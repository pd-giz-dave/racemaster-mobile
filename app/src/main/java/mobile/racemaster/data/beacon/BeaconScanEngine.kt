package mobile.racemaster.data.beacon

import android.util.Log
import com.juul.kable.Advertisement
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.mule.ProgressRepository
import mobile.racemaster.data.repository.BibsModeRepository
import mobile.racemaster.data.repository.RaceRepository
import mobile.racemaster.data.repository.isValidCpLocation
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.util.Beeper
import mobile.racemaster.util.BibSpeaker

/**
 * Auto-records a Bibs Mode entry the moment a race beacon is reliably detected nearby — phase 2
 * of docs/beacon-detection-plan.md. A process-lifetime engine, not ViewModel-scoped, same
 * reasoning as [mobile.racemaster.data.mule.MuleSyncEngine]: it must keep working with the screen
 * backgrounded, for as long as the process is alive (kept alive via
 * [mobile.racemaster.data.mule.PeripheralSyncService]'s foreground service, which starts/stops it
 * alongside that engine).
 *
 * Deliberately **not** the plan doc's own original §4 sketch, which tracked peak RSSI across a
 * beacon's whole approach-and-recede and only committed once the beacon went silent, to time the
 * entry at closest-approach for photo-finish resolution. That precision isn't trustworthy yet —
 * nobody's analyzed a real RSSI-vs-distance curve well enough (see Beacons > Range Calibration) —
 * so this instead commits on the very first in-range reading (no confidence threshold — a former
 * 3-consecutive-readings debounce was dropped as not worth the detection lag, given the very next
 * paragraph already establishes a checkpoint entry is safety-oriented and freely editable, not
 * timing-critical — [mobile.racemaster.ui.countmode.CountModeViewModel] dropped its own identical
 * debounce for the same reason), and announces the bib aloud immediately, the same way a
 * manually-typed bib does (see [BibSpeaker]/[Beeper] usage in [commitDetection]). The row this
 * writes is a completely ordinary [BibsModeRepository.recordEntry] row — editable afterward like
 * any manual entry if the timing turns out to matter.
 *
 * **Checkpoints only, not the finish, for this phase** — timing precision doesn't matter at a
 * checkpoint, but it does at the finish, and the finish is also where runners are most likely to
 * mill about chatting right next to the phone, a much harder detection/de-duplication problem
 * than one runner lingering at a quieter checkpoint. [commitDetection] gates on
 * [isValidCpLocation] so this engine is a structural no-op at the finish.
 *
 * **Recording at a checkpoint is a one-shot, permanent thing per bib, not a re-arming
 * cooldown — per explicit direction.** A checkpoint's primary purpose is safety (knowing a
 * runner passed through at all), not timing, and a runner only ever passes a given checkpoint
 * once during a race — there's no "second lap" case to re-arm for the way a cooldown implies.
 *
 * **"Already seen" has no state of its own — it's read straight off the same bibs-recorded
 * list the operator already sees on the Bibs Mode screen, per explicit direction.** Rather than
 * this engine keeping a private "beacons already recorded" set, [recordedBibs] mirrors
 * [BibsModeRepository.observeCurrentSegmentEntries] (kept live by [watchRecordedBibs]) — the
 * exact same fold-collapsed, currently-visible rows the operator edits/undoes through the usual
 * Edit Entry flow. This gets both halves of "single source of truth" for free: undoing an
 * erroneous beacon-triggered entry makes that bib detectable again (its row drops out of the
 * fold the moment the undo lands), and a bib the operator typed in manually — never seen via
 * BLE at all — still suppresses its beacon exactly as if the beacon had triggered it, since
 * [commitDetection] checks the resolved bib, not the beacon number, against this list.
 */
class BeaconScanEngine(
    private val muleRepository: MuleRepository,
    private val progressRepository: ProgressRepository,
    private val settingsRepository: SettingsRepository,
    private val raceRepository: RaceRepository,
    private val bibsModeRepository: BibsModeRepository,
    private val bibSpeaker: BibSpeaker,
    private val beeper: Beeper,
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught exception in BeaconScanEngine — swallowed to avoid crashing the app", throwable)
    }
    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    // Guards against a duplicate recordEntry row for the same bib — commitDetection does several
    // suspend calls (settings/race reads) before its own recordedBibs check, so a burst of
    // 200ms-spaced advertisements from a not-yet-recorded beacon can re-enter commitDetection
    // before an earlier call's write has fed back through recordedBibs (which only updates once
    // that write's own Flow re-emits — strictly after the fact). Marked synchronously, before
    // commitDetection's own first suspension point, so only the first call in a burst gets
    // through; cleared once that attempt finishes (success or failure). Keyed by bib, matching
    // recordedBibs itself — same reasoning CountModeViewModel's own inFlightCommits documents.
    private val inFlightCommits = ConcurrentHashMap.newKeySet<Int>()

    // Mirrors BibsModeRepository.observeCurrentSegmentEntries's bib numbers for the active race
    // — see this class's own doc for why this, not a private "already recorded" set, is the
    // single source of truth for suppression. Read from commitDetection; kept live by
    // watchRecordedBibs. Volatile rather than a StateFlow/MutableState: this is read from a
    // plain (non-suspend) context and there's exactly one writer, so a bare volatile field is
    // the simplest correct thing — no listeners of its own to notify.
    @Volatile
    private var recordedBibs: Set<Int> = emptySet()

    @Volatile
    private var started = false

    /** Idempotent, mirrors [mobile.racemaster.data.mule.MuleSyncEngine.start] — called once from
     *  [mobile.racemaster.data.mule.PeripheralSyncService.onCreate], right after that engine's own
     *  start(). */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        engineScope.launch { collectAdvertisements() }
        engineScope.launch { watchRecordedBibs() }
    }

    /** Keeps [recordedBibs] mirroring the active race's own currently-visible Bibs Mode entries
     *  (see this class's own doc). `collectLatest` on the race ID — rather than folding it into
     *  one combined flow — so switching races (or there being none) cancels the previous race's
     *  entries-collection outright and starts clean, the same reasoning
     *  [mobile.racemaster.data.mule.ServerStatusRepository.startPolling] already uses for its own
     *  per-URL loop. Also clears [inFlightCommits] on every race change, so a guard entry never
     *  carries over from a previous race. */
    private suspend fun watchRecordedBibs() {
        settingsRepository.activeRaceId.collectLatest { raceId ->
            inFlightCommits.clear()
            if (raceId == null) {
                recordedBibs = emptySet()
                return@collectLatest
            }
            bibsModeRepository.observeCurrentSegmentEntries(raceId).collect { entries ->
                recordedBibs = entries.mapNotNull { it.bibNumber }.toSet()
            }
        }
    }

    /** Tears down the scan collector and releases [beeper] — the counterpart to [start], called
     *  from [mobile.racemaster.data.mule.PeripheralSyncService.onDestroy] right after
     *  [mobile.racemaster.data.mule.MuleSyncEngine.stop]. */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        engineScope.cancel()
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
        beeper.release()
    }

    /** Collects the one shared scan [MuleRepository.scanForAdvertisements] exposes (fans out the
     *  same native scan Mule Mode's own scanning uses, rather than opening a second one). Unlike
     *  [mobile.racemaster.ui.beaconidentify.IdentifyFobViewModel]'s identical collect-and-catch
     *  shape, this engine runs for an entire multi-hour race rather than one screen's lifetime, so
     *  a transient upstream failure retries instead of silently ending detection for the rest of
     *  the race — the `while (isActive)` here is this specific launched coroutine's own
     *  liveness, same as [mobile.racemaster.data.mule.MuleSyncEngine]'s loops use. */
    private suspend fun CoroutineScope.collectAdvertisements() {
        while (isActive) {
            try {
                muleRepository.scanForAdvertisements().collect { advertisement -> handleAdvertisement(advertisement) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "beacon scan failed — retrying", e)
                delay(SCAN_RETRY_DELAY)
            }
        }
    }

    private fun handleAdvertisement(advertisement: Advertisement) {
        val beacon = BeaconScanDecoder.decodeFromAdvertisement(advertisement) ?: return
        if (beacon.estimatedDistanceMeters > BEACON_MAX_DISTANCE_METERS) return
        engineScope.launch { commitDetection(beacon.beaconNumber, beacon.rssi) }
    }

    /** Writes the row and gives the same feedback a manual 3-digit bib entry gives — see
     *  [mobile.racemaster.ui.bibsmode.BibsModeViewModel.onDigit]'s identical speak-vs-beep
     *  branch. */
    private suspend fun commitDetection(beaconNumber: Int, rssi: Int) {
        if (settingsRepository.appMode.first() != AppMode.BIBS) return
        val raceId = settingsRepository.activeRaceId.first() ?: return
        val race = raceRepository.getRace(raceId) ?: return
        if (race.bibsModeStartedAtMillis == null) return
        if (!isValidCpLocation(race.location)) return
        val bib = progressRepository.getBibForBeacon(raceId, beaconNumber) ?: return
        // The actual suppression check — see this class's own doc. Already-recorded, whether
        // by an earlier beacon detection or a manual entry, is a no-op, not an error.
        if (bib in recordedBibs) return
        // See inFlightCommits' own doc — must run before this function's next suspension point,
        // so it's ordered against any other commitDetection call for this same bib already queued.
        if (!inFlightCommits.add(bib)) return
        try {
            val action = settingsRepository.bibsAutoSaveAction.first()
            bibsModeRepository.recordEntry(raceId, action, bib, note = "beacon:$beaconNumber rssi=$rssi")
            if (settingsRepository.speakBibsAloud.first()) {
                bibSpeaker.speak("%03d".format(bib), settingsRepository.bibSpeechVoice.first())
            } else {
                beeper.beep()
            }
        } finally {
            inFlightCommits.remove(bib)
        }
    }

    companion object {
        private const val TAG = "BeaconScanEngine"

        // The finish-line policy cap from docs/beacon-detection-plan.md's 2026-11-05 update
        // ("not so loose (<3m)") — applied everywhere for now since this phase only ever commits
        // at checkpoints anyway (see commitDetection's isValidCpLocation gate). Promoted to a
        // per-race setting in phase 3 (beaconMaxDistanceMeters).
        private const val BEACON_MAX_DISTANCE_METERS = 3.0

        private val SCAN_RETRY_DELAY = 5.seconds
    }
}
