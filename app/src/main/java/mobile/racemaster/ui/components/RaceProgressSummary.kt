package mobile.racemaster.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import mobile.racemaster.data.db.entity.formatSplitRef
import mobile.racemaster.data.mule.BtPollingStatus
import mobile.racemaster.data.mule.ServerStatusState
import mobile.racemaster.data.peersync.PeerSyncEngine
import mobile.racemaster.util.formatWallClockTime
import mobile.racemaster.util.withClickSound

/** The device/race/location/progress/sync info block common to every screen that shows a race
 *  actually being recorded against — Time and Bibs Mode's own live headers, and Mode Picker's
 *  own summary once a race has been set up — so this lives in exactly one place rather than
 *  being duplicated (or near-duplicated) across all three. Rendered directly into whatever
 *  Column the caller already has open (no Column of its own), matching how these lines were
 *  already laid out inline before this was extracted.
 *
 *  [progressText] is each caller's own "how much has been recorded so far" line — e.g. Time's
 *  `formatTimeSplitsText`, Bibs' `formatBibsSoFarText` — since the exact wording differs per mode
 *  but every mode always has one. [nextRowTrailingContent] lets a caller add something into the
 *  same Row as "Next: ..." before the sync status (e.g. [EntryModeHeaderInfo]'s own dup-count
 *  badge) without needing its own copy of that Row.
 *
 *  [showLocation]: false for Time Mode (see TODO.md's "A rationalisation" — "bibs (location
 *  aware) and time (as now)") — Time records irrespective of where the device is, so echoing
 *  "Location: ..." (and the "Also open" hint below it, which only ever means anything once
 *  something is location-partitioned) would just be misleading noise on its own screen.
 *
 *  [otherOpenLocations]/[onGoToLocation]: Resetting only ever closes the CURRENT location's own
 *  segment (see RaceRepository.closeCurrentSegment's own doc) — any OTHER location a Relocate
 *  earlier moved away from is left fully intact, but otherwise invisible from here except by
 *  retyping its exact name via Relocate (confirmed in the field as genuinely confusing — see
 *  RaceRepository.observeOpenLocations' own doc). When non-empty, shown as its own tappable
 *  line right under "Location: ..." that opens a small picker; picking one calls
 *  [onGoToLocation], which each mode's own ViewModel wires to a same-mode
 *  RaceRepository.recordModeStart, resuming that location exactly as Relocate would. Left empty/
 *  null by callers with nothing to offer here (Mode Picker's own pre-recording summary, and Time
 *  Mode, which never has any). */
