package mobile.racemaster.ui.beacon

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.data.beacon.BeaconConnectionPhase
import mobile.racemaster.data.beacon.BeaconLogDirection
import mobile.racemaster.data.beacon.BeaconLogEntry
import mobile.racemaster.data.beacon.DxCp27Protocol
import mobile.racemaster.data.beacon.SlotSummary
import mobile.racemaster.ui.components.ScreenTopBar
import mobile.racemaster.ui.help.HelpTopics
import mobile.racemaster.util.bluetoothPermissions
import mobile.racemaster.util.hasAllPermissions
import mobile.racemaster.util.isPermissionRequestDialogSuppressed
import mobile.racemaster.util.openAppPermissionSettings
import mobile.racemaster.util.withClickSound

/** Lists nearby DX-CP27 beacons, connects and authenticates, shows the selected one's real
 *  current configuration (including battery), and programs it per TODO.md: a device-name frame
 *  encoding an operator-entered minor and measured power, 200ms interval, TX power -1dBm — see
 *  [mobile.racemaster.data.beacon.BeaconRepository.programForRace]. Every step narrates into the
 *  log at the bottom, which is the only place to see whether a step genuinely took effect on the
 *  real beacon. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeaconProgrammerScreen(
    onDone: () -> Unit,
    onHome: () -> Unit,
    viewModel: BeaconProgrammerViewModel = viewModel(factory = BeaconProgrammerViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissions = remember { bluetoothPermissions() }
    var permissionsGranted by remember { mutableStateOf(context.hasAllPermissions(permissions)) }
    var bluetoothEnabled by remember { mutableStateOf(viewModel.isBluetoothEnabled()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsGranted = context.hasAllPermissions(permissions)
    }
    LifecycleResumeEffect(Unit) {
        permissionsGranted = context.hasAllPermissions(permissions)
        bluetoothEnabled = viewModel.isBluetoothEnabled()
        onPauseOrDispose {}
    }

    BackHandler {
        viewModel.disconnect()
        onDone()
    }

    if (uiState.showProgramConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissProgramConfirm,
            title = { Text("Program this beacon?") },
            text = { Text("This clears every advertising slot after the iBeacon one (leaving the device name in slot 1 alone) and may change the beacon's password. Continue?") },
            confirmButton = { Button(onClick = withClickSound(viewModel::confirmProgram)) { Text("Program") } },
            dismissButton = { OutlinedButton(onClick = withClickSound(viewModel::dismissProgramConfirm)) { Text("Cancel") } },
        )
    }
    if (uiState.showPowerOffConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPowerOffConfirm,
            title = { Text("Power off this beacon?") },
            text = { Text("The beacon will stop advertising until it's powered back on by hand. Continue?") },
            confirmButton = { Button(onClick = withClickSound(viewModel::confirmPowerOff)) { Text("Power Off") } },
            dismissButton = { OutlinedButton(onClick = withClickSound(viewModel::dismissPowerOffConfirm)) { Text("Cancel") } },
        )
    }
    if (uiState.showFactoryResetConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissFactoryResetConfirm,
            title = { Text("Factory reset this beacon?") },
            text = { Text("This erases the password and every advertising slot, including the device name, back to firmware defaults. Continue?") },
            confirmButton = { Button(onClick = withClickSound(viewModel::confirmFactoryReset)) { Text("Factory Reset") } },
            dismissButton = { OutlinedButton(onClick = withClickSound(viewModel::dismissFactoryResetConfirm)) { Text("Cancel") } },
        )
    }

    // Manual scroll only, deliberately — the log panel below shows newest-first and never
    // auto-scrolls (see LogPanel's own doc), so there's nothing left here needing a programmatic
    // scroll either.
    val outerScrollState = rememberScrollState()

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text("Program Beacon") },
                onBack = { viewModel.disconnect(); onDone() },
                onHome = onHome,
                helpTopic = HelpTopics.ProgramBeacons,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(outerScrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                !permissionsGranted -> {
                    Text(
                        "Bluetooth permission is required to scan for or connect to a beacon.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = withClickSound {
                            if (context.isPermissionRequestDialogSuppressed(permissions)) {
                                context.openAppPermissionSettings()
                            } else {
                                permissionLauncher.launch(permissions)
                            }
                        },
                    ) { Text("Grant Bluetooth permission") }
                }
                !bluetoothEnabled -> {
                    Text("Bluetooth is off — turn it on to scan for beacons.", color = MaterialTheme.colorScheme.error)
                }
                uiState.connectionPhase == BeaconConnectionPhase.CONNECTED -> {
                    ConnectedSection(uiState = uiState, viewModel = viewModel)
                }
                else -> {
                    ScanSection(uiState = uiState, viewModel = viewModel)
                }
            }
            HorizontalDivider()
            LogPanel(log = uiState.log, onClear = viewModel::clearLog)
        }
    }
}

/** " — ~Nms" once a frame kind's actual advertising interval has been measured (its second
 *  arrival this scan), else blank — see [ObservedAdvertFrame.estimatedIntervalMillis]'s own doc. */
