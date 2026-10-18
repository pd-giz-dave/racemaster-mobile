package mobile.racemaster.data.repository

import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.settings.AppMode

private val CP_LOCATION_REGEX = Regex("^CP[1-9]\\d*(-.+)?$")

/** CP location format — "CP#" (# from 1 upwards) with an optional "-name" suffix (e.g. "CP1",
 *  "CP2-Bridge"). Enforced for Bibs mode (see [isValidLocationForMode]) on any location that
 *  isn't literally "Start"/"Finish" — Bibs is location-aware (TODO.md: "A rationalisation"), so
 *  every location it records at must be one of these three recognized shapes. Time mode's
 *  location stays free-form ("as now"). */
fun isValidCpLocation(location: String): Boolean = CP_LOCATION_REGEX.matches(location.trim())

/** Location validation against the chosen mode — the one shared check Setup Race and Relocate
 *  both need now that mode is picked alongside location on the same screen. Bibs enforces a real
 *  format constraint — the literal "Start"/"Finish" labels that drive [defaultActionForLocation],
 *  or else a CP-shaped location (see [isValidCpLocation]'s own doc). Time accepts any non-blank
 *  free text. [mode] null (nothing chosen yet) is never valid — Setup Race's own Save button
 *  stays disabled until a mode is picked regardless of location. */
fun isValidLocationForMode(location: String, mode: AppMode?): Boolean = when (mode) {
    null -> false
    AppMode.BIBS -> {
        val trimmed = location.trim()
        trimmed.equals("Start", ignoreCase = true) || trimmed.equals("Finish", ignoreCase = true) || isValidCpLocation(location)
    }
    AppMode.TIME -> location.isNotBlank()
}

/** Bibs mode's location-driven default action (TODO.md: "A rationalisation") — "Start"/"Finish"
 *  map onto their own matching action, and anything else (guaranteed CP-shaped by
 *  [isValidLocationForMode]'s own Bibs branch) maps onto Pass, CP's own equivalent of Finish.
 *  Called once, from RaceRepository.recordModeStart, to seed a freshly sensible default the
 *  moment location changes — the operator's own later picks via Bibs' default-action selector
 *  simply overwrite the same setting afterward, same as always. */
fun defaultActionForLocation(location: String): HistoryAction {
    val trimmed = location.trim()
    return when {
        trimmed.equals("Start", ignoreCase = true) -> HistoryAction.START
        trimmed.equals("Finish", ignoreCase = true) -> HistoryAction.FINISH
        else -> HistoryAction.PASS
    }
}

/** Whether [action] makes sense for Bibs Mode to record at [location] at all — not just as the
 *  default, but as a selectable option anywhere in BibsModeViewModel's two pickers (the compact
 *  default-action selector and Event's dialog, see ui/bibsmode/HistoryActionLabels.kt). At a CPn
 *  location only Pass/Retire/Ignore are offered — Start/Finish and the age/gender markers don't
 *  belong mid-course. At Start or Finish, everything is offered except Pass — it's CP's own
 *  equivalent of Finish, so it has no business appearing at either literal endpoint. A blank or
 *  not-yet-CP-shaped location (a transient uiState before a race is selected, or before Bibs' own
 *  stricter [isValidLocationForMode] check has had a chance to run) is permissive rather than
 *  hiding every option — Bibs Mode's actual running location is always one of the two concrete
 *  branches above by the time this matters. */
fun isBibsActionAllowedAtLocation(action: HistoryAction, location: String): Boolean {
    val trimmed = location.trim()
    return when {
        trimmed.equals("Start", ignoreCase = true) || trimmed.equals("Finish", ignoreCase = true) ->
            action != HistoryAction.PASS
        isValidCpLocation(location) ->
            action == HistoryAction.PASS || action == HistoryAction.RETIRE || action == HistoryAction.IGNORE
        else -> true
    }
}
