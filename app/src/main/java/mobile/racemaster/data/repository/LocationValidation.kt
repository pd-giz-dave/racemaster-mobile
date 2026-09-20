package mobile.racemaster.data.repository

private val CP_LOCATION_REGEX = Regex("^CP[1-9]\\d*(-.+)?$")

/** CP Mode's own required location format — "CP#" (# from 1 upwards) with an optional "-name"
 *  suffix (e.g. "CP1", "CP2-Bridge") — enforced at CpModeScreen's own Start button, and at
 *  RaceDetailsScreen's Relocate flow when CP Mode is among the currently-active modes (see
 *  RaceDetailsViewModel.cpModeActive); every other mode's location stays free-form. */
fun isValidCpLocation(location: String): Boolean = CP_LOCATION_REGEX.matches(location.trim())
