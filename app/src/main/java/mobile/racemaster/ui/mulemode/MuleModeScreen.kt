package mobile.racemaster.ui.mulemode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.ui.components.CompactTopAppBarHeight
import mobile.racemaster.ui.components.ServerStatusLine
import mobile.racemaster.ui.components.SyncStatusLine
import mobile.racemaster.ui.theme.SyncedGreen
import mobile.racemaster.ui.theme.UnsyncedRed
import mobile.racemaster.util.formatTimeOfDay
import mobile.racemaster.util.formatWallClock
import mobile.racemaster.util.withClickSound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuleModeScreen(
    onChangeMode: () -> Unit,
    onOptions: () -> Unit,
    viewModel: MuleModeViewModel = viewModel(factory = MuleModeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mule Mode") },
                // Bluetooth/server-sync toggles and the force-sync/auto-sync controls now live
                // on the shared Options screen (reachable from every mode via Setup Device) —
                // this used to be a button inline in the body, which cost a whole row of
                // vertical space this screen's own status dashboard needs more (see the
                // three "is everything working" lines just below in the body).
                actions = {
                    TextButton(onClick = withClickSound(onOptions)) { Text("Options") }
                    TextButton(onClick = withClickSound(onChangeMode)) { Text("Mode") }
                },
                expandedHeight = CompactTopAppBarHeight,
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        // MainActivity's outer Scaffold (no bottomBar) already reserves the navigation
        // bar's bottom inset for every screen — without this, this inner Scaffold's own
        // default contentWindowInsets reserves it a second time, wasting a whole nav-bar
        // height of blank space above the system bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Mule Mode's layout is due a larger rework — parked here plainly for now.
            if (!deviceName.isNullOrBlank()) {
                Text(text = "Device name: $deviceName", style = MaterialTheme.typography.labelMedium)
            }
            SyncStatusLine(uiState.unsyncedCount, uiState.lastSyncedAtMillis)
            ConnectivityStatusText(uiState)
            // The three "is everything actually working" feedback groups: this device's own
            // data reaching the server, a Bluetooth sink (the web app) reading from this
            // device, and — down in NearbyDevicesSection, per row rather than aggregated here —
            // this device pulling from every nearby device. An operator judges each by how
            // stale it looks, exactly the same way they already judge NearbyDevicesSection's
            // own red/green colouring; none of these carries its own separate warning state.
            ServerStatusLine(uiState.serverStatus)
            LastPushLine(uiState)
            WebAppStatusLines(uiState)

            uiState.statusMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = withClickSound(viewModel::dismissStatusMessage)),
                )
            }

            NearbyDevicesSection(uiState, onForget = viewModel::forgetDevice)
        }
    }
}

