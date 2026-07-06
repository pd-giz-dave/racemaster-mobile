package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import mobile.racemaster.util.withClickSound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeScreenTopBar(
    title: String,
    raceLabel: String,
    newRaceEnabled: Boolean,
    thisRaceEnabled: Boolean,
    onNewRace: () -> Unit,
    onThisRace: () -> Unit,
    onChangeMode: () -> Unit,
) {
    // The persistent AppBanner (above this bar) already reserves space for the status
    // bar, so this bar must not also apply it — otherwise the two stack and leave a gap.
    TopAppBar(
        title = {
            Column {
                Text(title)
                Text(raceLabel, style = MaterialTheme.typography.bodySmall)
            }
        },
        actions = {
            // This Race stays enabled even once the race has stopped — editing a typo in the
            // name/course shouldn't require never having finished logging.
            TextButton(onClick = withClickSound(onThisRace), enabled = thisRaceEnabled) { Text("This Race") }
            TextButton(onClick = withClickSound(onNewRace), enabled = newRaceEnabled) { Text("New Race") }
            TextButton(onClick = withClickSound(onChangeMode)) { Text("Mode") }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}
