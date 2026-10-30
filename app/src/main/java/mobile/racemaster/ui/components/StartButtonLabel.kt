package mobile.racemaster.ui.components

/** [prefix] with the race's current location appended ("@<location>"), so a button reads exactly
 *  where it's about to record into — the one place this "<prefix> @<location>" format is spelled
 *  out, shared by Bibs/Time Mode's own big mode button (prefix "START" — see BibsModeScreen/
 *  TimeModeScreen) and Mode Picker's own Start button (prefix the mode's displayName — see
 *  ModePickerScreen). Bare [prefix], no "@", while [location] hasn't loaded yet/isn't known. */
fun startButtonLabel(prefix: String, location: String): String =
    if (location.isBlank()) prefix else "$prefix @$location"
