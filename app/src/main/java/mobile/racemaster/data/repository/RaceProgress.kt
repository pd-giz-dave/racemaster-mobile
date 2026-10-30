package mobile.racemaster.data.repository

import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.NON_ENTRY_ACTIONS
import mobile.racemaster.data.settings.AppMode

/**
 * Whether any of these entries represent real, undoable activity — excludes the fixed Clock
 * marker (split #0) and any LOCATION marker, neither of which [EntryLogModeEngine.undoMostRecent]
 * (or TimeModeRepository's own copy) will ever target as an Undo. Used to gate the Undo button
 * (see BibsModeViewModel's and TimeModeViewModel's own `canUndo`), not for "started"/"active"
 * purposes — those are read directly off RaceEntity's own bibsModeStartedAtMillis/
 * timeModeStartedAtMillis fields (see [isRaceActive]/[isRaceInProgress]), which both modes now
 * track identically.
 */
fun List<HistoryLineEntity>.hasRealEntries(): Boolean = any { it.action !in NON_ENTRY_ACTIONS }

/**
 * THE single definition of "active"/"in progress" for a race — used both to guard a
 * destructive/disruptive action (changing the device name, deleting the race itself) and to
 * block starting a new race / summarize the mode picker. A race is active here once any mode has
 * been started in its *current segment* (since the last Reset) — there's no separate "running vs.
 * merely stopped" distinction any more (see HistoryAction's own doc for why Stop was dropped):
 * once started, a mode stays active until Reset, which is the only thing that ever clears the
 * relevant started-at field back to null — same as a race that was never started at all, which
 * is the one case this is meant to *not* protect.
 */
fun isRaceActive(timeModeStartedAtMillis: Long?, bibsModeStartedAtMillis: Long?): Boolean =
    timeModeStartedAtMillis != null || bibsModeStartedAtMillis != null

/**
 * Which mode(s) are actually keeping [isRaceActive] true for this race — i.e. which of Time/Bibs
 * still have a non-null startedAtMillis for the current segment. A race can be active in a mode
 * entirely different from whichever one the operator is currently looking at (nothing stops
 * switching AppMode without starting a new race — see RaceDetailsScreen's own doc): Time Mode's
 * own screen looks fully idle right after its own Stop+Reset, with no hint that Bibs was also
 * started for this same race at some point and never separately reset. Surfaced directly so
 * RaceHistoryScreen's "can't be deleted" caption can name the actual culprit instead of leaving
 * the operator to guess which of two screens to go reset.
 */
fun activeModeLabels(timeModeStartedAtMillis: Long?, bibsModeStartedAtMillis: Long?): List<String> =
    listOfNotNull(
        "Time Mode".takeIf { timeModeStartedAtMillis != null },
        "Bibs Mode".takeIf { bibsModeStartedAtMillis != null },
    )

/**
 * One-shot [isRaceActive] check for [raceId]. False for a race that no longer exists (nothing
 * to protect from an action against a dangling id). This is the one function every "is this
 * race active" guard in the app should call — see NameDeviceViewModel.hasActiveRace/save() and
 * RaceRepository.deleteRace for its current call sites. Needs no other repository — every
 * mode's own "active" signal is a plain RaceEntity field, fetched here in one query.
 */
suspend fun isRaceCurrentlyActive(raceId: Long, raceRepository: RaceRepository): Boolean {
    val race = raceRepository.getRace(raceId) ?: return false
    return isRaceActive(race.timeModeStartedAtMillis, race.bibsModeStartedAtMillis)
}

// The one place an AppMode's recording-mode name is spelled out, so the wording can't drift
// between call sites (the Mode Picker's own status text and active-mode labels, editing dialogs).
fun AppMode.displayName(): String = when (this) {
    AppMode.TIME -> "Time Mode"
    AppMode.BIBS -> "Bibs Mode"
}
