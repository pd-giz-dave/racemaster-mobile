package mobile.racemaster.data.repository

import mobile.racemaster.data.db.entity.BibEntryEntity
import mobile.racemaster.data.db.entity.BibEntryType

/**
 * Whether any of these Bibs-mode entries represent real activity. Every Bibs race always has
 * a Clock marker (split #0) from the moment it's created, so `isNotEmpty()` alone would never
 * go back to false — this excludes that fixed marker.
 */
fun List<BibEntryEntity>.hasRealEntries(): Boolean = any { it.type != BibEntryType.CLOCK }

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
