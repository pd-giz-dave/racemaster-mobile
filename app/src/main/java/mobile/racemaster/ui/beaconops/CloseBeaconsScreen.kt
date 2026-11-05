package mobile.racemaster.ui.beaconops

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mobile.racemaster.ui.components.ScreenTopBar
import mobile.racemaster.ui.help.HelpTopics

/** Post-race beacon tidy-up — TODO.md's "finish mode" (its "Race-day usage" section): connect to
 *  each beacon that was handed out, read its battery, power it off, and log what came back so
 *  anyone still holding one gets chased. Not yet implemented — this is a placeholder so
 *  [BeaconOperationsScreen]'s three operations are all reachable up front rather than the
 *  "Close" button appearing only once the feature itself exists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloseBeaconsScreen(
    onDone: () -> Unit,
    onHome: () -> Unit,
) {
    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text("Close Beacons") },
                onBack = onDone,
                onHome = onHome,
                helpTopic = HelpTopics.CloseBeacons,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Not yet implemented.\n\nThis will connect to each beacon handed out for the " +
                    "race, read its battery, power it off, and log what was returned.",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
