package mobile.racemaster.data.repository

/** Builds a race's stored label from its user-provided name — verbatim, just trimmed. No date
 *  (or course — that concept was dropped in phase 1) is ever appended: the name typed into Setup
 *  Race, autofilled from the server via Scan Server, or carrying its own Seniors/Juniors-style
 *  suffix by convention, is the race's identity exactly as given, not something this app
 *  augments. (This used to also suffix an auto-derived date, e.g. "pontesbury-26-09-14" — that
 *  behavior is gone: a name already inherited from the server or following its own convention
 *  must never be silently modified.) */
fun buildRaceLabel(name: String): String = name.trim()

// buildRaceLabel's own inverse for the one case that needs it: a BLE-adopted race identity (see
// RaceRepository.adoptRaceIdentity) may still reference an older, date-suffixed label (e.g. from
// a device or web app not yet on this convention, or a race created before this change) — this
// stays a defensive, harmless strip: returns the label unchanged if it doesn't end in a date at
// all, which is now the common case for anything newly created.
private val TRAILING_DATE = Regex("-\\d{2}-\\d{2}-\\d{2}$")
fun raceNameFromLabel(raceLabel: String): String = raceLabel.replace(TRAILING_DATE, "")
