package mobile.racemaster.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full human-readable wall-clock timestamp, e.g. for reviewing a past race's raw record
 *  times — as opposed to [formatTimeOfDay]'s terser "HH:mm" used for live per-screen captions. */
fun formatWallClock(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd:HH:mm:ss", Locale.getDefault()).format(Date(millis))

/** Terse "HH:mm" used for live per-screen captions ([mobile.racemaster.ui.components.SyncStatusLine]'s
 *  "last synced HH:mm", Mule Mode's connection-health "since HH:mm") — as opposed to
 *  [formatWallClock]'s full date+time for reviewing a past race. [locale] is a parameter
 *  (rather than [Locale.getDefault] internally, like [formatWallClock] above) specifically so a
 *  caller can source it from `LocalConfiguration.current.locales[0]`, which — unlike
 *  `Locale.getDefault()` — Compose actually recomposes on if the user changes their system
 *  locale mid-session. */
fun formatTimeOfDay(millis: Long, locale: Locale): String =
    SimpleDateFormat("HH:mm", locale).format(Date(millis))

/** Short "yy-MM-dd" date — used only to keep Setup Race's own "unknown" fallback name (a device
 *  that hasn't been given a real name yet — see SetupRaceScreen's own doc) distinguishable across
 *  devices/days, since unlike a real name (typed, inherited from the server, or following its
 *  own convention — see buildRaceLabel's own doc for why those are never modified), "unknown" on
 *  its own would collide identically for every such device. */
fun formatShortDate(millis: Long): String =
    SimpleDateFormat("yy-MM-dd", Locale.getDefault()).format(Date(millis))
