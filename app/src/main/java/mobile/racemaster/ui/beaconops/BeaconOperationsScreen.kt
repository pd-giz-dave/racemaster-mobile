package mobile.racemaster.ui.beaconops

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mobile.racemaster.ui.components.ScreenTopBar
import mobile.racemaster.ui.help.HelpTopics
import mobile.racemaster.util.withClickSound

/** Mode Picker's "Beacons" button lands here rather than straight on any one operation — three
 *  distinct beacon-related jobs (per TODO.md's "Race-day usage" section) share this one hub the
 *  same way [mobile.racemaster.ui.modepicker.SetupDeviceScreen] hubs device setup: a plain list
 *  of buttons, no logic of its own.
 *  - **Check** — [mobile.racemaster.ui.beaconidentify.IdentifyFobScreen], TODO.md's "check mode":
 *    read-only, confirms a beacon is on and its number matches the label on it.
 *  - **Close** — [CloseBeaconsScreen], TODO.md's "finish mode": not yet implemented; the button
 *    exists so all three operations are in place up front rather than appearing piecemeal later.
 *  - **Program** — [mobile.racemaster.ui.beacon.BeaconProgrammerScreen]: writes a beacon's number
 *    and measured power ahead of a race. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeaconOperationsScreen(
    onDone: () -> Unit,
    onHome: () -> Unit,
    onCheck: () -> Unit,
    onClose: () -> Unit,
    onProgram: () -> Unit,
) {
    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text("Beacons") },
                onBack = onDone,
                onHome = onHome,
                helpTopic = HelpTopics.BeaconOperations,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = withClickSound(onCheck),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Check") }
            Button(
                onClick = withClickSound(onClose),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Close") }
            Button(
                onClick = withClickSound(onProgram),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Program") }
        }
    }
}
