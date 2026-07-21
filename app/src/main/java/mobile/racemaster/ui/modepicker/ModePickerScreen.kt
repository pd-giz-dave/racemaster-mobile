package mobile.racemaster.ui.modepicker

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import mobile.racemaster.util.withClickSound

@Composable
fun ModePickerScreen(
    onModeSelected: (AppMode) -> Unit,
    onNewRaceNeeded: (AppMode) -> Unit,
    onReviewPastRaces: () -> Unit,
    onHelp: () -> Unit,
    onNameDevice: () -> Unit,
    viewModel: ModePickerViewModel = viewModel(factory = ModePickerViewModel.Factory),
) {
    val hasActiveRace by viewModel.hasActiveRace.collectAsStateWithLifecycle()
    val activeRaceStatus by viewModel.activeRaceStatus.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()

    fun handleModeTap(mode: AppMode) {
        // Mule Mode never creates a race of its own (it pulls/pushes other devices' race
        // data) — routing it through the New Race form on a fresh install was leftover
        // behavior from before Mule Mode was simplified, and asked for fields (race
        // name/course) it has no use for.
        if (mode == AppMode.MULE || hasActiveRace) {
            viewModel.selectModeForExistingRace(mode) { onModeSelected(mode) }
        } else {
            onNewRaceNeeded(mode)
        }
    }

    // The mode picker is always the root of the back stack (see RacemasterNavHost), so
    // pressing back here is the one place that would otherwise exit the app outright —
    // worth a confirmation rather than a stray tap closing everything.
    var showExitConfirm by remember { mutableStateOf(false) }
    var showCpModeInfo by remember { mutableStateOf(false) }
    val activity = LocalActivity.current
    BackHandler { showExitConfirm = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
    ) {
        Text("Set device name", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = withClickSound(onNameDevice),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(deviceName ?: "Name Device", style = MaterialTheme.typography.titleMedium)
        }
        Text("Select device mode", style = MaterialTheme.typography.titleMedium)
        ModeButton("Time Mode") { handleModeTap(AppMode.TIME) }
        ModeButton("Bibs Mode") { handleModeTap(AppMode.BIBS) }
        ModeButton("Mule Mode") { handleModeTap(AppMode.MULE) }
        ModeButton("CP Mode (coming soon)") { showCpModeInfo = true }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = withClickSound(onReviewPastRaces),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Text("History", style = MaterialTheme.typography.titleMedium)
            }
            Button(
                onClick = withClickSound(onHelp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Text("Help", style = MaterialTheme.typography.titleMedium)
            }
        }
        activeRaceStatus?.let { status ->
            ActiveRaceStatusCard(status)
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("Exit RaceMaster?") },
            text = { Text("Are you sure you want to exit the app?") },
            confirmButton = {
                TextButton(onClick = withClickSound { activity?.finish() }) { Text("Exit") }
            },
            dismissButton = {
                TextButton(onClick = withClickSound { showExitConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showCpModeInfo) {
        AlertDialog(
            onDismissRequest = { showCpModeInfo = false },
            title = { Text("CP Mode") },
            text = {
                Text(
                    "CP Mode is for use at checkpoints, to record the passing of runners at " +
                        "that checkpoint. This mode is coming soon.",
                )
            },
            confirmButton = {
                TextButton(onClick = withClickSound { showCpModeInfo = false }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun ActiveRaceStatusCard(status: ActiveRaceStatus, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("Race in progress: ${status.raceLabel}", style = MaterialTheme.typography.bodyMedium)
        if (status.splitCount > 0) {
            Text(
                "${status.splitCount} split${if (status.splitCount == 1) "" else "s"} recorded",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (status.bibCount > 0) {
            Text(
                "${status.bibCount} bib${if (status.bibCount == 1) "" else "s"} recorded",
                style = MaterialTheme.typography.bodySmall,
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
            .height(64.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}