package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mobile.racemaster.data.db.entity.formatSplitRef
import mobile.racemaster.data.mule.BtPollingStatus
import mobile.racemaster.data.mule.ServerStatusState

/** The device/race/location/progress/sync info block common to every screen that shows a race
 *  actually being recorded against — Time, Bibs and CP Mode's own live headers, and Mode
 *  Picker's own summary once a race has been set up — so this lives in exactly one place rather
 *  than being duplicated (or near-duplicated) across all four. Rendered directly into whatever
 *  Column the caller already has open (no Column of its own), matching how these lines were
 *  already laid out inline before this was extracted.
 *
 *  [progressText] is each caller's own "how much has been recorded so far" line — e.g. Time's
 *  `formatTimeSplitsText`, Bibs/CP's `formatBibsSoFarText`/`formatCpSoFarText` — since the exact
 *  wording differs per mode but every mode always has one. [nextRowTrailingContent] lets a
 *  caller add something into the same Row as "Next: ..." before the sync status (e.g.
 *  [EntryModeHeaderInfo]'s own dup-count badge) without needing its own copy of that Row. */
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
) {
    ServerStatusLine(serverStatus)
    BtPollingStatusLine(btPollingStatus)
    if (!deviceName.isNullOrBlank()) {
        Text(text = "Device name: $deviceName", style = MaterialTheme.typography.labelMedium)
    }
    Text(text = "Race name: $raceLabel", style = MaterialTheme.typography.labelMedium)
    Text(text = "Location: $raceLocation", style = MaterialTheme.typography.labelMedium)
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
