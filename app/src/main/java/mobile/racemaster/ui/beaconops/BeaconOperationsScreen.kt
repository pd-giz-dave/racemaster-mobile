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

/** Mode Picker's "Beacons" button lands here rather than straight on any one operation — five
 *  distinct beacon-related jobs share this one hub the same way
 *  [mobile.racemaster.ui.modepicker.SetupDeviceScreen] hubs device setup: a plain list of
 *  buttons, no logic of its own.
 *  - **Check** — [mobile.racemaster.ui.beaconidentify.IdentifyFobScreen], TODO.md's "check mode":
 *    read-only, confirms a beacon is on and its number matches the label on it.
 *  - **Close** — [mobile.racemaster.ui.closemode.CloseModeScreen], TODO.md's "finish mode":
 *    post-race battery read + power-off. Also its own Setup Race mode now (for a phone
 *    dedicated to this job all race long) — this button is a standing shortcut to the same
 *    screen, usable regardless of the active race's own mode (see Routes.CLOSE_BEACONS's own
 *    doc for why both entry points stay).
 *  - **Count** — [mobile.racemaster.ui.countmode.CountModeScreen], docs/beacon-detection-plan.md's
 *    Count mode: pre-start headcount. Same pairing as Close above — also its own Setup Race
 *    mode, this button a standing shortcut to the same screen regardless of the active race's
 *    own mode (see Routes.COUNT_BEACONS's own doc).
 *  - **Program** — [mobile.racemaster.ui.beacon.BeaconProgrammerScreen]: writes a beacon's number
 *    and measured power ahead of a race.
 *  - **Range Calibration** — [RangeCalibrationScreen], TODO.md's "Bluetooth Beacon range
 *    estimation" section: a data-collection tool for working out why the shipped distance
 *    estimate is inaccurate, not a race-day operation — lives here anyway since it needs the
 *    same passive beacon scanning as Check, and TODO.md asks for it to be "part of the
 *    programming system." */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeaconOperationsScreen(
    onDone: () -> Unit,
    onHome: () -> Unit,
    onCheck: () -> Unit,
    onClose: () -> Unit,
    onCount: () -> Unit,
    onProgram: () -> Unit,
    onRangeCalibration: () -> Unit,
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
                onClick = withClickSound(onCount),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Count") }
            Button(
                onClick = withClickSound(onProgram),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Program") }
            Button(
                onClick = withClickSound(onRangeCalibration),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Range Calibration") }
        }
    }
}
