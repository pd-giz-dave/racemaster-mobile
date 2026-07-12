package mobile.racemaster.ui.mulemode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.data.mule.DatasetSummary
import mobile.racemaster.ui.components.CompactTopAppBarHeight
import mobile.racemaster.ui.components.SyncStatusLine
import mobile.racemaster.ui.theme.SyncedGreen
import mobile.racemaster.ui.theme.UnsyncedRed
import mobile.racemaster.util.formatWallClock
import mobile.racemaster.util.withClickSound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuleModeScreen(
    onChangeMode: () -> Unit,
    onSetupServer: () -> Unit,
    viewModel: MuleModeViewModel = viewModel(factory = MuleModeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()

    // A fresh install (or any device that's never logged in) has nothing useful to do on
    // this screen yet — jump straight to Setup Server instead of making the operator find
    // the link themselves. Checked only once (rememberSaveable survives the round trip to
    // Setup Server and back, since navigating there doesn't destroy this screen's back
    // stack entry) — without the guard, cancelling out of Setup Server without logging in
    // would recompose this screen, re-fire the check, and immediately bounce straight back
    // to Setup Server, trapping the operator in a loop with no way to just look at Mule
    // Mode or leave.
    var hasCheckedLoginOnEntry by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (hasCheckedLoginOnEntry) return@LaunchedEffect
        hasCheckedLoginOnEntry = true
        if (!viewModel.isLoggedIn()) onSetupServer()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mule Mode") },
                actions = {
                    TextButton(onClick = withClickSound(onSetupServer)) { Text("Setup Server") }
                    TextButton(onClick = withClickSound(onChangeMode)) { Text("Mode") }
                },
                expandedHeight = CompactTopAppBarHeight,
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        // MainActivity's outer Scaffold (no bottomBar) already reserves the navigation
        // bar's bottom inset for every screen — without this, this inner Scaffold's own
        // default contentWindowInsets reserves it a second time, wasting a whole nav-bar
        // height of blank space above the system bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Mule Mode's layout is due a larger rework — parked here plainly for now.
            if (!deviceName.isNullOrBlank()) {
                Text(text = "Device name: $deviceName", style = MaterialTheme.typography.labelMedium)
            }
            SyncStatusLine(uiState.unsyncedCount, uiState.lastSyncedAtMillis)
            SelectedDatasetBanner(uiState.selectedDataset)
            AutoSyncStatus(uiState, viewModel)

            uiState.statusMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = withClickSound(viewModel::dismissStatusMessage)),
                )
            }

            NearbyDevicesSection(uiState)
            HorizontalDivider()

            if (!uiState.isLoggedIn) {
                Text(
                    "Not logged in — tap Setup Server above to configure the server URL and log in.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                DatasetSection(uiState = uiState, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun SelectedDatasetBanner(selectedDataset: Pair<String, String>?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text("Pushing to", style = MaterialTheme.typography.labelSmall)
        Text(
            selectedDataset?.let { (owner, fullName) -> "$owner/$fullName" } ?: "No dataset selected yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AutoSyncStatus(uiState: MuleModeUiState, viewModel: MuleModeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            uiState.autoSyncStopped -> Text(
                "Auto-sync: STOPPED",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            uiState.autoSyncArmed -> Text(
                "Auto-sync: ON — pulling and pushing automatically every few seconds",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            else -> Text(
                "Pulling from every visible device automatically. Pushing to the server " +
                    "starts once you're logged in and a dataset is selected.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        uiState.autoWarning?.let { warning ->
            Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(
            "Last pull: ${uiState.lastPulledAtMillis?.let { formatWallClock(it) } ?: "never"} · " +
                "Last push: ${uiState.lastSyncedAtMillis?.let { formatWallClock(it) } ?: "never"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = withClickSound(viewModel::forceSyncNow), enabled = !uiState.isBusy) {
                Text("Force sync now")
            }
            if (uiState.autoSyncStopped) {
                TextButton(onClick = withClickSound(viewModel::resumeAutoSync)) { Text("Resume auto-sync") }
            } else {
                TextButton(onClick = withClickSound(viewModel::stopAutoSync)) { Text("Stop auto-sync") }
            }
        }
    }
}

@Composable
private fun NearbyDevicesSection(uiState: MuleModeUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Nearby devices", style = MaterialTheme.typography.titleMedium)
        Text(
            "Every device Mule can see is synced automatically — red means it has unsynced " +
                "data, green means it's all synced.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (uiState.bluetoothWarning != null) {
            Text(
                uiState.bluetoothWarning,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (uiState.discoveredDevices.isEmpty()) {
            Text("Scanning…", style = MaterialTheme.typography.bodyMedium)
        }
        uiState.discoveredDevices.forEach { device ->
            val hasReported = device.deviceName.isNotEmpty() || device.raceLabel.isNotEmpty() || device.roleCounts.isNotEmpty()
            if (!hasReported) {
                Text(
                    "${device.deviceKey.take(8)} — Discovering…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val unsynced = device.roleCounts.values.sum()
                Text(
                    "${device.deviceName.ifEmpty { device.deviceKey.take(8) }} — ${device.raceLabel.ifEmpty { "no race" }}" +
                        if (device.isSelf) " (self)" else if (device.unreachable) " (unreachable)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    // Unreachable overrides whatever unsynced count was last read — that
                    // count is stale the moment a read fails, so trusting it would show a
                    // reassuring green right next to a "couldn't reach" warning.
                    color = if (device.unreachable || unsynced > 0) UnsyncedRed else SyncedGreen,
                )
            }
        }
    }
}

@Composable
private fun DatasetSection(uiState: MuleModeUiState, viewModel: MuleModeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Push to server", style = MaterialTheme.typography.titleMedium)
        DatasetPicker(uiState, viewModel)
        Button(
            onClick = withClickSound(viewModel::pushToServer),
            enabled = !uiState.isBusy && uiState.selectedDataset != null && uiState.unsyncedCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Push ${uiState.unsyncedCount} record${if (uiState.unsyncedCount == 1) "" else "s"} now") }
    }
}

@Composable
private fun DatasetPicker(uiState: MuleModeUiState, viewModel: MuleModeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Dataset", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = withClickSound(viewModel::loadDatasets)) { Text("Refresh") }
        }
        if (uiState.datasets.isEmpty()) {
            Text("No datasets found — tap Refresh.", style = MaterialTheme.typography.bodyMedium)
        }
        uiState.datasets.forEach { dataset: DatasetSummary ->
            val selected = uiState.selectedDataset == dataset.owner to dataset.fullName
            ListItem(
                headlineContent = {
                    Text(
                        "${dataset.owner}/${dataset.name}",
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                supportingContent = { Text(dataset.eventName.ifEmpty { dataset.visibility }) },
                trailingContent = { if (selected) Text("✓ Selected", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier
                    .clickable(onClick = withClickSound { viewModel.selectDataset(dataset.owner, dataset.fullName) })
                    .let {
                        if (selected) it.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)) else it
                    },
            )
        }
    }
}
