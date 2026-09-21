package mobile.racemaster.data.repository

import mobile.racemaster.data.settings.AppMode

private val CP_LOCATION_REGEX = Regex("^CP[1-9]\\d*(-.+)?$")

/** CP Mode's own required location format — "CP#" (# from 1 upwards) with an optional "-name"
 *  suffix (e.g. "CP1", "CP2-Bridge") — enforced at CpModeScreen's own Start button, and at
 *  RaceDetailsScreen's Relocate flow when CP Mode is among the currently-active modes (see
 *  RaceDetailsViewModel.cpModeActive); every other mode's location stays free-form. */
fun isValidCpLocation(location: String): Boolean = CP_LOCATION_REGEX.matches(location.trim())

/** Location validation against the chosen mode — the one shared check Setup Race and Relocate
 *  both need now that mode is picked alongside location on the same screen (see
 *  [isValidCpLocation]'s own doc). Only CP imposes a real format constraint; Time/Bibs accept
 *  any non-blank free text. [mode] null (nothing chosen yet) is never valid — Setup Race's own
 *  Save button stays disabled until a mode is picked regardless of location. */
fun isValidLocationForMode(location: String, mode: AppMode?): Boolean = when (mode) {
    null -> false
    AppMode.CP -> isValidCpLocation(location)
    AppMode.TIME, AppMode.BIBS -> location.isNotBlank()
}
