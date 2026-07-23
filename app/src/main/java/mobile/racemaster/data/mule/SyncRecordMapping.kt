package mobile.racemaster.data.mule

import java.util.Locale
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode

/**
 * Maps a unified history line into the wire/server record shape. [raceStartedAtMillis] is the
 * race's `timeModeStartedAtMillis` (a Time-mode row's own t=0 reference) — `time` is formatted
 * elapsed-since-start to match the racemaster server's existing finisher time convention
 * (confirmed against real data, e.g. `"00:26:51"`), only for Time-mode rows; a Bibs-mode row
 * has no stopwatch of its own, so its `time` stays null and it relies purely on
 * `timestampMillis`, the raw wall-clock instant the record was created. No device name is
 * attached here — the caller already knows (and separately threads through) which device this
 * batch of records belongs to; see [SyncRecord]'s own doc for why that's not repeated per line.
 */
fun HistoryLineEntity.toSyncRecord(raceStartedAtMillis: Long?): SyncRecord {
    val time = if (mode == HistoryMode.TIME) {
        val elapsedMillis = raceStartedAtMillis?.let { timestampMillis - it } ?: 0L
        formatElapsedAsClock(elapsedMillis)
    } else {
        null
    }
    return SyncRecord(
        recordUuid = recordUuid,
        action = action.toServerAction(),
        number = bibNumber,
        time = time,
        splitNumber = splitNumber,
        lineNumber = lineNumber,
        refLineNumber = refLineNumber,
        note = note,
        timestampMillis = timestampMillis,
    )
}

private fun HistoryAction.toServerAction(): String = when (this) {
    // Time Mode
    HistoryAction.SPLIT -> "Split"
    // Bibs Mode
    HistoryAction.FINISH -> "Finish"
    HistoryAction.RETIRE -> "DNF"
    HistoryAction.IGNORE -> "Ignore"
    HistoryAction.SENIORS -> "Seniors"
    HistoryAction.JUNIORS -> "Juniors"
    HistoryAction.MALE -> "Male"
    HistoryAction.FEMALE -> "Female"
    HistoryAction.CLOCK -> "Clock"
    // Shared
    HistoryAction.START -> "Start"
    HistoryAction.STOP -> "Stop"
    HistoryAction.RESET -> "Reset"
    HistoryAction.UNDO -> "Undo"
}

/**
 * Reconstructs the original [HistoryAction] from a pulled [SyncRecord]'s own wire fields — the
 * exact inverse of [toServerAction] above, kept right beside it so the two can never quietly
 * drift apart. This is what lets Mule Source Detail (a pulled record) render an action label
 * via the very same [mobile.racemaster.ui.bibsmode.displayName] a local race's own history
 * (Race History) uses, instead of showing the raw wire string — which isn't the same wording
 * (e.g. "DNF" on the wire vs. this app's own "Retire"). Every wire value maps to exactly one
 * [HistoryAction] — "Split" and "Finish" are no longer ambiguous with each other now that a
 * Time split is sent as its own honest "Split" (see [toServerAction]), not disguised as
 * "Finish".
 */
fun SyncRecord.toHistoryAction(): HistoryAction = when (action) {
    "Split" -> HistoryAction.SPLIT
    "Finish" -> HistoryAction.FINISH
    "Start" -> HistoryAction.START
    "DNF" -> HistoryAction.RETIRE
    "Ignore" -> HistoryAction.IGNORE
    "Seniors" -> HistoryAction.SENIORS
    "Juniors" -> HistoryAction.JUNIORS
    "Male" -> HistoryAction.MALE
    "Female" -> HistoryAction.FEMALE
    "Clock" -> HistoryAction.CLOCK
    "Stop" -> HistoryAction.STOP
    "Reset" -> HistoryAction.RESET
    "Undo" -> HistoryAction.UNDO
    // An unrecognized wire value - should not get here
    else -> HistoryAction.IGNORE
}

// Centisecond precision, matching util/ElapsedTimeFormat.kt's on-screen convention exactly —
// previously truncated to whole seconds here, discarding precision the app already tracks and
// displays internally. No server-side change needed: both server.js and the companion web
// app's finishers.js treat this field as an opaque passthrough string. NOTE: if a future
// integration ever feeds this value into finishers.js's own parseFinishTime() (which splits on
// any non-digit run, including '.', and rejects more than 3 numeric parts), a trailing ".CC"
// would push it to 4 parts and be rejected — not a concern for any integration that exists
// today, but worth knowing before wiring one up.
private fun formatElapsedAsClock(elapsedMillis: Long): String {
    val totalMillis = elapsedMillis.coerceAtLeast(0)
    val hours = totalMillis / 3_600_000
    val minutes = (totalMillis % 3_600_000) / 60_000
    val seconds = (totalMillis % 60_000) / 1000
    val centis = (totalMillis % 1000) / 10
    return String.format(Locale.US, "%02d:%02d:%02d.%02d", hours, minutes, seconds, centis)
}
