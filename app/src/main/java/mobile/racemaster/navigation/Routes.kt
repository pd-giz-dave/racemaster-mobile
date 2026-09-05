package mobile.racemaster.navigation

import mobile.racemaster.data.settings.AppMode

object Routes {
    const val MODE_PICKER = "mode_picker"
    const val TIME_MODE = "time_mode"
    const val BIBS_MODE = "bibs_mode"
    const val CP_MODE = "cp_mode"
    const val MULE_MODE = "mule_mode"
    const val RACE_HISTORY = "race_history"
    const val RACE_HISTORY_DETAIL = "race_history_detail/{raceId}"
    const val MULE_SOURCE_DETAIL = "mule_source_detail/{raceLabel}/{sourceDeviceId}"
    const val RACE_DETAILS = "race_details/{mode}/{raceId}"
    const val HELP = "help"
    const val SETUP_DEVICE = "setup_device"
    const val NAME_DEVICE = "name_device"
    const val MULE_SERVER_SETUP = "mule_server_setup"

    // Bare path — every existing caller that just wants plain Options (Setup Device's own
    // Options button, the Mode Picker's Mule-off-routes-here flow) keeps navigating here
    // unchanged; fromMule defaults to false via SETUP_OPTIONS_PATTERN's own navArgument.
    const val SETUP_OPTIONS = "setup_options"

    // Registration-only pattern (see RacemasterNavHost's own composable(route = ...) for this) —
    // never navigate() to this string directly, use SETUP_OPTIONS or setupOptions() instead.
    const val SETUP_OPTIONS_PATTERN = "setup_options?fromMule={fromMule}"

    const val EDIT_SPLIT = "edit_split/{splitId}"
    const val EDIT_ENTRY = "edit_entry/{mode}/{entryId}"

    fun raceHistoryDetail(raceId: Long) = "race_history_detail/$raceId"

    // fromMule=true is what lets Options tell a Disable tap to pop all the way back to the Mode
    // Picker (past both Options and the now-pointless Mule Mode dashboard underneath it) — see
    // SetupOptionsScreen's own onMuleModeDisabled doc. Only MuleModeScreen's own "Options" button
    // navigates here; every other entry point (Setup Device's Options button, the Mode Picker's
    // own Mule-off routing) uses the plain SETUP_OPTIONS constant instead, where a Disable tap
    // stays put — reachable from any mode, disabling shouldn't yank the operator away from
    // whatever they were actually doing there.
    fun setupOptions(fromMule: Boolean) = "setup_options?fromMule=$fromMule"

    // A dedicated screen for editing a single Time Mode split's note — see EditSplitScreen's
    // own doc for why this replaced composing the editor inline over the live splits list.
    fun editSplit(splitId: Long) = "edit_split/$splitId"

    // Shared by Bibs and CP Mode — mode picks which repository the screen reads/writes through
    // and which action-type options it offers, same as RACE_DETAILS already does for its own
    // per-mode field set.
    fun editEntry(mode: AppMode, entryId: Long) = "edit_entry/${mode.name}/$entryId"

    // raceLabel is free-form user text (race names can contain spaces/punctuation), so it
    // must be URL-encoded to travel safely as a nav argument. sourceDeviceId disambiguates
    // between two different physical devices that happen to share the same raceLabel (see
    // PulledRecordDao.PulledSourceSummary's own doc) — encoded too even though today's ids are
    // URL-safe already, for consistency and future-proofing.
    fun muleSourceDetail(raceLabel: String, sourceDeviceId: String) =
        "mule_source_detail/${java.net.URLEncoder.encode(raceLabel, "UTF-8")}/" +
            java.net.URLEncoder.encode(sourceDeviceId, "UTF-8")

    // raceId of -1 is the "new race" sentinel — Nav Compose's Long arg type doesn't support
    // nullable values, so this avoids a second parallel route just for "no existing race".
    fun raceDetails(mode: AppMode, raceId: Long?) =
        "race_details/${mode.name}/${raceId ?: -1L}"
}