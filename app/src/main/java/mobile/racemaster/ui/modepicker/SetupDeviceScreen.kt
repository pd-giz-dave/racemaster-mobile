package mobile.racemaster.ui.modepicker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import mobile.racemaster.ui.mulemode.ConnectivityStatusText
import mobile.racemaster.ui.mulemode.MuleModeViewModel
import mobile.racemaster.util.withClickSound

/** The one hub every mode (Time/Bibs/CP/Mule alike) reaches device-level setup through — see
 *  [mobile.racemaster.data.mule.MuleSyncEngine]'s own doc for why this needed a home outside
 *  Mule Mode's own screen once Time/Bibs/CP phones became a distinct, separate role from Mule:
 *  every phone still needs its name, server connection, and Bluetooth/server-sync options
 *  configured independently of which role it's playing, and none of that should require
 *  detouring through a role most operators never actually use.
 *
 *  Deliberately just a list of buttons plus a read-only status summary, not the actual editing
 *  UI for any of them — [ConnectivityStatusText] (feedback only, no controls) reuses exactly
 *  what [mobile.racemaster.ui.mulemode.SetupOptionsScreen]'s own toggles are built on
 *  ([MuleModeViewModel]), so this hub and that screen can never show contradictory status. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupDeviceScreen(
    onDone: () -> Unit,
    onSetupName: () -> Unit,
    onSetupServer: () -> Unit,
    onOptions: () -> Unit,
    connectivityViewModel: MuleModeViewModel = viewModel(factory = MuleModeViewModel.Factory),
) {
    val uiState by connectivityViewModel.uiState.collectAsStateWithLifecycle()
    val deviceName by connectivityViewModel.deviceName.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup Device") },
                navigationIcon = { TextButton(onClick = withClickSound(onDone)) { Text("Back") } },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = withClickSound(onSetupName),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(deviceName?.let { "Setup Name: $it" } ?: "Setup Name")
            }
            Button(
                onClick = withClickSound(onSetupServer),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Setup Server") }
            Button(
                onClick = withClickSound(onOptions),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Options") }
            ConnectivityStatusText(uiState)
        }
    }
}
