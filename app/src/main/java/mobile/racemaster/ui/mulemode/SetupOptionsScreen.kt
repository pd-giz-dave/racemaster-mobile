package mobile.racemaster.ui.mulemode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.util.formatWallClock
import mobile.racemaster.util.withClickSound

/** The actual Bluetooth on/off, server-sync on/off, and auto-sync controls — split out from
 *  [SetupDeviceScreen]'s own plain status summary so that hub stays a quick glance, with the
 *  interactive controls one tap further in here. Reuses [MuleModeViewModel] rather than a
 *  dedicated one of its own — it already exposes exactly this state (and the control
 *  functions), regardless of which mode this phone is actually in; see that ViewModel's own
 *  uiState fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupOptionsScreen(
    onDone: () -> Unit,
    viewModel: MuleModeViewModel = viewModel(factory = MuleModeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Options") },
                navigationIcon = { TextButton(onClick = withClickSound(onDone)) { Text("Back") } },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BluetoothAndServerSyncToggles(uiState = uiState, viewModel = viewModel)
            HorizontalDivider()
            AutoSyncControls(uiState = uiState, viewModel = viewModel)
        }
    }
}

// The push/auto-sync half of what used to be one combined AutoSyncStatus composable on Mule
// Mode's own screen (see MuleModeScreen.kt's PullStatusLine for the other, BLE-pull-only half
// that stayed there). This half is mode-agnostic — pushIfNeeded()/forceSyncNow() push THIS
// device's own recorded data to the server regardless of Bluetooth role, so every mode needs
// access to it, not just Mule. forceSyncNow() also triggers a pull pass, but that's a no-op on
// a non-Mule phone (nothing ever populates discoveredFlow without scanning), so it's safe to
// expose here unconditionally.
@Composable
internal fun AutoSyncControls(uiState: MuleModeUiState, viewModel: MuleModeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            uiState.autoSyncStopped -> Text(
                "Auto-sync: STOPPED",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            uiState.autoSyncArmed -> Text(
                if (uiState.isMuleMode) {
                    "Auto-sync: ON — pulling and pushing every few seconds"
                } else {
                    "Auto-sync: ON — pushing every few seconds"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        uiState.autoWarning?.let { warning ->
            Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
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
