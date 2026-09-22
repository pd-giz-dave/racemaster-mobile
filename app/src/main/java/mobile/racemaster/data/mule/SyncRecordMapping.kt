package mobile.racemaster.data.mule

import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.util.elapsedSeconds

/**
 * Maps a unified history line into the wire/server record shape. [raceStartedAtMillis] is the
 * race's `timeModeStartedAtMillis` (a Time-mode row's own t=0 reference) — `splitTime` is
 * elapsed-seconds-since-start, only for a real Time-mode row; a Bibs-mode row has no stopwatch
 * of its own, so its `splitTime` stays null and it relies purely on `timestampMillis`, the raw
 * wall-clock instant the record was created. `bibNumber` is a straight passthrough of the local
 * column — already null for every non-`BIB_REQUIRED_ACTIONS` row, including every Time-mode row
 * (see [SyncRecord]'s own doc for why neither field carries any discriminator meaning any more).
 * No device name is attached here — the caller already knows (and separately threads through)
 * which device this batch of records belongs to; see [SyncRecord]'s own doc for why that's not
 * repeated per line either — location is the same story: it travels only via `note` on
 * `HistoryAction.LOCATION` rows, which this function passes through unchanged like any other
 * row's `note`, rather than as a field of its own here.
 *
 * MODE_START's `splitTime` is deliberately always null, regardless of mode — it's written at the
 * exact same instant as the real Start marker right after it (see
 * TimeModeRepository.startStopwatch), so a naive elapsed calculation would come out "00:00:00",
 * indistinguishable from a genuine Start; its own explicit mode declaration already lives in
 * `note` instead (see `AppMode.wireName()`), so there's nothing left for `splitTime` to signal.
 */
fun HistoryLineEntity.toSyncRecord(raceStartedAtMillis: Long?): SyncRecord {
    val splitTime = if (mode == HistoryMode.TIME && action != HistoryAction.MODE_START) {
        val elapsedMillis = raceStartedAtMillis?.let { timestampMillis - it } ?: 0L
        elapsedSeconds(elapsedMillis).toInt()
    } else {
        null
    }
    return SyncRecord(
        action = action.toServerAction(),
        bibNumber = bibNumber,
        splitTime = splitTime,
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
    // CP Mode
    HistoryAction.PASS -> "Pass"
    // Shared
    HistoryAction.START -> "Start"
    HistoryAction.STOP -> "Stop"
    HistoryAction.RESET -> "Reset"
    HistoryAction.UNDO -> "Undo"
    // Deliberately distinct from "Start" on the wire — the whole point is letting the web app
    // tell this boundary marker apart from a mode's own real Start/Clock row (see
    // HistoryAction.MODE_START's own doc), even though both show as "Start" in this app's UI.
    HistoryAction.MODE_START -> "ModeStart"
    // See HistoryAction.LOCATION's own doc — this marker's own new-location value travels via
    // `note`, not this action string.
    HistoryAction.LOCATION -> "Location"
    // See HistoryAction.NEW_RACE's own doc — this is the wire signal every recipient
    // (server, a Mule's own pull cache, the web app) must recognize and act on: discard
    // whatever's already held for this exact race+device identity before applying what follows.
    HistoryAction.NEW_RACE -> "NewRace"
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
    "Pass" -> HistoryAction.PASS
    "Stop" -> HistoryAction.STOP
    "Reset" -> HistoryAction.RESET
    "Undo" -> HistoryAction.UNDO
    "ModeStart" -> HistoryAction.MODE_START
    "Location" -> HistoryAction.LOCATION
    "NewRace" -> HistoryAction.NEW_RACE
    // An unrecognized wire value - should not get here
    else -> HistoryAction.IGNORE
}
