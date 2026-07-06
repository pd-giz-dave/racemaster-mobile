package mobile.racemaster.data.repository

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds a race's stored label from a user-provided name and course, suffixed with the
 *  date (yy-MM-dd) — the date is always auto-derived, never user-entered. */
fun buildRaceLabel(name: String, course: String, timestampMillis: Long = System.currentTimeMillis()): String =
    "${name.trim()}-${course.trim()}-${SimpleDateFormat("yy-MM-dd", Locale.getDefault()).format(Date(timestampMillis))}"
