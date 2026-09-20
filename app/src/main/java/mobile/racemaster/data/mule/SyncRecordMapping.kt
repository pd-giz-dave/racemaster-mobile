package mobile.racemaster.data.mule

import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.util.formatElapsedSplitTime

/**
 * Maps a unified history line into the wire/server record shape. [raceStartedAtMillis] is the
 * race's `timeModeStartedAtMillis` (a Time-mode row's own t=0 reference) — `splitTime` is
 * formatted elapsed-since-start to match the racemaster server's existing finisher time
 * convention (confirmed against real data, e.g. `"00:26:51"`), only for Time-mode rows; a
 * Bibs-mode row has no stopwatch of its own, so its `splitTime` stays null and it relies purely
 * on `timestampMillis`, the raw wall-clock instant the record was created. `bibNumber` is the
 * mirror image — see [SyncRecord]'s own doc for why every Bibs row sends a non-null string
 * (the bib itself, or `"n/a"` for an action with no bib of its own) while a Time row always
 * sends null. [location] is per-call rather than a flat race-wide constant precisely because a
 * race can now span more than one — see [withResolvedLocations] above, which every caller should
 * resolve each row's own correct value through rather than passing one fixed string for a whole
 * race's history — see [SyncRecord]'s own doc for why location is repeated per line at all
 * rather than sent once. No device name is attached here — the caller already knows (and
 * separately threads through) which device this batch of records belongs to; see [SyncRecord]'s
 * own doc for why that's not repeated per line either.
 *
 * MODE_START is the one Time-mode row whose `splitTime` deliberately isn't a real elapsed time:
 * it's written at the exact same instant as the real Start marker right after it (see
 * TimeModeRepository.startStopwatch), so a naive elapsed calculation would also come out
 * "00:00:00" — indistinguishable on the wire from a genuine Start. It sends `"n/a"` instead,
 * mirroring the same sentinel Bibs/CP's own non-bib markers already use for `bibNumber` — a
 * consumer already told to skip a blank/`"n/a"` split time (the same convention this mirrors)
 * naturally never mistakes this boundary marker for a real split.
 */
/**
 * Resolves each of [this] race history's own rows (already lineNumber-ascending — see
 * getHistorySinceLineNumber's own doc) to whichever location was actually in effect when it was
 * recorded, rather than the race's flat *current* location every row used to get stamped with —
 * see HistoryAction.LOCATION's own doc for why a relocation mid-race means that's no longer
 * accurate for anything recorded before the move. Walks once, tracking a running "current
 * location" that starts at [initialLocation] (the race's own location as of its very first
 * record — a race is never created with a blank one) and updates to a LOCATION row's own `note`
 * the moment one is encountered; every row (the LOCATION row itself included, per its own doc)
 * is paired with whatever that running value is at its own position. No filtering by `mode` is
 * needed here: relocateActiveModes writes one LOCATION row per active mode, all carrying the
 * same new location, so any one of them updates the running value correctly regardless of which
 * mode's own segment a caller ultimately cares about.
 */
fun List<HistoryLineEntity>.withResolvedLocations(initialLocation: String): List<Pair<HistoryLineEntity, String>> {
    var current = initialLocation
    return map { row ->
        if (row.action == HistoryAction.LOCATION) row.note?.let { current = it }
        row to current
    }
}

fun HistoryLineEntity.toSyncRecord(raceStartedAtMillis: Long?, location: String = "Finish"): SyncRecord {
    val splitTime = if (mode == HistoryMode.TIME && action == HistoryAction.MODE_START) {
        "n/a"
    } else if (mode == HistoryMode.TIME) {
        val elapsedMillis = raceStartedAtMillis?.let { timestampMillis - it } ?: 0L
        formatElapsedSplitTime(elapsedMillis)
    } else {
        null
    }
    val wireBibNumber = if (mode == HistoryMode.BIBS || mode == HistoryMode.CP) bibNumber?.toString() ?: "n/a" else null
    return SyncRecord(
        recordUuid = recordUuid,
        action = action.toServerAction(),
        bibNumber = wireBibNumber,
        splitTime = splitTime,
        location = location,
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
    // See HistoryAction.SETUP's own doc — the only wire content this marker carries that
    // matters is `location` (set on every SyncRecord regardless of action), but it still needs
    // its own honest, distinct action string like every other row.
    HistoryAction.SETUP -> "Setup"
    // See HistoryAction.LOCATION's own doc — this marker's own new-location value travels via
    // `note` (not this action string), same convention SETUP already established.
    HistoryAction.LOCATION -> "Location"
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
    "Setup" -> HistoryAction.SETUP
    "Location" -> HistoryAction.LOCATION
    // An unrecognized wire value - should not get here
    else -> HistoryAction.IGNORE
}