@Composable
fun RaceProgressSummary(
    deviceName: String?,
    nearbyPeerNames: List<String> = emptyList(),
    // Count Mode's own feedback on the same-group peer-sync feature (TODO.md: "put the peer
    // activity feedback line under the nearby devices line and in the same style") — null (every
    // other caller) omits the line entirely, same as nearbyPeerNames' own empty-list convention.
    peerActivityCount: Int? = null,
    // Diagnostic aid, temporary — see CountModeUiState.peerActivityLatestAtMillis's own doc. Null
    // whenever peerActivityCount is null or no peer row has ever arrived.
    peerActivityLatestAtMillis: Long? = null,
    raceLabel: String,
    raceLocation: String,
    nextSplitNumber: Int,
    unsyncedCount: Int,
    lastSyncedAtMillis: Long?,
    serverStatus: ServerStatusState,
    btPollingStatus: BtPollingStatus,
    progressText: String,
    nextRowTrailingContent: @Composable RowScope.() -> Unit = {},
    showLocation: Boolean = true,
    // False for Count/Close (see CloseModeRepository/CountModeRepository's own doc) — neither
    // has a segmented split-counter concept, so [nextSplitNumber] is meaningless for them.
    showNextSplit: Boolean = true,
    otherOpenLocations: List<String> = emptyList(),
    onGoToLocation: ((String) -> Unit)? = null,
    // Non-empty only when there are more same-group peers nearby than PeerSyncEngine's own
    // MAX_SELECTED_PEERS cap — the inline picker below only ever renders then (see
    // PeerSyncEngine.peerChoices' own doc). Left empty/null by callers with no peer-sync feature
    // of their own (Time Mode, Mode Picker).
    peerChoices: List<PeerSyncEngine.SelectablePeer> = emptyList(),
    onPeerSelectedChanged: ((shortDeviceId: Long, selected: Boolean) -> Unit)? = null,
) {
    ServerStatusLine(serverStatus)
    BluetoothRadioStatusLine(btPollingStatus)
    BtPollingStatusLine(btPollingStatus)
    if (!deviceName.isNullOrBlank()) {
        Text(text = "Device name: $deviceName", style = MaterialTheme.typography.labelMedium)
    }
    // Same-mode peer-sync feature — other phones converging on this same race/mode/station right
    // now (see PeerSyncEngine.nearbyPeerNames' own doc). Omitted entirely rather than shown as
    // "none" when empty, matching every other optional line in this block.
    if (nearbyPeerNames.isNotEmpty()) {
        Text(text = "Nearby: ${nearbyPeerNames.joinToString(", ")}", style = MaterialTheme.typography.labelMedium)
    }
    // Only ever non-empty with more than one same-group peer nearby and no confirmed pair yet —
    // TODO.md's "restrict peers to 2... if more are visible the operator must select which one
    // to connect to" is a mesh-wide, mutual-agreement decision (see
    // PeerSyncEngine.resolvePairingOutcome's own doc), not a personal preference, so this needs
    // its own explanatory line rather than just appearing as unlabelled chips — confirmed in the
    // field as genuinely unreadable without one ("some highlighted but no indication what any of
    // it means"). Inline rather than a dialog (confirmed via this feature's own
    // AskUserQuestion) — nothing else on these screens needs a modal for this.
    if (peerChoices.isNotEmpty() && onPeerSelectedChanged != null) {
        val ownPickName = peerChoices.firstOrNull { it.isSelected }?.name
        Text(
            text = if (ownPickName == null) {
                "Too many nearby — tap the ONE phone to sync with:"
            } else {
                "Waiting for $ownPickName to also pick this phone back:"
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (choice in peerChoices) {
                FilterChip(
                    selected = choice.isSelected,
                    onClick = withClickSound { onPeerSelectedChanged(choice.shortDeviceId, !choice.isSelected) },
                    label = { Text(choice.name) },
                )
            }
        }
    }
    if (peerActivityCount != null) {
        val latestSuffix = peerActivityLatestAtMillis?.let { " (latest ${formatWallClockTime(it)})" } ?: ""
        Text(text = "Peer activity: $peerActivityCount lines received$latestSuffix", style = MaterialTheme.typography.labelMedium)
    }
    Text(text = "Race name: $raceLabel", style = MaterialTheme.typography.labelMedium)
    if (showLocation) {
        Text(text = "Location: $raceLocation", style = MaterialTheme.typography.labelMedium)
    }
    if (showLocation && otherOpenLocations.isNotEmpty() && onGoToLocation != null) {
        var showLocationPicker by remember { mutableStateOf(false) }
        Text(
            text = "Also open: ${otherOpenLocations.joinToString(", ")} (tap to go)",
            style = MaterialTheme.typography.labelMedium.copy(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = withClickSound { showLocationPicker = true }),
        )
        if (showLocationPicker) {
            OpenLocationPickerDialog(
                locations = otherOpenLocations,
                onSelect = {
                    showLocationPicker = false
                    onGoToLocation(it)
                },
                onDismiss = { showLocationPicker = false },
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        if (showNextSplit) {
            Text(
                text = "Next: ${formatSplitRef(nextSplitNumber)}",
                style = MaterialTheme.typography.labelMedium,
            )
        } else {
            Spacer(modifier = Modifier)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            nextRowTrailingContent()
            SyncStatusLine(unsyncedCount, lastSyncedAtMillis)
        }
    }
    Text(text = progressText, style = MaterialTheme.typography.labelMedium)
}

/** The "which still-open location do you want to go to" dialog behind RaceProgressSummary's own
 *  "Also open: ..." line — same small list-of-buttons shape as ActionPickerDialog
 *  (EntryLogEditing.kt), just over plain location strings instead of HistoryActions. */
@Composable
private fun OpenLocationPickerDialog(
    locations: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to location") },
        text = {
            Column {
                locations.forEach { location ->
                    TextButton(onClick = withClickSound { onSelect(location) }, modifier = Modifier.fillMaxWidth()) {
                        Text(location, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = withClickSound(onDismiss)) { Text("Cancel") } },
    )
}