// Two independent toggles, side by side: turning Bluetooth off takes the whole radio
// presence down (not scanning, not advertising, not answerable by any other Mule) without
// affecting server sync; turning server sync off stops pushing to/checking the server
// without affecting BLE device-to-device sync. Both are also independent of the Auto-sync
// stop/resume toggle below (which only pauses this device's own pull/push loop while it
// keeps scanning/advertising/serving).
// Plain feedback text, no controls — shared between this screen's own toggles below and
// SetupDeviceScreen's read-only status summary (see that screen's own doc for why it needs
// the same feedback without needing the actual toggle buttons alongside it).
@Composable
internal fun ConnectivityStatusText(uiState: MuleModeUiState) {
    // Read via LocalConfiguration rather than Locale.getDefault() directly — see
    // formatTimeOfDay's own doc for why (Compose only recomposes on the former).
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (uiState.bluetoothOff) {
            Text(
                "Bluetooth: OFF — not visible to nearby devices",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            // "Pulling" is only ever true for the active BT puller (see MuleSyncEngine's own
            // doc on the source/sink role split) — a Time/Bibs/CP phone only advertises and
            // serves reads, it never scans or pulls anything itself, so this text must not
            // claim otherwise just because this composable is now shared across every mode.
            Text(
                if (uiState.isMuleMode) {
                    "Bluetooth: ON — pulling data from nearby devices"
                } else {
                    "Bluetooth: ON — visible to nearby Mule devices"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // Distinct from bluetoothWarning below (this device failing to *scan*) — this is
            // this device failing to be *seen*, reported by PeripheralSyncService via
            // BluetoothStateRepository.advertisingWarning. Confirmed in the field: a plain
            // Bluetooth off/on toggle doesn't always clear it (the underlying BLE chipset
            // firmware itself can get wedged), which is why the message names a phone restart
            // explicitly rather than leaving the operator to guess.
            uiState.advertisingWarning?.let { warning ->
                Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            // This phone's own recent BLE central connect success rate — see ConnectHealth's
            // own doc for why this matters: some phones' peripheral role (the "ON — visible to
            // nearby Mule devices" line just above, and every other branch here) works fine
            // while their central role (this phone actively pulling, isMuleMode only) fails on
            // most connect attempts, purely a chipset/driver limitation confirmed in the field
            // — no code fix can improve it, but an operator who can see this can swap to a
            // different phone as Mule before a race rather than mid-event. Gated on
            // recentAttempts > 0 so a Mule that's only just started (nothing to report yet)
            // doesn't show either line. The "since HH:mm" span (oldestAttemptAtMillis) matters
            // because a real reconnect to an already-resolved peer is throttled to roughly once
            // a minute — see ConnectHealth's own doc — so this window can genuinely take several
            // minutes to fill even on a healthy Mule; without the span, a bare count reads as
            // "just now" regardless of how stale it actually is. Always the *failure* count, in
            // both the healthy and struggling case — a fraction that means "successes" in one
            // branch and "failures" in the other (this used to read "6/6 succeeded" alongside
            // "13/20 failed") makes an operator do arithmetic to tell if a number is good news;
            // one consistent "N bad" convention means 0 always reads as good news outright.
            if (uiState.connectHealth.recentAttempts > 0) {
                val health = uiState.connectHealth
                val sinceText = health.oldestAttemptAtMillis?.let { " since ${formatTimeOfDay(it, locale)}" }.orEmpty()
                val healthText = "Connection health: ${health.recentFailures}/${health.recentAttempts} bad$sinceText"
                if (health.isStruggling) {
                    Text(
                        "$healthText — try a different Mule phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(healthText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (uiState.serverSyncOff) {
            Text(
                "Server sync: OFF — not pushing to the server",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (!uiState.isLoggedIn) {
            Text(
                "Not logged in — tap Setup Server to configure the server URL and log in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                "Server sync: ON — pushing data to the server",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun BluetoothAndServerSyncToggles(
    uiState: MuleModeUiState,
    viewModel: MuleModeViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ConnectivityStatusText(uiState)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (uiState.bluetoothOff) {
                Button(onClick = withClickSound(viewModel::turnBluetoothOn)) { Text("Bluetooth off") }
            } else {
                OutlinedButton(onClick = withClickSound(viewModel::turnBluetoothOff)) { Text("Bluetooth on") }
            }
            if (uiState.serverSyncOff) {
                Button(onClick = withClickSound(viewModel::turnServerSyncOn)) { Text("Server sync off") }
            } else {
                OutlinedButton(onClick = withClickSound(viewModel::turnServerSyncOff)) { Text("Server sync on") }
            }
        }
    }
}

// This device's own data reaching the server — distinct from SyncStatusLine's "last synced"
// above, which also counts a BLE sink confirming a record (see
// MuleRepository.lastPushAttemptAtMillis's own doc for why that conflation isn't good enough
// here). Shared with SetupOptionsScreen's AutoSyncControls (right next to the Force sync
// now/Stop auto-sync buttons that actually drive it) so there's one copy of this text, not two
// that could drift.
@Composable
internal fun LastPushLine(uiState: MuleModeUiState) {
    Text(
        if (uiState.lastPushAttemptAtMillis == null && !uiState.isLoggedIn) {
            "Last push to server: never (no server)"
        } else {
            "Last push to server: ${uiState.lastPushAttemptAtMillis?.let { formatWallClock(it) } ?: "never"}"
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

// A Bluetooth-connected sink (currently only ever the racemaster web app's own BLE client — see
// AckPayload.isSink's own doc) reading from THIS device — separate from NearbyDevicesSection's
// own per-row "Last seen"/"Last pulled" pair, which is about this device pulling from others.
// "Last seen" here mirrors that same pair's own naming (any contact, not necessarily new data);
// "Last pushed" — not "pulled" — since named from this device's own point of view, that's this
// device's data actually reaching the web app, the same direction LastPushLine names for the
// server. Plain timestamps, no separate connected/stopped tracking of their own — see
// BluetoothStateRepository.lastWebAppSeenAtMillis/lastWebAppPushedAtMillis's own docs for why.
@Composable
internal fun WebAppStatusLines(uiState: MuleModeUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
            "Web app last seen: ${uiState.lastWebAppSeenAtMillis?.let { formatWallClock(it) } ?: "never"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Web app last pushed to: ${uiState.lastWebAppPushedAtMillis?.let { formatWallClock(it) } ?: "never"}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun NearbyDevicesSection(uiState: MuleModeUiState, onForget: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Split direct (real BLE visibility) from relayed (known only transitively, via
        // another Mule) once any relaying is actually happening — a raw combined count would
        // overstate how many devices are genuinely in range right now. Falls back to today's
        // exact wording while relayCount is 0, so this is a visual no-op until a chain forms.
        // Used to pair with a separate aggregate "Last pull" timestamp here too, but that was
        // exactly the max of what every row's own "Last pulled" line below already shows —
        // redundant once this list started carrying that per row, so only the count survives.
        val directDeviceCount = uiState.discoveredDevices.count { !it.isSelf && !it.isStale && it.relayedViaDeviceName == null }
        val relayDeviceCount = uiState.discoveredDevices.count { it.relayedViaDeviceName != null }
        val countSuffix = if (relayDeviceCount > 0) {
            "$directDeviceCount nearby, $relayDeviceCount relayed"
        } else {
            "$directDeviceCount device${if (directDeviceCount == 1) "" else "s"}"
        }
        Text("Nearby devices ($countSuffix)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Red means it has unsynced data, green means it's all synced, grey means it's " +
                "gone quiet — most recently seen first.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (uiState.bluetoothOff) {
            Text("Bluetooth off — not scanning for nearby devices", style = MaterialTheme.typography.bodySmall)
        } else if (uiState.bluetoothWarning != null) {
            Text(
                uiState.bluetoothWarning,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (uiState.discoveredDevices.isEmpty()) {
            Text("Scanning…", style = MaterialTheme.typography.bodyMedium)
        }
        uiState.discoveredDevices.forEach { device ->
            // deviceName is always non-blank the moment any device (self or a genuinely
            // different BLE peer) has actually resolved — see PeripheralSyncService's own
            // advertise gate — so that alone would already suffice; raceLabel is the one
            // remaining case it doesn't cover: self, resolved, but with no active race and no
            // device name ever chosen yet.
            val hasReported = device.deviceName.isNotEmpty() || device.raceLabel.isNotEmpty()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    // A stale row (see DiscoveredDevice.isStale's own doc) always has a name —
                    // it only exists because it resolved at least once before — so this branches
                    // ahead of the live-only "Discovering…" case below. Neutral grey, not
                    // red/green: there's no current sync status to report, just a last-seen time,
                    // which is what differentiates it from a live row at a glance.
                    device.isStale -> Text(
                        "${device.deviceName} — last seen ${formatWallClock(device.lastReachableAtMillis)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    !hasReported -> Text(
                        "${device.deviceKey.take(8)} — Discovering…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    else -> {
                        val unsynced = device.unsyncedCount
                        // Below UNREACHABLE_FAILURE_THRESHOLD, a missed read is still just ordinary
                        // BLE noise (see DiscoveredDevice's own doc) — naming the running count here
                        // rather than staying silent lets the operator see a device is having trouble
                        // before it's actually flagged unreachable, without a shared banner that could
                        // only ever name one device at a time.
                        val suffix = when {
                            device.isSelf -> " (self)"
                            // A relay-only row has no direct BLE link of its own to be unreachable/missing
                            // reads on — those track this phone's own connection to a peer, meaningless
                            // for an origin only ever known transitively (see DiscoveredDevice's own doc).
                            device.relayedViaDeviceName != null -> " (via ${device.relayedViaDeviceName})"
                            device.unreachable -> " (unreachable)"
                            device.consecutiveFailures > 0 -> " (missed ${device.consecutiveFailures})"
                            else -> ""
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${device.deviceName.ifEmpty { device.deviceKey.take(8) }} — ${device.raceLabel.ifEmpty { "no race" }}$suffix",
                                style = MaterialTheme.typography.bodyMedium,
                                // Unreachable overrides whatever unsynced count was last read — that
                                // count is stale the moment a read fails, so trusting it would show a
                                // reassuring green right next to a "couldn't reach" warning.
                                color = if (device.unreachable || unsynced > 0) UnsyncedRed else SyncedGreen,
                            )
                            // Self pushes straight to the server rather than being "seen"/
                            // "pulled" by this device at all (see DiscoveredDevice.lastPulledAtMillis's
                            // own doc) — showing either against its own row would read as a
                            // malfunction rather than the expected, permanent state it is.
                            if (!device.isSelf) {
                                // Distinct from "Last pulled" just below: this bumps on any
                                // successful contact, whether or not it turned up new data, so
                                // it stays fresh for a device that's simply fully caught up —
                                // "Last pulled" alone would otherwise look stale even though
                                // this device is reaching it fine every check.
                                Text(
                                    "Last seen: ${formatWallClock(device.lastReachableAtMillis)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "Last pulled: ${device.lastPulledAtMillis?.let { formatWallClock(it) } ?: "never"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                // Can't forget yourself — there's no live/persisted entry for self to purge
                // (see MuleSyncEngine.selfDevice's own doc, it's synthesized fresh every time,
                // never folded into discoveredFlow or the known-devices roster at all). A plain
                // clickable Text, not a Button — Material's own minimum touch target on even a
                // TextButton was tall enough to visibly space out every row in this list.
                if (!device.isSelf) {
                    Text(
                        "Forget",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = withClickSound { onForget(device.deviceId ?: device.deviceKey) }),
                    )
                }
            }
        }
    }
}
