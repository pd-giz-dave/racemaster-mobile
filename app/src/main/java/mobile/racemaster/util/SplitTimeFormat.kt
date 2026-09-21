package mobile.racemaster.util

/**
 * A recorded split's elapsed time, rounded to the nearest whole second — not truncated, so
 * "1:29.6" reads as the 1:30 an operator would naturally call it, not 1:29. Deliberately
 * distinct from [mobile.racemaster.ui.timemode.formatElapsed]'s own centisecond precision,
 * which stays reserved for the *live* ticking clock — once a split is logged, the extra
 * precision is no longer wanted, only whole seconds. The wire's own SyncRecord.splitTime sends
 * this raw value directly (see SyncRecordMapping.toSyncRecord) rather than a formatted string —
 * [formatElapsedSplitTime] below is purely a *local display* helper built on top of it.
 */
fun elapsedSeconds(millis: Long): Long = (millis.coerceAtLeast(0) + 500) / 1000

/**
 * A recorded split's elapsed time as "HH:MM:SS" — shown on every recorded split (Time Mode's
 * own list, its Undo/edit descriptions, Race History). Local display only; never sent on the
 * wire (see [elapsedSeconds]'s own doc).
 */
fun formatElapsedSplitTime(millis: Long): String {
    val roundedSeconds = elapsedSeconds(millis)
    val hours = roundedSeconds / 3600
    val minutes = (roundedSeconds % 3600) / 60
    val seconds = roundedSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}
