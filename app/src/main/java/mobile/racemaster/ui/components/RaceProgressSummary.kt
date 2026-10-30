package mobile.racemaster.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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
    otherOpenLocations: List<String> = emptyList(),
    onGoToLocation: ((String) -> Unit)? = null,
) {
    ServerStatusLine(serverStatus)
    BtPollingStatusLine(btPollingStatus)
    if (!deviceName.isNullOrBlank()) {
        Text(text = "Device name: $deviceName", style = MaterialTheme.typography.labelMedium)
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
        Text(
            text = "Next: ${formatSplitRef(nextSplitNumber)}",
            style = MaterialTheme.typography.labelMedium,
        )
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
