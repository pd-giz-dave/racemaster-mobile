package mobile.racemaster.ui.countmode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import mobile.racemaster.ui.components.ResetButton
import mobile.racemaster.ui.help.HelpTopics
import mobile.racemaster.util.withClickSound

/** Pre-start headcount — see [CountModeViewModel]'s own doc. Structurally the simplest of the
 *  four mode screens: one running count, one list of issued-but-not-yet-seen bibs — no per-
 *  beacon dialog or result banner, since counting has no fallible action to report on the way a
 *  BLE connect/power-off does. The top bar's own secondary action is "Stop" (per direct
 *  feedback: Relocate's full name/location/mode editor was clunky for a mode with nothing
 *  meaningful to relocate) — clears the race's mode back to null ([mobile.racemaster.data.repository.RaceRepository.stopMode])
 *  rather than opening a separate screen: it's the only way to ever move this race out of Count
 *  mode once it's set up. A separate, body-level "Reset" button (reusing the shared
 *  [ResetButton] composable Bibs/Time already use) additionally closes out the current Count
 *  segment (see [CountModeViewModel.resetCountMode]'s own doc) so a restarted count genuinely
 *  starts from zero, rather than resuming whatever was already seen — Stop alone deliberately
 *  does not do this, since pausing without losing the running tally is still useful on its own.
 *  Reset calls [onHome] itself immediately after, same reasoning as Stop's own dialog: this
 *  screen's scan loop would otherwise immediately re-detect and re-record the very beacons just
 *  reset. Reached only via Routes.COUNT_MODE (Setup Race) — unlike Close, the Beacons hub's own
 *  "Count" button is no longer a standing shortcut straight to this screen (TODO.md: Count only
 *  ever makes sense against a race already set up for it, unlike Close — see
 *  [mobile.racemaster.ui.beaconops.BeaconOperationsScreen]'s own doc), so both Stop and Reset's
 *  [CountModeUiState.canStopCountMode] gating is now effectively always true in practice, kept
 *  as a defensive guard rather than removed outright. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountModeScreen(
    onHome: () -> Unit,
    viewModel: CountModeViewModel = viewModel(factory = CountModeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showStopConfirm by remember { mutableStateOf(false) }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("Stop Count Mode?") },
            text = { Text("This clears the mode so a different one can be set up for this race. Beacons already seen stay recorded.") },
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

    Scaffold(
        topBar = {
            ModeScreenTopBar(
                title = "Count Mode",
                thisRaceEnabled = uiState.canStopCountMode,
                thisRaceLabel = "Stop",
                onThisRace = { showStopConfirm = true },
                onHome = onHome,
                helpTopic = HelpTopics.CountMode,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "${uiState.uniqueBeaconsSeen} unique beacons seen",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "${uiState.outstanding.size} of ${uiState.totalIssued} yet to be seen",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                }
                if (uiState.outstanding.isEmpty()) {
                    item {
                        Text(
                            // Both an empty outstanding list AND totalIssued == 0 look identical
                            // here (there's nothing to show either way) — but they mean opposite
                            // things: the former is genuine completion, the latter is "no beacon
                            // expectations loaded for this race at all yet" (e.g. the server's
                            // own progress.json for it hasn't synced down yet), which the old
                            // single "every issued beacon has been seen" text claimed regardless,
                            // confirmed in the field as actively misleading in that second case.
                            if (uiState.totalIssued == 0) {
                                "No beacon expectations loaded for this race yet."
                            } else {
                                "None — every issued beacon has been seen."
                            },
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
            ResetButton(
                resetDescription = "Stops Count Mode and clears the running tally so it can be restarted from zero. Nothing is deleted — every beacon seen stays recorded in Race History.",
                onReset = {
                    viewModel.resetCountMode()
                    onHome()
                },
                enabled = uiState.canStopCountMode,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
