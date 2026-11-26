package mobile.racemaster.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.BuildConfig
import mobile.racemaster.R
import mobile.racemaster.data.mule.ServerStatus
import mobile.racemaster.ui.theme.RaceMasterGreen
import mobile.racemaster.ui.theme.ServerInvalidAmber
import mobile.racemaster.ui.theme.ServerOfflineRed
import mobile.racemaster.ui.theme.ServerOnlineGreen
import mobile.racemaster.ui.theme.ServerPausedGrey

/**
 * Always-visible app banner shown above every screen, styled to match the RaceMaster web
 * app's header (solid brand green, white icon + wordmark), plus a small dot + label on the
 * right reporting whether the configured Racemaster server (see Mule Mode's Setup Server) is
 * currently reachable. Composed once outside RacemasterNavHost's back stack (see
 * MainActivity), so the underlying poll loop naturally runs for the whole app session
 * regardless of which screen is showing.
 *
 * Deliberately just the dot + label, nothing more — a "last seen" timestamp was tried here
 * too, appended next to the label, but pushed the title into wrapping onto two lines on a real
 * device and read as clutter permanently pinned above every screen. That feedback now lives
 * per-mode instead — see ui/components/ServerStatusLine.kt.
 */
@Composable
fun AppBanner(
    modifier: Modifier = Modifier,
    viewModel: AppBannerViewModel = viewModel(factory = AppBannerViewModel.Factory),
) {
    val serverStatus by viewModel.serverStatus.collectAsStateWithLifecycle()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(RaceMasterGreen)
            .statusBarsPadding()
            .height(48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_racemaster_banner),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
        Text(
            buildAnnotatedString {
                append("RM Mobile ")
                withStyle(SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal)) {
                    append("v${BuildConfig.VERSION_NAME}")
                }
            },
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ServerStatusIndicator(serverStatus.status, serverStatus.username)
    }
}

// Blank while UNKNOWN (no server URL configured yet — see Mule Mode's Setup Server form) so
// the banner doesn't nag about connectivity before there's even a server to be connected to.
//
// ONLINE names the actual logged-in account (e.g. "Online — mercia") rather than a bare
// "Online" — a field report: an operator read "Online" and reasonably assumed it meant "logged
// in as the account I meant to use," when in fact the saved session could be a different,
// unintended account (e.g. a debug build's auto-filled default) that this device never
// explicitly logged out of. Naming the account makes that mismatch visible at a glance instead
// of only surfacing once a feature scoped to the wrong account quietly comes up empty.
@Composable
private fun ServerStatusIndicator(status: ServerStatus, username: String?) {
    val indicator = when (status) {
        ServerStatus.UNKNOWN -> null
        ServerStatus.ONLINE -> ServerOnlineGreen to (username?.let { "Online — $it" } ?: "Online")
        ServerStatus.OFFLINE -> ServerOfflineRed to "Offline"
        ServerStatus.INVALID -> ServerInvalidAmber to "Invalid server"
        // Reachable, genuinely a Racemaster server, but no login has ever been saved on this
        // device for it — see ServerStatus.NOT_LOGGED_IN's own doc. Distinct from UNAUTHORIZED
        // below (a login *was* saved, the server just rejected it) since the two are easy to
        // conflate otherwise, and that conflation is exactly what caused the field report above.
        ServerStatus.NOT_LOGGED_IN -> ServerInvalidAmber to "Not logged in"
        // Reachable, genuinely a Racemaster server, but this device's saved login is no longer
        // accepted — see ServerStatus.UNAUTHORIZED's own doc. Same amber tier as INVALID (both
        // are "needs operator attention", not an outage) but a distinct label, since the fix is
        // different: log back in, not fix the server URL.
        ServerStatus.UNAUTHORIZED -> ServerInvalidAmber to "Login expired"
        // Server sync deliberately turned off from Mule Mode — see AppBannerViewModel's own doc.
        ServerStatus.PAUSED -> ServerPausedGrey to "Paused"
    } ?: return
    val (dotColor, label) = indicator
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("●", color = dotColor, fontSize = 14.sp)
        Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
