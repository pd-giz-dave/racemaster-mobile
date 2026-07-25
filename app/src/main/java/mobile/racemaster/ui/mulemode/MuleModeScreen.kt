package mobile.racemaster.ui.mulemode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
            BluetoothAndServerSyncToggles(uiState, viewModel)
            AutoSyncStatus(uiState, viewModel)

            uiState.statusMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = withClickSound(viewModel::dismissStatusMessage)),
                )
            }

            NearbyDevicesSection(uiState, onForget = viewModel::forgetDevice)
        }
    }
}

// Two independent toggles, side by side: turning Bluetooth off takes the whole radio
// presence down (not scanning, not advertising, not answerable by any other Mule) without
// affecting server sync; turning server sync off stops pushing to/checking the server
// without affecting BLE device-to-device sync. Both are also independent of the Auto-sync
// stop/resume toggle below (which only pauses this device's own pull/push loop while it
// keeps scanning/advertising/serving).
@Composable
private fun BluetoothAndServerSyncToggles(uiState: MuleModeUiState, viewModel: MuleModeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (uiState.bluetoothOff) {
            Text(
                "Bluetooth: OFF — not visible to nearby devices",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                "Bluetooth: ON — pulling data from nearby devices",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (uiState.serverSyncOff) {
            Text(
                "Server sync: OFF — not pushing to the server",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (!uiState.isLoggedIn) {
            Text(
                "Not logged in — tap Setup Server above to configure the server URL and log in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                "Server sync: ON — pushing data to the server",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (uiState.bluetoothOff) {
                Button(onClick = withClickSound(viewModel::turnBluetoothOn)) { Text("Bluetooth off") }
            } else {
                OutlinedButton(onClick = withClickSound(viewModel::turnBluetoothOff)) { Text("Bluetooth on") }
            }
            if (uiState.serverSyncOff) {
                Button(onClick = withClickSound(viewModel::turnServerSyncOn)) { Text("Server sync off") }
            } else {
                OutlinedButton(onClick = withClickSound(viewModel::turnServerSyncOff)) { Text("Server sync on") }
            }
        }
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
                "Auto-sync: ON — pulling and pushing every few seconds",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        uiState.autoWarning?.let { warning ->
            Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        // Nearby device count is always worth showing alongside "Last pull" — it's the one
        // piece of context that explains a persistent "never" (nothing nearby to pull from,
        // not a malfunction) without the operator having to cross-reference the list below.
        // Split direct (real BLE visibility) from relayed (known only transitively, via
        // another Mule) once any relaying is actually happening — a raw combined count would
        // overstate how many devices are genuinely in range right now. Falls back to today's
        // exact wording while relayCount is 0, so this is a visual no-op until a chain forms.
        val directDeviceCount = uiState.discoveredDevices.count { !it.isSelf && !it.isStale && it.relayedViaDeviceName == null }
        val relayDeviceCount = uiState.discoveredDevices.count { it.relayedViaDeviceName != null }
        val fromSuffix = if (relayDeviceCount > 0) {
            "$directDeviceCount nearby, $relayDeviceCount relayed"
        } else {
            "$directDeviceCount device${if (directDeviceCount == 1) "" else "s"}"
        }
        Text(
            "Last pull: ${uiState.lastPulledAtMillis?.let { formatWallClock(it) } ?: "never"} (from $fromSuffix)",
            style = MaterialTheme.typography.bodySmall,
        )
        // Unlike a pull, a push doesn't depend on any nearby device being visible at all — this
        // device's own data pushes on its own the moment it's logged in, so a genuine "never"
        // here only ever means one thing: not logged in. Once something has actually pushed,
        // the qualifier drops — pairing a real timestamp with "(no server)" would read as a
        // contradiction rather than an explanation.
        Text(
            if (uiState.lastSyncedAtMillis == null && !uiState.isLoggedIn) {
                "Last push: never (no server)"
            } else {
                "Last push: ${uiState.lastSyncedAtMillis?.let { formatWallClock(it) } ?: "never"}"
            },
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
private fun NearbyDevicesSection(uiState: MuleModeUiState, onForget: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Nearby devices", style = MaterialTheme.typography.titleMedium)
        Text(
            "Red means it has unsynced data, green means it's all synced, grey means it's " +
                "gone quiet — most recently seen first.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (uiState.bluetoothOff) {
            Text("Bluetooth off — not scanning for nearby devices", style = MaterialTheme.typography.bodySmall)
        } else if (uiState.bluetoothWarning != null) {
            Text(
                uiState.bluetoothWarning,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (uiState.discoveredDevices.isEmpty()) {
            Text("Scanning…", style = MaterialTheme.typography.bodyMedium)
        }
        uiState.discoveredDevices.forEach { device ->
            // deviceName is always non-blank the moment any device (self or a genuinely
            // different BLE peer) has actually resolved — see PeripheralSyncService's own
            // advertise gate — so that alone would already suffice; raceLabel is the one
            // remaining case it doesn't cover: self, resolved, but with no active race and no
            // device name ever chosen yet.
            val hasReported = device.deviceName.isNotEmpty() || device.raceLabel.isNotEmpty()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    // A stale row (see DiscoveredDevice.isStale's own doc) always has a name —
                    // it only exists because it resolved at least once before — so this branches
                    // ahead of the live-only "Discovering…" case below. Neutral grey, not
                    // red/green: there's no current sync status to report, just a last-seen time,
                    // which is what differentiates it from a live row at a glance.
                    device.isStale -> Text(
                        "${device.deviceName} — last seen ${formatWallClock(device.lastReachableAtMillis)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    !hasReported -> Text(
                        "${device.deviceKey.take(8)} — Discovering…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    else -> {
                        val unsynced = device.unsyncedCount
                        // Below UNREACHABLE_FAILURE_THRESHOLD, a missed read is still just ordinary
                        // BLE noise (see DiscoveredDevice's own doc) — naming the running count here
                        // rather than staying silent lets the operator see a device is having trouble
                        // before it's actually flagged unreachable, without a shared banner that could
                        // only ever name one device at a time.
                        val suffix = when {
                            device.isSelf -> " (self)"
                            // A relay-only row has no direct BLE link of its own to be unreachable/missing
                            // reads on — those track this phone's own connection to a peer, meaningless
                            // for an origin only ever known transitively (see DiscoveredDevice's own doc).
                            device.relayedViaDeviceName != null -> " (via ${device.relayedViaDeviceName})"
                            device.unreachable -> " (unreachable)"
                            device.consecutiveFailures > 0 -> " (missed ${device.consecutiveFailures})"
                            else -> ""
                        }
                        Text(
                            "${device.deviceName.ifEmpty { device.deviceKey.take(8) }} — ${device.raceLabel.ifEmpty { "no race" }}$suffix",
                            style = MaterialTheme.typography.bodyMedium,
                            // Unreachable overrides whatever unsynced count was last read — that
                            // count is stale the moment a read fails, so trusting it would show a
                            // reassuring green right next to a "couldn't reach" warning.
                            color = if (device.unreachable || unsynced > 0) UnsyncedRed else SyncedGreen,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                // Can't forget yourself — there's no live/persisted entry for self to purge
                // (see MuleSyncEngine.selfDevice's own doc, it's synthesized fresh every time,
                // never folded into discoveredFlow or the known-devices roster at all). A plain
                // clickable Text, not a Button — Material's own minimum touch target on even a
                // TextButton was tall enough to visibly space out every row in this list.
                if (!device.isSelf) {
                    Text(
                        "Forget",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = withClickSound { onForget(device.deviceId ?: device.deviceKey) }),
                    )
                }
            }
        }
    }
}
