package mobile.racemaster.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// Brand color from the RaceMaster web app's header (css/app.css --accent), used for the
// always-visible app banner so both apps read as the same product.
val RaceMasterGreen = Color(0xFF1A6E3C)

// A fixed (not theme-derived) synced/success green for Time/Bibs row text, paired with
// UnsyncedRed below for the not-yet-synced state — deliberately not tied to the dynamic
// colour scheme, since sync status is a universal signal an operator needs to read at a
// glance, not something that should shift with wallpaper-derived theming. RelayedOrange is the
// third, intermediate state (see LineSyncState) — relayed to a mule, but not yet confirmed
// reaching a genuine sink. An earlier value (0xFFEF6C00, a deep orange) sat hue-wise between
// red and green to match its in-between meaning, but that put it too close to UnsyncedRed to
// tell apart at a glance (confirmed in the field) — a gold/amber further from red on the wheel
// (and lighter than either neighbour) reads as clearly distinct from both instead.
val SyncedGreen = Color(0xFF2E7D32)
val RelayedOrange = Color(0xFFF9A825)
val UnsyncedRed = Color(0xFFC62828)

// Server reachability dot colors for the AppBanner — fixed, not theme-derived, same reasoning
// as SyncedGreen, and chosen to stay readable against the banner's solid RaceMasterGreen
// background specifically (unlike MaterialTheme.colorScheme.error, which isn't guaranteed to
// contrast against it).
val ServerOnlineGreen = Color(0xFF00FF00)
val ServerOfflineRed = Color(0xFFE57373)
val ServerInvalidAmber = Color(0xFFFFB74D)
val ServerPausedGrey = Color(0xFFBDBDBD)