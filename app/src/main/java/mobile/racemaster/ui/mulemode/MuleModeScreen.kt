package mobile.racemaster.ui.mulemode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

    // A fresh install (or any device that's never logged in) is offered an explicit choice
    // rather than being auto-forwarded to Setup Server: Mule Mode's Bluetooth pull/relay side
    // works fine with no server at all, so an operator opening this screen just to look
    // around shouldn't be forced through server setup first. null while the one-shot checks
    // below are still resolving (renders nothing that one frame, rather than flashing the
    // real logged-out screen first); true/false once resolved. rememberSaveable so the choice
    // survives the round trip to Setup Server and back and sticks across recomposition for
    // this screen's back-stack lifetime — naturally stops mattering the moment isLoggedIn
    // flips true for real (e.g. after actually completing Setup Server).
    var showServerChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // Whether "With server" is the recommended option — a device with no working internet
    // connection right now can't reach a server regardless, so "Without server" (pure
    // Bluetooth device-to-device sync) is highlighted instead. See
    // MuleModeViewModel.hasInternetConnectivity's own doc for why this is a one-shot check,
    // not a live subscription.
    var recommendServer by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (showServerChoice != null) return@LaunchedEffect
        recommendServer = viewModel.hasInternetConnectivity()
        showServerChoice = !viewModel.isLoggedIn()
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
        when (showServerChoice) {
            // Still resolving the one-shot checks above — nothing to show yet, deliberately
            // for only a frame or two rather than flashing the real (logged-out) screen first.
            null -> {}
            true -> ServerChoicePrompt(
                recommendServer = recommendServer,
                onWithServer = {
                    showServerChoice = false
                    onSetupServer()
                },
                onWithoutServer = { showServerChoice = false },
                modifier = Modifier
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            )
            false -> Column(
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
                }
            }
        }
    }
}

@Composable
private fun ServerChoicePrompt(
    recommendServer: Boolean,
    onWithServer: () -> Unit,
    onWithoutServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Use Mule Mode with a server, or purely device-to-device?", style = MaterialTheme.typography.titleMedium)
        Text(
            "Nearby devices sync with each other over Bluetooth either way. A server also " +
                "backs everything up online for the race organizer, reachable even once " +
                "devices are out of Bluetooth range of each other.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!recommendServer) {
            Text(
                "No internet connection detected right now, so Without server is recommended " +
                    "— you can still connect to a server later from this screen's Setup " +
                    "Server button once you have signal or WiFi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // The recommended option (based on current connectivity) gets the filled Button; the
        // other gets the lower-emphasis OutlinedButton — same two actions either way, just
        // which one visually leads.
        if (recommendServer) {
            Button(onClick = withClickSound(onWithServer), modifier = Modifier.fillMaxWidth()) { Text("With server") }
            OutlinedButton(onClick = withClickSound(onWithoutServer), modifier = Modifier.fillMaxWidth()) { Text("Without server") }
        } else {
            OutlinedButton(onClick = withClickSound(onWithServer), modifier = Modifier.fillMaxWidth()) { Text("With server") }
            Button(onClick = withClickSound(onWithoutServer), modifier = Modifier.fillMaxWidth()) { Text("Without server") }
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
                "Auto-sync: ON — pulling and pushing automatically every few seconds",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            else -> Text(
                "Pulling from every visible device automatically. Pushing to the server " +
                    "starts once you're logged in.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        uiState.autoWarning?.let { warning ->
            Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(
            "Last pull: ${uiState.lastPulledAtMillis?.let { formatWallClock(it) } ?: "never"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
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
                // Below UNREACHABLE_FAILURE_THRESHOLD, a missed read is still just ordinary
                // BLE noise (see DiscoveredDevice's own doc) — naming the running count here
                // rather than staying silent lets the operator see a device is having trouble
                // before it's actually flagged unreachable, without a shared banner that could
                // only ever name one device at a time.
                val suffix = when {
                    device.isSelf -> " (self)"
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
                )
            }
        }
    }
}
