package mobile.racemaster.ui.countmode

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.data.repository.CountBeaconStatus
import mobile.racemaster.ui.components.RaceProgressSummary
import mobile.racemaster.ui.components.ResetButton
import mobile.racemaster.ui.components.ScreenTopBar
import mobile.racemaster.ui.help.HelpTopics
import mobile.racemaster.ui.theme.SyncedGreen
import mobile.racemaster.ui.theme.UnsyncedRed
import mobile.racemaster.util.formatTimeOfDay
import mobile.racemaster.util.withClickSound

/** Pre-start headcount — see [CountModeViewModel]'s own doc. One running count, a list of
 *  issued-but-not-yet-seen bibs — including any ejected beacon, tagged with its own "ejected"
 *  link (tap to un-eject, behind a yes/no confirm, see [CountModeViewModel.unejectBeacon]) and
 *  highlighted red while it's currently detected (the operator's cue this runner is back and
 *  ready to be un-ejected), or "was-ejected" once un-ejected but not yet re-seen — itself a
 *  tappable re-eject link (confirmed the same way) until it's genuinely re-seen, highlighted
 *  green while NOT currently detected (reusing [SyncedGreen]/[UnsyncedRed]'s own fixed,
 *  theme-independent "status at a glance" convention from Mule Mode's sync rows — a different
 *  status here, but the same "red needs attention, green is settled" reading) — and a "Seen"
 *  list, each row ejectable behind its own plain yes/no confirm (TODO.md: a previous version's
 *  dialog here "shows a confusing dialog", simplified). There is no bulk "Eject All" any more
 *  (TODO.md: redundant now there's Reset) and no other per-beacon dialog or result banner for an
 *  ordinary first-time detection, since counting one has no fallible action to report on the way
 *  a BLE connect/power-off does. "Stop" and "Reset" now sit side by side as body-level buttons
 *  (direct feedback: a text link buried in the top bar read as too easy to miss, or to hit by
 *  accident, for an action that ends the mode) — the top bar itself is the same
 *  [mobile.racemaster.ui.components.ScreenTopBar] every non-mode screen uses (Help/Back/Home,
 *  direct feedback: this screen used to be the odd one out with only one trailing icon), not
 *  [mobile.racemaster.ui.components.ModeScreenTopBar] (Bibs/Time's own secondary-action shape).
 *  Stop clears the race's mode back to null
 *  ([mobile.racemaster.data.repository.RaceRepository.stopMode]) — it's the only way
 *  to ever move this race out of Count mode once it's set up. Reset (reusing the shared
 *  [ResetButton] composable Bibs/Time already use) additionally closes out the current Count
 *  segment (see [CountModeViewModel.resetCountMode]'s own doc) so a restarted count genuinely
 *  starts from zero, rather than resuming whatever was already seen — Stop alone deliberately
 *  does not do this, since pausing without losing the running tally is still useful on its own.
 *  Both call [onHome] themselves immediately after (same reasoning for either): this screen's
 *  scan loop would otherwise immediately re-detect and re-record the very beacons just
 *  stopped/reset. Reached only via Routes.COUNT_MODE (Setup Race) — unlike Close, the Beacons
 *  hub's own "Count" button is no longer a standing shortcut straight to this screen (TODO.md:
 *  Count only ever makes sense against a race already set up for it, unlike Close — see
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
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val nearbyPeerNames by viewModel.nearbyPeerNames.collectAsStateWithLifecycle()
    val btPollingStatus by viewModel.btPollingStatus.collectAsStateWithLifecycle()
    var showStopConfirm by remember { mutableStateOf(false) }
    var pendingEjectBeaconNumber by remember { mutableStateOf<Int?>(null) }
    var pendingUnejectBeaconNumber by remember { mutableStateOf<Int?>(null) }
    // TODO.md "need to be able to see the peer lines pulled from nearby devices in the history
    // (as a diagnostic aid)" — collapsed by default (this is a diagnostic, not something an
    // operator needs open during ordinary counting).
    var showPeerActivity by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]

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

    pendingEjectBeaconNumber?.let { beaconNumber ->
        AlertDialog(
            onDismissRequest = { pendingEjectBeaconNumber = null },
            title = { Text("Eject beacon %03d?".format(beaconNumber)) },
            confirmButton = {
                Button(onClick = withClickSound {
                    pendingEjectBeaconNumber = null
                    viewModel.ejectBeacon(beaconNumber)
                }) { Text("Yes") }
            },
            dismissButton = {
                OutlinedButton(onClick = withClickSound { pendingEjectBeaconNumber = null }) { Text("No") }
            },
        )
    }

    // Un-eject's own confirm (TODO.md: "so the user can check they hit the right one") — same
    // yes/no shape as the eject dialog above.
    pendingUnejectBeaconNumber?.let { beaconNumber ->
        AlertDialog(
            onDismissRequest = { pendingUnejectBeaconNumber = null },
            title = { Text("Un-eject beacon %03d?".format(beaconNumber)) },
            confirmButton = {
                Button(onClick = withClickSound {
                    pendingUnejectBeaconNumber = null
                    viewModel.unejectBeacon(beaconNumber)
                }) { Text("Yes") }
            },
            dismissButton = {
                OutlinedButton(onClick = withClickSound { pendingUnejectBeaconNumber = null }) { Text("No") }
            },
        )
    }

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text("Count Mode") },
                // Back and Home both just navigate home — a mode screen's own back stack is
                // always exactly [ModePicker, this screen] (see RacemasterNavHost), so a single
                // pop and jumping straight to Mode Picker land on the same place; still shown as
                // two separate icons (direct feedback) to match every other screen's own
                // Help/Back/Home layout rather than standing out as the only screen missing one.
                onBack = onHome,
                onHome = onHome,
                helpTopic = HelpTopics.CountMode,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxSize(),
                // Top kept tight (vertical space is scarce — the gap under the top bar's title
                // should cost as little as possible); sides/bottom keep their original margin.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                // Tight by default (TODO.md: "the seen list is too sparse vertically") — section
                // headers below add their own extra top padding instead, so the list body itself
                // stays dense.
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    // Same shared feedback block Bibs/Time/Close Mode all use (server status,
                    // BT/Location status, device name, same-mode nearby peers, race name,
                    // location, unsynced count) — see RaceProgressSummary's own doc. showNextSplit
                    // false: Count has no segmented split-counter concept (same reasoning Close
                    // Mode's own call gives). progressText carries what used to be this item's own
                    // standalone "N of M yet to be seen" line, so nothing here is shown twice.
                    RaceProgressSummary(
                        deviceName = deviceName,
                        nearbyPeerNames = nearbyPeerNames,
                        raceLabel = uiState.raceLabel,
                        raceLocation = uiState.raceLocation,
                        nextSplitNumber = 0,
                        showNextSplit = false,
                        unsyncedCount = uiState.unsyncedCount,
                        lastSyncedAtMillis = uiState.lastSyncedAtMillis,
                        serverStatus = uiState.serverStatus,
                        btPollingStatus = btPollingStatus,
                        progressText = "${uiState.outstanding.size} of ${uiState.totalIssued} yet to be seen",
                    )
                    // Count's own distinctive large running tally — nothing else on this screen
                    // (or RaceProgressSummary) shows this number, so it stays its own prominent
                    // line rather than folding into progressText above.
                    Text(
                        "${uiState.uniqueBeaconsSeen} unique beacons seen",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
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
                        // Red while EJECTED and currently detected — TODO.md: "an ejected beacon
                        // that is visible should be highlighted" (the operator's cue that this
                        // runner is back and ready to be un-ejected). Green once un-ejected
                        // (WAS_EJECTED) and NOT currently detected — the settled state once an
                        // un-eject has genuinely taken, before a fresh detection clears the tag
                        // entirely. Reuses the same fixed, theme-independent SyncedGreen/
                        // UnsyncedRed pair Mule Mode's own sync rows use for an unrelated status,
                        // for the same "red needs attention, green is settled" reading.
                        val highlightColor = when {
                            outstanding.status == CountBeaconStatus.EJECTED && outstanding.currentlyVisible -> UnsyncedRed
                            outstanding.status == CountBeaconStatus.WAS_EJECTED && !outstanding.currentlyVisible -> SyncedGreen
                            else -> null
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Bib ${outstanding.bibNumber} — beacon %03d".format(outstanding.beaconNumber),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (highlightColor != null) FontWeight.Bold else FontWeight.Normal,
                                color = highlightColor ?: MaterialTheme.colorScheme.onSurface,
                            )
                            when (outstanding.status) {
                                // Tap to un-eject, behind its own confirm (TODO.md: "so the user
                                // can check they hit the right one") — an actionable link, not a
                                // button, matching this screen's other labelled-link convention.
                                CountBeaconStatus.EJECTED -> Text(
                                    "ejected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable(onClick = withClickSound { pendingUnejectBeaconNumber = outstanding.beaconNumber }),
                                )
                                // Also a link (TODO.md) — tap to re-eject, behind the same eject
                                // confirm the "Seen" list's own Eject link uses
                                // (CountModeViewModel.ejectBeacon accepts either a currently-seen
                                // or a WAS_EJECTED beacon). Clears on its own, same as before,
                                // once a fresh detection genuinely re-counts this beacon instead.
                                CountBeaconStatus.WAS_EJECTED -> Text(
                                    "was-ejected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable(onClick = withClickSound { pendingEjectBeaconNumber = outstanding.beaconNumber }),
                                )
                                null -> {}
                            }
                        }
                    }
                }
                // Seen beacons, each ejectable behind its own confirm (TODO.md: "a means to eject
                // one seen beacon" — a runner leaves and comes back later having forgotten their
                // kit, or the operator needs to un-count one entered by mistake).
                item {
                    Text(
                        "Seen",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                }
                items(uiState.seen, key = { it.beaconNumber }) { seen ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            (seen.bibNumber?.let { "Bib $it" } ?: "Bib ?") + " — beacon %03d".format(seen.beaconNumber) +
                                (seen.originDeviceName?.let { " (via $it)" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // A peer-sourced entry (same-mode peer-sync feature, Phase 4 — see
                        // SeenBeaconUi's own doc) has no local row of this device's own for
                        // Eject to target, so the link is withheld entirely rather than offering
                        // a tap that would just silently no-op.
                        if (seen.originDeviceName == null) {
                            Text(
                                "Eject",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(onClick = withClickSound { pendingEjectBeaconNumber = seen.beaconNumber }),
                            )
                        }
                    }
                }
                // TODO.md "need to be able to see the peer lines pulled from nearby devices in
                // the history (as a diagnostic aid)" — the raw feed behind statusByBeacon/
                // peerSeenOriginByBeacon above, shown here unfiltered/unfolded so an operator or
                // developer can confirm peer-sync is actually landing anything at all, without
                // needing an adb/sqlite pull.
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clickable(onClick = withClickSound { showPeerActivity = !showPeerActivity }),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Peer activity (diagnostic) — ${uiState.peerActivity.size}", style = MaterialTheme.typography.titleMedium)
                        Text(if (showPeerActivity) "hide" else "show", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                }
                if (showPeerActivity) {
                    if (uiState.peerActivity.isEmpty()) {
                        item {
                            Text("None yet.", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        items(uiState.peerActivity, key = { "${it.originDeviceName}:${it.timestampMillis}:${it.actionLabel}" }) { line ->
                            Text(
                                formatTimeOfDay(line.timestampMillis, locale) + " " + line.originDeviceName + " — " + line.actionLabel +
                                    (line.beaconNumber?.let { " beacon %03d".format(it) } ?: "") +
                                    (line.bibNumber?.let { " bib $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            // Stop and Reset side by side (direct feedback: Stop used to be a top-bar text
            // link, moved down here next to its own sibling action — see this screen's own doc).
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = withClickSound { showStopConfirm = true },
                    enabled = uiState.canStopCountMode,
                    modifier = Modifier.weight(1f),
                ) { Text("STOP") }
                ResetButton(
                    resetDescription = "Stops Count Mode and clears the running tally so it can be restarted from zero. Nothing is deleted — every beacon seen stays recorded in Race History.",
                    onReset = {
                        viewModel.resetCountMode()
                        onHome()
                    },
                    enabled = uiState.canStopCountMode,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
