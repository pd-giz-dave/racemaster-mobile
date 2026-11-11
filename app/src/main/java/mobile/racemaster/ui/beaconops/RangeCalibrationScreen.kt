package mobile.racemaster.ui.beaconops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.data.beacon.RangeContext
import mobile.racemaster.ui.components.ScreenTopBar
import mobile.racemaster.ui.help.HelpTopics
import mobile.racemaster.util.withClickSound

/** Data-collection tool, not a race-day display — see [RangeCalibrationViewModel]'s own doc.
 *  Pick the [RangeContext] matching where you're standing, enter the distance you've actually
 *  measured to a beacon, then tap Record on that beacon's row in the live list below. Share
 *  exports everything recorded so far (this session and every prior one — the file accumulates
 *  across app restarts) for offline analysis. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RangeCalibrationScreen(
    onDone: () -> Unit,
    onHome: () -> Unit,
    viewModel: RangeCalibrationViewModel = viewModel(factory = RangeCalibrationViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    if (uiState.showClearConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearConfirm,
            title = { Text("Clear all recorded samples?") },
            text = { Text("This deletes every range-calibration sample recorded so far on this device, including from earlier sessions. Share first if you haven't yet.") },
            confirmButton = { Button(onClick = withClickSound(viewModel::confirmClear)) { Text("Clear") } },
            dismissButton = { OutlinedButton(onClick = withClickSound(viewModel::dismissClearConfirm)) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text("Range Calibration") },
                onBack = onDone,
                onHome = onHome,
                helpTopic = HelpTopics.RangeCalibration,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        // Scrollable like BeaconProgrammerScreen (see its own outerScrollState) — on a small
        // screen (confirmed on the XCover 4) three context buttons, the distance field, every
        // currently-audible beacon's row, and the Clear/Share row together overflow a fixed
        // Column with no way to reach what's cut off. The beacon list is a plain Column, not a
        // LazyColumn, since it rides this same outer scroll (a LazyColumn can't be nested inside
        // a scrollable parent without its own bounded height) — fine here since it's never more
        // than a handful of rows.
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Context", style = MaterialTheme.typography.labelLarge)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RangeContext.entries.forEach { option ->
                    val selected = uiState.context == option
                    Button(
                        onClick = withClickSound { viewModel.setContext(option) },
                        colors = if (selected) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary,
                            )
                        } else {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(option.label) }
                }
            }

            OutlinedTextField(
                value = uiState.distanceMetersInput,
                onValueChange = viewModel::setDistanceMetersInput,
                label = { Text("Measured distance (m)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            Text(
                if (uiState.beaconsInRange.isEmpty()) {
                    "No programmed race beacons heard yet."
                } else {
                    "Tap Record on a beacon below once the distance above is correct for it."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.beaconsInRange.forEach { beacon ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("%03d".format(beacon.beaconNumber), style = MaterialTheme.typography.titleMedium)
                            Text(
                                "RSSI: %d dBm · today's estimate: ~%.1fm".format(beacon.rawRssi, beacon.currentEstimateMeters),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(
                            onClick = withClickSound { viewModel.record(beacon.address) },
                            enabled = uiState.canRecord,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) { Text("Record") }
                    }
                }
            }

            HorizontalDivider()

            Text("${uiState.sampleCount} sample(s) recorded", style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = withClickSound(viewModel::requestClear), modifier = Modifier.weight(1f)) {
                    Text("Clear")
                }
                Button(
                    onClick = withClickSound {
                        viewModel.shareIntent()?.let { context.startActivity(it) }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Share") }
            }
        }
    }
}
