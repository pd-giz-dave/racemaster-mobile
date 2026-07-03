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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeScreenTopBar(
    title: String,
    raceLabel: String,
    newRaceEnabled: Boolean,
    onNewRace: (name: String) -> Unit,
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
            TextButton(onClick = { showNewRaceDialog = true }, enabled = newRaceEnabled) { Text("New Race") }
            TextButton(onClick = onChangeMode) { Text("Mode") }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
    )

    if (showNewRaceDialog) {
        NewRaceDialog(
            onConfirm = { name ->
                onNewRace(name)
                showNewRaceDialog = false
            },
            onDismiss = { showNewRaceDialog = false },
        )
    }
}