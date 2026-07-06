package mobile.racemaster.navigation

import mobile.racemaster.data.settings.AppMode

object Routes {
    const val MODE_PICKER = "mode_picker"
    const val TIME_MODE = "time_mode"
    const val BIBS_MODE = "bibs_mode"
    const val MULE_MODE = "mule_mode"
    const val RACE_HISTORY = "race_history"
    const val RACE_HISTORY_DETAIL = "race_history_detail/{raceId}"
    const val MULE_SOURCE_DETAIL = "mule_source_detail/{deviceRole}/{raceLabel}"
    const val RACE_DETAILS = "race_details/{mode}/{raceId}"
    const val HELP = "help"

    fun raceHistoryDetail(raceId: Long) = "race_history_detail/$raceId"

    // raceLabel is free-form user text (race names can contain spaces/punctuation), so it
    // must be URL-encoded to travel safely as a nav argument.
    fun muleSourceDetail(deviceRole: String, raceLabel: String) =
        "mule_source_detail/$deviceRole/${java.net.URLEncoder.encode(raceLabel, "UTF-8")}"

    // raceId of -1 is the "new race" sentinel — Nav Compose's Long arg type doesn't support
    // nullable values, so this avoids a second parallel route just for "no existing race".
    fun raceDetails(mode: AppMode, raceId: Long?) =
        "race_details/${mode.name}/${raceId ?: -1L}"
}