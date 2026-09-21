package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mobile.racemaster.util.withClickSound

// Material3's default TopAppBar height (64.dp) leaves generous padding above/below a single
// line of title text — these screens' headers are otherwise plain (single-line title, no
// subtitle since the race name moved into the screen body), so a shorter bar reads as tight
// rather than cramped. Matches AppBanner's own 48.dp height for a visually consistent stack.
val CompactTopAppBarHeight: Dp = 48.dp

// The race label used to double up as this bar's second title line — moved out into the
// screen body (as a "Race name: …" line, above "Next: …") so this stays a single-line title
// and the bar itself shrinks to just that line's height, instead of always reserving room
// for two.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeScreenTopBar(
    title: String,
    thisRaceEnabled: Boolean,
    onThisRace: () -> Unit,
    onChangeMode: () -> Unit,
) {
    // The persistent AppBanner (above this bar) already reserves space for the status
    // bar, so this bar must not also apply it — otherwise the two stack and leave a gap.
    // No "New Race" button any more — a device now records against exactly one race for its
    // whole lifetime (set up via Setup Device > Setup Race); starting over means going back
    // there, not swapping races mid-mode. See Race History's own "Resume" action for the one
    // remaining case that used to route through here (an accidentally-stopped race).
    TopAppBar(
        title = { Text(title) },
        actions = {
            // Relocate stays enabled even once the race has stopped — editing a typo in the
            // name/location shouldn't require never having finished logging. "Relocate" (not
            // "This Race") since that screen's own new capability — editing location mid-race,
            // which is what this button is mostly reached for now — is the more common reason to
            // tap it than a pre-race rename.
            TextButton(onClick = withClickSound(onThisRace), enabled = thisRaceEnabled) { Text("Relocate") }
            TextButton(onClick = withClickSound(onChangeMode)) { Text("Back") }
        },
        expandedHeight = CompactTopAppBarHeight,
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}
