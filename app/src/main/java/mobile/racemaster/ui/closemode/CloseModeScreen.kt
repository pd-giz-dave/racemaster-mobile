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
import mobile.racemaster.ui.components.ScreenTopBar
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
 *  finish line first) — closing it again is a normal action, not an error. Below the live list
 *  sits a "Closed" list in bib order (direct feedback: the old "Outstanding" list here was
 *  redundant with the nearby list's own status tags) — reactively derived from the same
 *  CLOSE/CLOSE_REOPENED rows [CloseModeViewModel.closedBeaconNumbers] already tracks, so a
 *  closed beacon re-presenting (auto-reinstated via [CloseModeViewModel.handleReopenDetection])
 *  drops back out of this list the moment that happens, same as it already did for the list this
 *  replaced.
 *
 *  "Stop" is a body-level button at the foot of the page, below the list (direct feedback, same
 *  move [mobile.racemaster.ui.countmode.CountModeScreen] made for its own Stop/Reset pair) — the
 *  top bar itself is the same [mobile.racemaster.ui.components.ScreenTopBar] every non-mode
 *  screen uses (Help/Back/Home, direct feedback: this screen used to be the odd one out with
 *  only a Home icon), not [mobile.racemaster.ui.components.ModeScreenTopBar] (Bibs/Time's own
 *  secondary-action shape) — Back and Home both just navigate home, same reasoning
 *  [mobile.racemaster.ui.countmode.CountModeScreen]'s own doc gives for its identical pair. Stop
 *  clears the race's mode back to null
 *  ([RaceRepository.stopMode]) — it's the *only* way to ever move this race out of Close mode once
 *  it's set up, since Close has no Reset of its own (see [CloseModeViewModel]'s own doc). Reached
 *  via Routes.CLOSE_MODE (Setup Race) and Routes.CLOSE_BEACONS (the Beacons hub's own standing
 *  shortcut) alike — see Routes.CLOSE_BEACONS's own doc for why both stay; Stop is disabled on the
 *  latter path unless the active race's own current mode genuinely is Close (see
 *  [CloseModeUiState.canStopCloseMode]'s own doc). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloseModeScreen(
    onHome: () -> Unit,
    viewModel: CloseModeViewModel = viewModel(factory = CloseModeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
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
            ScreenTopBar(
                title = { Text("Close Mode") },
                onBack = onHome,
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
                // Tight by default (TODO.md: "tighten the vertical spacing of the close list") —
                // section headers below add their own extra top padding instead, matching Count
                // Mode's own LazyColumn convention.
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    // Echoed under the title (TODO.md) — same "Device name: ..." convention
                    // every other mode's own RaceProgressSummary already shows.
                    deviceName?.takeIf { it.isNotBlank() }?.let {
                        Text("Device name: $it", style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        "${uiState.closed.size} of ${uiState.totalIssued} closed",
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
                        "Closed (${uiState.closed.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (uiState.closed.isEmpty()) {
                    item {
                        Text(
                            "None closed yet.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    items(uiState.closed, key = { it.beaconNumber }) { closed ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                (closed.bibNumber?.let { "Bib $it" } ?: "Bib ?") + " — beacon %03d".format(closed.beaconNumber),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "closed ${formatClosedAt(closed.closedAtMillis)}" +
                                    (if (closed.closeCount > 1) " ×${closed.closeCount}" else "") +
                                    " · ${closed.batteryPercent}%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            // Foot-of-page button (direct feedback, same move CountModeScreen made for its own
            // Stop/Reset pair) — see this screen's own doc for why the top bar's own Stop link
            // moved here.
            OutlinedButton(
                onClick = withClickSound { showStopConfirm = true },
                enabled = uiState.canStopCloseMode,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("STOP") }
        }
    }
}
