package mobile.racemaster.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import java.text.SimpleDateFormat
import java.util.Date
import mobile.racemaster.data.mule.ServerStatus
import mobile.racemaster.data.mule.ServerStatusState

/** Shared "Server: Online (seen HH:MM)" line — server connectivity matters to every mode that
 *  might push its own data, not just Mule (see ServerStatusRepository's own doc: the
 *  underlying poll loop already runs app-wide, driven once from AppBannerViewModel, regardless
 *  of which screen is showing). Blank while UNKNOWN — no server configured yet, same reasoning
 *  as the app banner's own original indicator. [ServerStatusState.lastOnlineAtMillis] is when
 *  the server was actually last confirmed reachable, not merely last checked (see that field's
 *  own doc for why those differ) — judged the same way as any other "last X" time in this app:
 *  if it looks old, something's stopped, no separate warning state of its own needed.
 *
 *  This used to live in the always-visible AppBanner instead, appended next to its status dot
 *  — reverted after it pushed the banner's own title into wrapping onto two lines on a real
 *  device (confirmed in the field) and read as clutter permanently pinned above every screen.
 *  Shown per-mode instead: Time/Bibs/CP as another header line, Mule Mode directly above its
 *  own "Last push to server" line. */
@Composable
fun ServerStatusLine(state: ServerStatusState, modifier: Modifier = Modifier) {
    val (color, label) = when (state.status) {
        ServerStatus.UNKNOWN -> return
        ServerStatus.ONLINE -> MaterialTheme.colorScheme.primary to "Online"
        ServerStatus.OFFLINE -> MaterialTheme.colorScheme.error to "Offline"
        ServerStatus.INVALID -> MaterialTheme.colorScheme.error to "Invalid server"
        // Server sync deliberately turned off from Mule Mode — see AppBannerViewModel's own doc.
        ServerStatus.PAUSED -> MaterialTheme.colorScheme.onSurfaceVariant to "Paused"
    }
    // Read via LocalConfiguration rather than Locale.getDefault() directly — the latter isn't
    // observable by Compose, so this composable wouldn't recompose if the user changes their
    // system locale mid-session (same reasoning as SyncStatusLine's own identical pattern).
    val locale = LocalConfiguration.current.locales[0]
    val lastSeenText = state.lastOnlineAtMillis?.let { SimpleDateFormat("HH:mm", locale).format(Date(it)) } ?: "never"
    Text(
        "Server: $label (seen $lastSeenText)",
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier,
    )
}
