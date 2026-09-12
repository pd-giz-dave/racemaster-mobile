package mobile.racemaster.data.repository

private val RACE_NAME_REGEX = Regex("^[a-zA-Z0-9-]+$")

/** A race's name becomes part of its stored label (see [buildRaceLabel]), which in turn is the
 *  server-side folder key this race's synced data lands under — restricted to letters, digits,
 *  and hyphens only so that stays a safe, unambiguous folder/URL-safe string regardless of what
 *  an operator types (no spaces, punctuation, or other characters that could collide, need
 *  escaping, or otherwise misbehave once it's a path segment). */
fun isValidRaceName(name: String): Boolean = RACE_NAME_REGEX.matches(name.trim())

/** A course name becomes part of the same per-course label [isValidRaceName] guards (see
 *  [buildRaceLabel]) once it's picked at Start time — same constraint, same regex, just a
 *  distinct entry point so a call site reads as "validating a course" rather than "validating
 *  a race name" even though the rule is identical. */
fun isValidCourseName(name: String): Boolean = RACE_NAME_REGEX.matches(name.trim())
