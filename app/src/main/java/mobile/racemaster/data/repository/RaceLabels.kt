package mobile.racemaster.data.repository

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds a race's stored label from a user-provided name, suffixed with the date (yy-MM-dd). */
fun buildRaceLabel(name: String, timestampMillis: Long = System.currentTimeMillis()): String =
    name.trim() + "-" + SimpleDateFormat("yy-MM-dd", Locale.getDefault()).format(Date(timestampMillis))