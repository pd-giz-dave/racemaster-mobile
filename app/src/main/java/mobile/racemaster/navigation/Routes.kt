package mobile.racemaster.navigation

object Routes {
    const val MODE_PICKER = "mode_picker"
    const val TIME_MODE = "time_mode"
    const val BIBS_MODE = "bibs_mode"
    const val MULE_MODE = "mule_mode"
    const val RACE_HISTORY = "race_history"
    const val RACE_HISTORY_DETAIL = "race_history_detail/{raceId}"
    const val HELP = "help"

    fun raceHistoryDetail(raceId: Long) = "race_history_detail/$raceId"
}