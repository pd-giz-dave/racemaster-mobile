package mobile.racemaster.data.repository

/**
 * A race is "in progress" if either its Time-mode stopwatch is running (started but not yet
 * stopped) or any bibs have been recorded — Bibs mode has no stop signal of its own, so
 * recorded entries alone mark it as still open. Used to block starting a new race (which
 * would orphan the in-progress one) from either mode's screen, and to summarize it on the
 * mode picker.
 */
fun isRaceInProgress(
    timeModeStartedAtMillis: Long?,
    timeModeStoppedAtMillis: Long?,
    hasBibEntries: Boolean,
): Boolean {
    val timeRunning = timeModeStartedAtMillis != null && timeModeStoppedAtMillis == null
    return timeRunning || hasBibEntries
}