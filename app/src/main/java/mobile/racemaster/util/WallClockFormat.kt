package mobile.racemaster.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full human-readable wall-clock timestamp, e.g. for reviewing a past race's raw record
 *  times — as opposed to [mobile.racemaster.ui.components.SyncStatusLine]'s terser "HH:mm"
 *  used for the live per-screen sync caption. */
fun formatWallClock(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd:HH:mm:ss", Locale.getDefault()).format(Date(millis))
