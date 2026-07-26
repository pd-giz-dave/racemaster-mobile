package mobile.racemaster.util

/**
 * A recorded split's elapsed time as "HH:MM:SS", rounded to the nearest whole second — shown
 * on every recorded split (Time Mode's own list, its Undo/edit descriptions, Race History) and
 * sent as the racemaster server's own splitTime field (see SyncRecordMapping.toSyncRecord).
 * Deliberately distinct from [mobile.racemaster.ui.timemode.formatElapsed]'s own centisecond
 * precision, which stays reserved for the *live* ticking clock — once a split is logged, the
 * extra precision is no longer wanted on-screen or on the wire, only whole seconds. Rounds
 * (not truncates) so "1:29.6" reads as the 1:30 an operator would naturally call it, not 1:29.
 */
fun formatElapsedSplitTime(millis: Long): String {
    val roundedSeconds = (millis.coerceAtLeast(0) + 500) / 1000
    val hours = roundedSeconds / 3600
    val minutes = (roundedSeconds % 3600) / 60
    val seconds = roundedSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}
