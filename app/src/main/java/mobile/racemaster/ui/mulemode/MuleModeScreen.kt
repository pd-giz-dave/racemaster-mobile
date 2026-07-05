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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.data.mule.DatasetSummary
import mobile.racemaster.ui.components.SyncStatusLine
import mobile.racemaster.util.formatWallClock
import mobile.racemaster.util.withClickSound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuleModeScreen(
    onChangeMode: () -> Unit,
    viewModel: MuleModeViewModel = viewModel(factory = MuleModeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mule Mode") },
                actions = {
                    TextButton(onClick = withClickSound(onChangeMode)) { Text("Mode") }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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

            NearbyDevicesSection(uiState, viewModel)
            HorizontalDivider()

            if (!uiState.isLoggedIn) {
                LoginSection(isBusy = uiState.isBusy, onLogin = viewModel::login)
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
                "Auto-sync starts once you're logged in and a dataset is selected, and at " +
                    "least one device is attached.",
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
private fun NearbyDevicesSection(uiState: MuleModeUiState, viewModel: MuleModeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Nearby devices", style = MaterialTheme.typography.titleMedium)
        Text(
            "Attach to up to one Bibs device and one Time device at once (or one phone that's " +
                "held both) — both are optional.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (uiState.discoveredDevices.isEmpty()) {
            Text("Scanning…", style = MaterialTheme.typography.bodyMedium)
        }
        uiState.discoveredDevices.forEach { device ->
            val isLocked = device.deviceId != null &&
                (device.deviceId == uiState.lockedBibsDeviceId || device.deviceId == uiState.lockedTimeDeviceId)
            ListItem(
                headlineContent = { Text(device.raceLabel.ifEmpty { device.deviceKey.take(8) }.ifEmpty { "Discovering…" }) },
                supportingContent = {
                    if (device.roleCounts.isEmpty()) {
                        Text("Discovering…")
                    } else {
                        Text(
                            device.roleCounts.entries.joinToString(" · ") { (role, count) -> "$role: $count unsynced" } +
                                if (isLocked) " · attached" else "",
                        )
                    }
                },
                trailingContent = {
                    TextButton(
                        onClick = withClickSound { viewModel.pullFrom(device) },
                        enabled = device.deviceId != null && !uiState.isBusy,
                    ) { Text("Pull") }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun LoginSection(isBusy: Boolean, onLogin: (String, String, String) -> Unit) {
    var baseUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Log in to sync to the server", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            singleLine = true,
            label = { Text("Server URL (e.g. racemaster.example.com)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            singleLine = true,
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            singleLine = true,
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = withClickSound { onLogin(baseUrl.trim(), username.trim(), password) },
            enabled = !isBusy && baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Log in") }
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
