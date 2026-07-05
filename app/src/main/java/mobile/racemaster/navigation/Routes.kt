package mobile.racemaster.navigation

object Routes {
    const val MODE_PICKER = "mode_picker"
    const val TIME_MODE = "time_mode"
    const val BIBS_MODE = "bibs_mode"
    const val MULE_MODE = "mule_mode"
    const val RACE_HISTORY = "race_history"
    const val RACE_HISTORY_DETAIL = "race_history_detail/{raceId}"
    const val MULE_SOURCE_DETAIL = "mule_source_detail/{deviceRole}/{raceLabel}"
    const val HELP = "help"

    fun raceHistoryDetail(raceId: Long) = "race_history_detail/$raceId"

    // raceLabel is free-form user text (race names can contain spaces/punctuation), so it
    // must be URL-encoded to travel safely as a nav argument.
    fun muleSourceDetail(deviceRole: String, raceLabel: String) =
        "mule_source_detail/$deviceRole/${java.net.URLEncoder.encode(raceLabel, "UTF-8")}"
}