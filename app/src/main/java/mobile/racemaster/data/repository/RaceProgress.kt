package mobile.racemaster.data.repository

import kotlinx.coroutines.flow.first
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity

/**
 * Whether any of these Bibs-mode entries represent real activity. Every Bibs race always has
 * a Clock marker (split #0) from the moment it's created, so `isNotEmpty()` alone would never
 * go back to false — this excludes that fixed marker.
 */
fun List<HistoryLineEntity>.hasRealEntries(): Boolean = any { it.action != HistoryAction.CLOCK }

/**
 * A race is "in progress" if either its Time-mode stopwatch is running (started but not yet
 * stopped) or Bibs mode has real recorded activity and hasn't itself been stopped. Used to
 * block starting a new race (which would orphan the in-progress one) from either mode's
 * screen, and to summarize it on the mode picker.
 */
fun isRaceInProgress(
    timeModeStartedAtMillis: Long?,
    timeModeStoppedAtMillis: Long?,
    bibsHasRealEntries: Boolean,
    bibsModeStoppedAtMillis: Long?,
): Boolean {
    val timeRunning = timeModeStartedAtMillis != null && timeModeStoppedAtMillis == null
    val bibsRunning = bibsHasRealEntries && bibsModeStoppedAtMillis == null
    return timeRunning || bibsRunning
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
 * an empty segment) ever turns this back to false — same as a race that was never started at
 * all, which is the one case this is meant to *not* protect.
 */
fun isRaceActive(timeModeStartedAtMillis: Long?, bibsHasRealEntries: Boolean): Boolean =
    timeModeStartedAtMillis != null || bibsHasRealEntries

/**
 * One-shot [isRaceActive] check for [raceId], resolving its own current Time-mode field and
 * current-segment Bibs entries first. False for a race that no longer exists (nothing to
 * protect from an action against a dangling id). This is the one function every "is this race
 * active" guard in the app should call — see NameDeviceViewModel.hasActiveRace/save() and
 * RaceRepository.deleteRace for its current call sites.
 */
suspend fun isRaceCurrentlyActive(
    raceId: Long,
    raceRepository: RaceRepository,
    bibsModeRepository: BibsModeRepository,
): Boolean {
    val race = raceRepository.getRace(raceId) ?: return false
    val bibsEntries = bibsModeRepository.observeCurrentSegmentEntries(raceId).first()
    return isRaceActive(race.timeModeStartedAtMillis, bibsEntries.hasRealEntries())
}
