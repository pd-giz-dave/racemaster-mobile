package mobile.racemaster.ui.modepicker

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.ui.components.NewBibsRaceDialog
import mobile.racemaster.ui.components.NewRaceDialog
import mobile.racemaster.util.withClickSound

@Composable
fun ModePickerScreen(
    onModeSelected: (AppMode) -> Unit,
    onReviewPastRaces: () -> Unit,
    onHelp: () -> Unit,
    viewModel: ModePickerViewModel = viewModel(factory = ModePickerViewModel.Factory),
) {
    val hasActiveRace by viewModel.hasActiveRace.collectAsStateWithLifecycle()
    val activeRaceStatus by viewModel.activeRaceStatus.collectAsStateWithLifecycle()
    var pendingMode by remember { mutableStateOf<AppMode?>(null) }

    fun handleModeTap(mode: AppMode) {
        if (hasActiveRace) {
            viewModel.selectModeForExistingRace(mode) { onModeSelected(mode) }
        } else {
            pendingMode = mode
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        activeRaceStatus?.let { status ->
            ActiveRaceStatusCard(status)
        }
        Text("Select device mode", style = MaterialTheme.typography.titleMedium)
        ModeButton("Time Mode") { handleModeTap(AppMode.TIME) }
        ModeButton("Bibs Mode") { handleModeTap(AppMode.BIBS) }
        ModeButton("Mule Mode (coming soon)") { handleModeTap(AppMode.MULE) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = withClickSound(onReviewPastRaces), modifier = Modifier.weight(1f)) {
                Text("Review past races")
            }
            TextButton(onClick = withClickSound(onHelp), modifier = Modifier.weight(1f)) {
                Text("Help")
            }
        }
    }

    pendingMode?.let { mode ->
        if (mode == AppMode.BIBS) {
            NewBibsRaceDialog(
                onConfirm = { name, start, count ->
                    viewModel.selectModeAndCreateRace(mode, name, start, count) { onModeSelected(mode) }
                    pendingMode = null
                },
                onDismiss = { pendingMode = null },
            )
        } else {
            NewRaceDialog(
                onConfirm = { name ->
                    viewModel.selectModeAndCreateRace(mode, name) { onModeSelected(mode) }
                    pendingMode = null
                },
                onDismiss = { pendingMode = null },
            )
        }
    }
}

@Composable
private fun ActiveRaceStatusCard(status: ActiveRaceStatus, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Race in progress: ${status.raceLabel}", style = MaterialTheme.typography.titleMedium)
        if (status.splitCount > 0) {
            Text(
                "${status.splitCount} split${if (status.splitCount == 1) "" else "s"} recorded",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (status.bibCount > 0) {
            Text(
                "${status.bibCount} bib${if (status.bibCount == 1) "" else "s"} recorded",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "You can continue by going back to ${status.currentModeLabel}.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ModeButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = withClickSound(onClick),
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}