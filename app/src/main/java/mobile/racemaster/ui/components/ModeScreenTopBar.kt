package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import mobile.racemaster.util.withClickSound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeScreenTopBar(
    title: String,
    raceLabel: String,
    newRaceEnabled: Boolean,
    newRaceDialog: @Composable (onDismiss: () -> Unit) -> Unit,
    onChangeMode: () -> Unit,
) {
    var showNewRaceDialog by remember { mutableStateOf(false) }

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
            TextButton(onClick = withClickSound { showNewRaceDialog = true }, enabled = newRaceEnabled) { Text("New Race") }
            TextButton(onClick = withClickSound(onChangeMode)) { Text("Mode") }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
    )

    // The top bar just owns whether the dialog is showing — it doesn't need to know what
    // kind of dialog (plain name vs. Bibs' name+range fields) each mode uses.
    if (showNewRaceDialog) {
        newRaceDialog { showNewRaceDialog = false }
    }
}
