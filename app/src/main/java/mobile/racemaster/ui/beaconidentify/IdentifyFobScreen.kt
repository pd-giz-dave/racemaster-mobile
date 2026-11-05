package mobile.racemaster.ui.beaconidentify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.data.beacon.DetectedRaceBeacon
import mobile.racemaster.ui.components.ScreenTopBar
import mobile.racemaster.ui.help.HelpTopics

/** Always just a nearest-first list of whatever's currently within
 *  [mobile.racemaster.data.settings.SettingsRepository.beaconIdentifyDistanceMeters] — no
 *  separate "exactly one found" display, so the screen never jumps between layouts as a second
 *  fob wanders in or out of range; reading the top (nearest) entry against the beacon's own
 *  label is the normal single-fob case. (See [mobile.racemaster.ui.beacon.BeaconProgrammerScreen]
 *  for the unrelated job of programming a beacon rather than checking an already-programmed
 *  one's number.) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentifyFobScreen(
    onDone: () -> Unit,
    onHome: () -> Unit,
    viewModel: IdentifyFobViewModel = viewModel(factory = IdentifyFobViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text("Check Beacon") },
                onBack = onDone,
                onHome = onHome,
                helpTopic = HelpTopics.IdentifyFob,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (uiState.beaconsInRange.isEmpty()) {
                Text(
                    "Hold a beacon close to the phone",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(uiState.beaconsInRange, key = DetectedRaceBeacon::address) { beacon ->
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "%03d".format(beacon.beaconNumber),
                                style = MaterialTheme.typography.displayLarge,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                "RSSI: %d dBm · ~%.1fm".format(beacon.rssi, beacon.estimatedDistanceMeters),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}
