package mobile.racemaster.data.repository

import kotlinx.coroutines.flow.first
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.RaceEntity

/**
 * Whether any of these Bibs-mode entries represent real activity. Every Bibs race always has
 * a Clock marker (split #0) from the moment it's created, so `isNotEmpty()` alone would never
 * go back to false — this excludes that fixed marker.
 */
fun List<HistoryLineEntity>.hasRealEntries(): Boolean = any { it.action != HistoryAction.CLOCK }

/**
 * A race is "in progress" if its Time-mode stopwatch is running (started but not yet stopped),
 * or Bibs mode has real recorded activity and hasn't itself been stopped, or CP mode has been
 * started and hasn't itself been stopped. Used to block starting a new race (which would orphan
 * the in-progress one) from any mode's screen, and to summarize it on the mode picker.
 * [cpModeStartedAtMillis]/[cpModeStoppedAtMillis] mirror the Time-mode params exactly rather
 * than Bibs' entries-derived check — see [RaceEntity.cpModeStartedAtMillis]'s own doc for why
 * CP tracks "started" as a plain timestamp instead.
 */
fun isRaceInProgress(
    timeModeStartedAtMillis: Long?,
    timeModeStoppedAtMillis: Long?,
    bibsHasRealEntries: Boolean,
    bibsModeStoppedAtMillis: Long?,
    cpModeStartedAtMillis: Long?,
    cpModeStoppedAtMillis: Long?,
): Boolean {
    val timeRunning = timeModeStartedAtMillis != null && timeModeStoppedAtMillis == null
    val bibsRunning = bibsHasRealEntries && bibsModeStoppedAtMillis == null
    val cpRunning = cpModeStartedAtMillis != null && cpModeStoppedAtMillis == null
    return timeRunning || bibsRunning || cpRunning
}

/**
 * THE single definition of "active" for guarding a destructive/disruptive action against a
 * race — changing the device name, deleting the race itself — as opposed to [isRaceInProgress]
 * above, which answers a different question ("is it currently *running* right now", used only
 * to warn about starting a new race and to summarize on the mode picker). A race is active here
 * once either mode has recorded anything in its *current segment* (since the last Reset,
 * inclusive of a Clock-only Bibs segment not counting — see [hasRealEntries]) — deliberately
 * ignoring both modes' own "stopped" flags: a race that's merely been Stopped, not Reset, still
 * has live, un-finalized history sitting in this segment and must stay just as protected as one
 * that's still running. Only Reset (which clears timeModeStartedAtMillis and folds Bibs back to
 * an empty segment, and for CP also clears cpModeStartedAtMillis) ever turns this back to false
 * — same as a race that was never started at all, which is the one case this is meant to *not*
 * protect. [cpModeStartedAtMillis] mirrors [timeModeStartedAtMillis] rather than Bibs' own
 * entries-derived check — see [RaceEntity.cpModeStartedAtMillis]'s own doc for why.
 */
fun isRaceActive(timeModeStartedAtMillis: Long?, bibsHasRealEntries: Boolean, cpModeStartedAtMillis: Long?): Boolean =
    timeModeStartedAtMillis != null || bibsHasRealEntries || cpModeStartedAtMillis != null

/**
 * One-shot [isRaceActive] check for [raceId], resolving its own current Time/CP fields and
 * current-segment Bibs entries first. False for a race that no longer exists (nothing to
 * protect from an action against a dangling id). This is the one function every "is this race
 * active" guard in the app should call — see NameDeviceViewModel.hasActiveRace/save() and
 * RaceRepository.deleteRace for its current call sites. Needs no CpModeRepository — CP's own
 * "active" signal is a plain RaceEntity field already fetched here, not a repository call.
 */
suspend fun isRaceCurrentlyActive(
    raceId: Long,
    raceRepository: RaceRepository,
    bibsModeRepository: BibsModeRepository,
): Boolean {
    val race = raceRepository.getRace(raceId) ?: return false
    val bibsEntries = bibsModeRepository.observeCurrentSegmentEntries(raceId).first()
    return isRaceActive(race.timeModeStartedAtMillis, bibsEntries.hasRealEntries(), race.cpModeStartedAtMillis)
}
