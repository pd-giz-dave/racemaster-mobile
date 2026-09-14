package mobile.racemaster.data.repository

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds a race's stored label from a user-provided name and course, suffixed with the
 *  date (yy-MM-dd) — the date is always auto-derived, never user-entered. [course] is always
 *  blank now (see RaceEntity.course's own doc — the course concept was dropped), in which case
 *  its segment is omitted entirely rather than leaving a bare double-hyphen in the label. */
fun buildRaceLabel(name: String, course: String, timestampMillis: Long = System.currentTimeMillis()): String {
    val date = SimpleDateFormat("yy-MM-dd", Locale.getDefault()).format(Date(timestampMillis))
    val trimmedCourse = course.trim()
    return if (trimmedCourse.isEmpty()) "${name.trim()}-$date" else "${name.trim()}-$trimmedCourse-$date"
}

// buildRaceLabel's own inverse for the one case that needs it: Setup Race's online branch picks
// a race off the server by its raceLabel (e.g. "pontesbury-seniors-26-09-14", the web app's own
// course-suffixed convention — see racemaster's js/mobile-files-shared.js deriveRaceLabel), and
// this device's own RaceEntity.name needs to become "pontesbury-seniors" — the whole label minus
// its own trailing date, not the server's separate (course-less) raceName field, since the
// picked course is only ever encoded in the label's suffix, never sent as its own field. Returns
// the label unchanged if it doesn't end in a date at all (defensive — every label this app or the
// web app builds always does).
private val TRAILING_DATE = Regex("-\\d{2}-\\d{2}-\\d{2}$")
fun raceNameFromLabel(raceLabel: String): String = raceLabel.replace(TRAILING_DATE, "")
