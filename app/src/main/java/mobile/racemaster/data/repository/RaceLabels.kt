package mobile.racemaster.data.repository

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds a race's stored label from a user-provided name and course, suffixed with the
 *  date (yy-MM-dd) — the date is always auto-derived, never user-entered. [course] is blank
 *  for a race that hasn't had a course picked yet (see RaceEntity.course's own doc — course is
 *  now chosen at Start time, not Race Details time), in which case its segment is omitted
 *  entirely rather than leaving a bare double-hyphen in the placeholder label. */
fun buildRaceLabel(name: String, course: String, timestampMillis: Long = System.currentTimeMillis()): String {
    val date = SimpleDateFormat("yy-MM-dd", Locale.getDefault()).format(Date(timestampMillis))
    val trimmedCourse = course.trim()
    return if (trimmedCourse.isEmpty()) "${name.trim()}-$date" else "${name.trim()}-$trimmedCourse-$date"
}
