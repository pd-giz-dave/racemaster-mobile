package mobile.racemaster.data.repository

private val CP_LOCATION_REGEX = Regex("^CP[1-9]\\d*(-.+)?$")

/** CP Mode's own required location format — "CP#" (# from 1 upwards) with an optional "-name"
 *  suffix (e.g. "CP1", "CP2-Bridge") — enforced only when editing race details from CP Mode
 *  (see RaceDetailsScreen's own mode-gated `canSave`); every other mode's location stays
 *  free-form. */
fun isValidCpLocation(location: String): Boolean = CP_LOCATION_REGEX.matches(location.trim())
