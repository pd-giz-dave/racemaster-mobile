package mobile.racemaster.data.repository

import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.RaceEntity
import mobile.racemaster.data.settings.AppMode

/**
 * Whether any of these entries represent real, undoable activity — excludes the fixed Clock
 * marker (split #0), which [EntryLogModeEngine.undoMostRecent] refuses to ever undo. Used to
 * gate the Undo button (see BibsModeViewModel/CpModeViewModel's own `canUndo`), not for
 * "started"/"active" purposes — those are read directly off RaceEntity's own
 * bibsModeStartedAtMillis/cpModeStartedAtMillis/timeModeStartedAtMillis fields (see
 * [isRaceActive]/[isRaceInProgress]), which all three modes now track identically.
 */
fun List<HistoryLineEntity>.hasRealEntries(): Boolean = any { it.action != HistoryAction.CLOCK }

/**
 * A race is "in progress" if any mode's own stopwatch/segment is running (started but not yet
 * stopped) — all three shaped identically, one started/stopped timestamp pair per mode. Used to
 * block starting a new race (which would orphan the in-progress one) from any mode's screen,
 * and to summarize it on the mode picker.
 */
fun isRaceInProgress(
    timeModeStartedAtMillis: Long?,
    timeModeStoppedAtMillis: Long?,
    bibsModeStartedAtMillis: Long?,
    bibsModeStoppedAtMillis: Long?,
    cpModeStartedAtMillis: Long?,
    cpModeStoppedAtMillis: Long?,
): Boolean {
    val timeRunning = timeModeStartedAtMillis != null && timeModeStoppedAtMillis == null
    val bibsRunning = bibsModeStartedAtMillis != null && bibsModeStoppedAtMillis == null
    val cpRunning = cpModeStartedAtMillis != null && cpModeStoppedAtMillis == null
    return timeRunning || bibsRunning || cpRunning
}

/**
 * THE single definition of "active" for guarding a destructive/disruptive action against a
 * race — changing the device name, deleting the race itself — as opposed to [isRaceInProgress]
 * above, which answers a different question ("is it currently *running* right now", used only
 * to warn about starting a new race and to summarize on the mode picker). A race is active here
 * once any mode has been started in its *current segment* (since the last Reset) — deliberately
 * ignoring every mode's own "stopped" flag: a race that's merely been Stopped, not Reset, still
 * has live, un-finalized history sitting in this segment and must stay just as protected as one
 * that's still running. Only Reset (which clears the relevant started-at field back to null)
 * ever turns this back to false — same as a race that was never started at all, which is the
 * one case this is meant to *not* protect.
 */
fun isRaceActive(timeModeStartedAtMillis: Long?, bibsModeStartedAtMillis: Long?, cpModeStartedAtMillis: Long?): Boolean =
    timeModeStartedAtMillis != null || bibsModeStartedAtMillis != null || cpModeStartedAtMillis != null

/**
 * Which mode(s) are actually keeping [isRaceActive] true for this race — i.e. which of
 * Time/Bibs/CP still have a non-null startedAtMillis for the current segment. A race can be
 * active in a mode entirely different from whichever one the operator is currently looking at
 * (nothing stops switching AppMode without starting a new race — see RaceDetailsScreen's own
 * doc): Time Mode's own screen looks fully idle right after its own Stop+Reset, with no hint
 * that Bibs or CP was also started for this same race at some point and never separately reset.
 * Surfaced directly so RaceHistoryScreen's "can't be deleted" caption can name the actual
 * culprit instead of leaving the operator to guess which of three screens to go reset.
 */
fun activeModeLabels(timeModeStartedAtMillis: Long?, bibsModeStartedAtMillis: Long?, cpModeStartedAtMillis: Long?): List<String> =
    listOfNotNull(
        "Time Mode".takeIf { timeModeStartedAtMillis != null },
        "Bibs Mode".takeIf { bibsModeStartedAtMillis != null },
        "CP Mode".takeIf { cpModeStartedAtMillis != null },
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
    return isRaceActive(race.timeModeStartedAtMillis, race.bibsModeStartedAtMillis, race.cpModeStartedAtMillis)
}

/**
 * Whether [mode] has been started for [race]'s current segment — the per-mode mapping onto
 * whichever of RaceEntity's own timeModeStartedAtMillis/bibsModeStartedAtMillis/
 * cpModeStartedAtMillis fields is the right one to read, pulled out as its own pure function so
 * a caller (see RaceDetailsScreen's own field-locking use) never has to hand-roll that mapping
 * itself. [race] null (not yet loaded, or a dangling id) is never "started". Mule has no started
 * concept of its own — it never owns a race's fields the way Time/Bibs/CP each do.
 */
fun isModeStarted(mode: AppMode, race: RaceEntity?): Boolean = when (mode) {
    AppMode.BIBS -> race?.bibsModeStartedAtMillis != null
    AppMode.CP -> race?.cpModeStartedAtMillis != null
    AppMode.TIME -> race?.timeModeStartedAtMillis != null
    AppMode.MULE -> false
}