private fun Long?.intervalSuffix(): String = this?.let { " — ~${it}ms" } ?: ""

@Composable
private fun ScanSection(uiState: BeaconUiState, viewModel: BeaconProgrammerViewModel) {
    // Shows the decoded-advert dialog for whichever scan row's info icon was last tapped — see
    // the Info IconButton below. Null means no dialog is showing. Tracks the address, not a
    // frozen ScannedBeaconUi snapshot — that beacon keeps advertising new distinct frame types
    // for as long as the dialog is open (confirmed on real hardware: a factory-default unit's
    // device-name/iBeacon/Eddystone×3 frames can take a few seconds each to all arrive), so this
    // re-looks-up the live, continuously-updating scan result every recomposition rather than
    // freezing on whatever was seen at the instant of the tap.
    var decodedAdvertForAddress by remember { mutableStateOf<String?>(null) }
    val decodedAdvertFor = decodedAdvertForAddress?.let { address -> uiState.scanResults.firstOrNull { it.address == address } }
    decodedAdvertFor?.let { beacon ->
        AlertDialog(
            onDismissRequest = { decodedAdvertForAddress = null },
            title = { Text(beacon.displayName ?: "(unnamed)") },
            text = {
                // This beacon broadcasts each configured slot as its own separate advertisement
                // packet (never combined — see ScannedBeaconUi.frames's own doc), so there can be
                // anywhere from zero (every slot deleted/disabled, or none have arrived yet) to
                // several distinct frames to show, each its own line rather than one slot's
                // absence masking another's presence.
                if (beacon.frames.isEmpty()) {
                    Text(
                        "No advertisement frames seen from this beacon yet — every slot may be " +
                            "deleted/disabled, or none have arrived yet. Connect to see its real " +
                            "configuration.",
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        beacon.frames.forEach { frame ->
                            when (frame) {
                                is ObservedAdvertFrame.DeviceName -> Text(
                                    "Name: \"${frame.name}\"" + frame.estimatedIntervalMillis.intervalSuffix(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                is ObservedAdvertFrame.IBeacon -> Text(
                                    "iBeacon" + frame.estimatedIntervalMillis.intervalSuffix(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                is ObservedAdvertFrame.Eddystone -> Text(
                                    frame.label + frame.estimatedIntervalMillis.intervalSuffix(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                is ObservedAdvertFrame.OtherFrameType -> Text(
                                    frame.label + frame.estimatedIntervalMillis.intervalSuffix(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                is ObservedAdvertFrame.Unrecognized -> Text(
                                    "Unrecognized advertisement (not a name, not a decodable " +
                                        "iBeacon frame, not Eddystone)" + frame.estimatedIntervalMillis.intervalSuffix() +
                                        ":\n${frame.rawHex}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = withClickSound { decodedAdvertForAddress = null }) { Text("Close") } },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = withClickSound(viewModel::startScan), enabled = !uiState.isBusy && !uiState.isScanning) { Text("Scan") }
            OutlinedButton(onClick = withClickSound(viewModel::stopScan), enabled = uiState.isScanning) { Text("Stop") }
            if (uiState.isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Scanning…", style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (uiState.scanResults.isNotEmpty()) {
                Text(
                    "Tap a beacon to connect:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            uiState.scanResults.forEach { beacon ->
                Card(onClick = withClickSound { viewModel.connect(beacon) }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${beacon.displayName ?: "(unnamed)"} — ${beacon.address} — ${beacon.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        // Always shown — tapping it is informative either way: whatever distinct
                        // advertisement frames have been seen so far (possibly none — see
                        // ScannedBeaconUi.frames's own doc).
                        IconButton(onClick = withClickSound { decodedAdvertForAddress = beacon.address }) {
                            Icon(Icons.Filled.Info, contentDescription = "Show decoded advertisement")
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Connect")
                    }
                }
            }
            if (uiState.scanResults.isEmpty()) {
                val message = if (uiState.isScanning) "No beacons found yet…" else "No beacons found yet — tap Scan."
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (uiState.showManualPasswordField) {
            HorizontalDivider()
            Text(
                "Connected, but authentication failed with the known passwords.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = uiState.manualPasswordInput,
                    onValueChange = viewModel::updateManualPassword,
                    label = { Text("Password (6 chars)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = withClickSound(viewModel::submitManualPassword),
                    // Firmware's password field is a fixed 6 characters — anything shorter is
                    // guaranteed to be rejected, so don't let the operator submit it and wait on
                    // a doomed round trip.
                    enabled = !uiState.isBusy && uiState.manualPasswordInput.length == DxCp27Protocol.PASSWORD_LENGTH,
                ) { Text("Try") }
            }
        }
    }
}

@Composable
private fun ConnectedSection(uiState: BeaconUiState, viewModel: BeaconProgrammerViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Connected to ${uiState.connectedDeviceLabel ?: "beacon"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            // Deliberately not gated on isCalibrating too — viewModel.disconnect() already
            // cancels any in-progress calibration itself before disconnecting, and calibration
            // can run indefinitely (it's an open-ended, user-stoppable listen), so Disconnect
            // must stay a working escape hatch regardless of what's running.
            OutlinedButton(onClick = withClickSound(viewModel::disconnect), enabled = !uiState.isBusy) { Text("Disconnect") }
        }

        uiState.config?.let { config -> CurrentConfigSection(config) }

        HorizontalDivider()
        Text("Program this beacon", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = uiState.measuredPowerInput,
                onValueChange = viewModel::updateMeasuredPowerInput,
                label = { Text("Measured power @1m (-dBm)") },
                isError = uiState.measuredPowerError != null,
                supportingText = uiState.measuredPowerError?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            if (uiState.isCalibrating) {
                OutlinedButton(onClick = withClickSound(viewModel::stopCalibration)) { Text("Stop") }
            } else {
                Button(onClick = withClickSound(viewModel::startCalibration), enabled = !uiState.isBusy) { Text("Calibrate") }
            }
        }
        if (uiState.isCalibrating) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    "Listening for advertisements (${uiState.calibrationSampleCount} seen) — watch the log, tap Stop when ready.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                "Hold the beacon exactly 1 metre from this phone, then tap Calibrate.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = uiState.minorInput,
            onValueChange = viewModel::updateMinorInput,
            label = { Text("Beacon Number (0-999)") },
            isError = uiState.minorError != null,
            supportingText = uiState.minorError?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = withClickSound(viewModel::requestProgram),
                enabled = uiState.canProgram,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f),
            ) { Text("Set Program", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            OutlinedButton(
                onClick = withClickSound(viewModel::requestPowerOff),
                enabled = !uiState.isBusy && !uiState.isCalibrating,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f),
            ) { Text("Power Off", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            OutlinedButton(
                onClick = withClickSound(viewModel::requestFactoryReset),
                enabled = !uiState.isBusy && !uiState.isCalibrating,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f),
            ) { Text("Factory Reset", style = MaterialTheme.typography.labelSmall, maxLines = 1) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (uiState.isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Working…", style = MaterialTheme.typography.bodySmall)
            } else {
                uiState.programResult?.let { result ->
                    val color = if (result.outcome == ProgramOutcome.SUCCESS) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                    Icon(
                        if (result.outcome == ProgramOutcome.SUCCESS) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        contentDescription = result.message,
                        tint = color,
                    )
                    Text(result.message, style = MaterialTheme.typography.bodySmall, color = color)
                }
            }
        }
    }
}

@Composable
private fun CurrentConfigSection(config: mobile.racemaster.data.beacon.BeaconConfig) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Current configuration", style = MaterialTheme.typography.titleSmall)
        Text(
            "Battery: ${config.deviceInfo.batteryMillivolts} mV (~${config.deviceInfo.batteryPercent}%)",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "${config.deviceInfo.manufacturer ?: "?"} ${config.deviceInfo.model ?: "?"} " +
                "(${config.deviceInfo.chip ?: "?"}) FW ${config.deviceInfo.firmware ?: "?"} " +
                "SW ${config.deviceInfo.software ?: "?"} — MAC ${config.deviceInfo.mac}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        config.deviceNameFrame?.let { frame ->
            Text(
                "Name: \"${frame.name}\", interval ${frame.intervalRaw}, " +
                    "TX power ${frame.txPowerRaw}, trigger ${frame.triggerDescription}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Slots: " + config.slots.joinToString(", ") { slot: SlotSummary -> "${slot.slot}=${slot.label}" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Newest entry first, oldest last — so the latest status is always right under the "Log"
 *  header. Auto-scrolls to reveal each new entry only while the operator is actually looking at
 *  the top of the list (see [pinnedToTop] below) — scrolled away to read older entries, it leaves
 *  the view alone rather than yanking a line they're mid-read on out from under them. Either way
 *  the list's own bounded height (below) never grows to fit its content — only the content
 *  scrolls inside it, the surrounding layout never reflows. */
@Composable
private fun LogPanel(log: List<BeaconLogEntry>, onClear: () -> Unit) {
    val listState = rememberLazyListState()
    // True once the operator has scrolled back up to (or never away from) the very top. Derived
    // only from real scroll gestures (the isScrollInProgress transition below), not read fresh on
    // every new log entry — LazyColumn's own key-based scroll-position preservation shifts
    // firstVisibleItemIndex by itself once a new item lands at index 0, which would otherwise make
    // "was at the top" look false the instant it stops being true for the wrong reason.
    var pinnedToTop by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            if (!inProgress) {
                pinnedToTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            }
        }
    }
    LaunchedEffect(log.size) {
        if (pinnedToTop && log.isNotEmpty()) listState.animateScrollToItem(0)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = withClickSound(onClear), enabled = log.isNotEmpty()) { Text("Clear") }
        }
        // Bounded height — this LazyColumn sits inside the screen's own outer verticalScroll
        // Column, which hands it an infinite height constraint and crashes
        // ("vertically scrollable component was measured with infinity maximum height") unless
        // its own height is capped here. Fixed, not just capped — heightIn(max=) alone would let
        // a short log leave the box short too, shrinking and growing the layout around it as
        // entries are cleared/added; a plain height() keeps the scroll area's own footprint
        // constant regardless of entry count.
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(320.dp).padding(top = 4.dp)) {
            items(log.asReversed(), key = { it.id }) { entry -> LogRow(entry) }
        }
    }
}

@Composable
private fun LogRow(entry: BeaconLogEntry) {
    val tagColor = when (entry.direction) {
        BeaconLogDirection.TX -> MaterialTheme.colorScheme.primary
        BeaconLogDirection.RX -> MaterialTheme.colorScheme.tertiary
        BeaconLogDirection.ERROR -> MaterialTheme.colorScheme.error
        BeaconLogDirection.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        "${entry.direction} ${entry.note}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = tagColor,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
