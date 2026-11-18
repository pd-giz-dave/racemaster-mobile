package mobile.racemaster.ui.closemode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.ui.components.ModeScreenTopBar
import mobile.racemaster.ui.help.HelpTopics
import mobile.racemaster.util.withClickSound
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatClosedAt(millis: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

/** Post-race beacon tidy-up — see [CloseModeViewModel]'s own doc. Tap a beacon in the live
 *  list to connect, read its battery, and power it off; a beacon already closed this race is
 *  still listed (tagged "closed ×N") rather than disappearing, since it may genuinely be
 *  re-presented (TODO.md: a runner going straight to the close station without crossing the
 *  finish line first) — closing it again is a normal action, not an error.
 *
 *  Uses the same [ModeScreenTopBar] (Home + a secondary action, no separate Back) every
 *  recording-mode screen uses, not the plain back/home [mobile.racemaster.ui.components.ScreenTopBar]
 *  this screen used back when it was only the Beacons hub's own utility — that secondary action
 *  is "Stop" here (per direct feedback: Relocate's full name/location/mode editor was clunky for
 *  a mode with nothing meaningful to relocate), clearing the race's mode back to null
 *  ([RaceRepository.stopMode]) rather than opening a separate screen — it's the *only* way to
 *  ever move this race out of Close mode once it's set up, since Close has no Reset of its own
 *  (see [CloseModeViewModel]'s own doc). Reached via Routes.CLOSE_MODE (Setup Race) and
 *  Routes.CLOSE_BEACONS (the Beacons hub's own standing shortcut) alike — see
 *  Routes.CLOSE_BEACONS's own doc for why both stay; Stop is disabled on the latter path unless
 *  the active race's own current mode genuinely is Close (see
 *  [CloseModeUiState.canStopCloseMode]'s own doc). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloseModeScreen(
    onHome: () -> Unit,
    viewModel: CloseModeViewModel = viewModel(factory = CloseModeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showStopConfirm by remember { mutableStateOf(false) }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("Stop Close Mode?") },
            text = { Text("This clears the mode so a different one can be set up for this race. Already-closed beacons stay recorded.") },
            confirmButton = {
                Button(onClick = withClickSound {
                    showStopConfirm = false
                    viewModel.stopMode()
                    onHome()
                }) { Text("Stop") }
            },
            dismissButton = {
                OutlinedButton(onClick = withClickSound { showStopConfirm = false }) { Text("Cancel") }
            },
        )
    }

    uiState.selected?.let { selected ->
        val label = "%03d".format(selected.beaconNumber)
        val prior = uiState.reclosingPriorRecord
        AlertDialog(
            onDismissRequest = viewModel::dismissSelection,
            title = { Text("Close beacon $label" + (selected.bibNumber?.let { " (bib $it)" } ?: "")) },
            text = {
                Text(
                    if (prior != null) {
                        "Already closed at ${formatClosedAt(prior.closedAtMillis)} (battery ${prior.batteryPercent}%). Power off again?"
                    } else {
                        "Read battery and power off?"
                    },
                )
            },
            confirmButton = {
                Button(onClick = withClickSound(viewModel::confirmClose), enabled = !uiState.isBusy) { Text("Close") }
            },
            dismissButton = {
                OutlinedButton(onClick = withClickSound(viewModel::dismissSelection), enabled = !uiState.isBusy) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            ModeScreenTopBar(
                title = "Close Mode",
                thisRaceEnabled = uiState.canStopCloseMode,
                thisRaceLabel = "Stop",
                onThisRace = { showStopConfirm = true },
                onHome = onHome,
                helpTopic = HelpTopics.CloseMode,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            uiState.lastResult?.let { result ->
                Text(
                    result.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (result.outcome == CloseOutcome.SUCCESS) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            if (uiState.isBusy) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text("Closing…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "${uiState.outstanding.size} of ${uiState.totalIssued} outstanding",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                }
                if (uiState.nearbyBeacons.isEmpty()) {
                    item {
                        Text(
                            "No beacons nearby.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                } else {
                    items(uiState.nearbyBeacons, key = { it.advertisement.identifier }) { beacon ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    "%03d".format(beacon.beaconNumber) + (beacon.bibNumber?.let { " · bib $it" } ?: ""),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "RSSI: %d dBm · ~%.1fm".format(beacon.rssi, beacon.distanceMeters) +
                                        if (beacon.closeCount > 0) " · closed ×${beacon.closeCount}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Button(onClick = withClickSound { viewModel.selectBeacon(beacon) }, enabled = !uiState.isBusy) {
                                Text("Close")
                            }
                        }
                    }
                }
                item {
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                    Text(
                        "Outstanding (${uiState.outstanding.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (uiState.outstanding.isEmpty()) {
                    item {
                        Text(
                            "None — every issued beacon has been closed.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    items(uiState.outstanding, key = { it.beaconNumber }) { outstanding ->
                        Text(
                            "Bib ${outstanding.bibNumber} — beacon %03d".format(outstanding.beaconNumber),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
