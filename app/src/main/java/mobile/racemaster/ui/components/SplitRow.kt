package mobile.racemaster.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mobile.racemaster.data.db.entity.formatLineRef
import mobile.racemaster.data.db.entity.formatSplitRef
import mobile.racemaster.data.repository.LineSyncState
import mobile.racemaster.ui.theme.RelayedOrange
import mobile.racemaster.ui.theme.SyncedGreen
import mobile.racemaster.ui.theme.UnsyncedRed
import mobile.racemaster.util.formatElapsedSplitTime
import mobile.racemaster.util.withClickSound

/** One line per Time Mode split — split number, elapsed time, action, note, matching Bibs
 *  Mode's own row layout (see BibEntryRow) column for column — the live Time Mode screen's own
 *  current-segment view (Race History uses the separate, more detailed HistoryLineRow instead,
 *  since it needs to show every mode/segment together). [splitNumber] is null for a Stop row
 *  (it never crosses any timing point of its own — see HistoryLineEntity.splitNumber's own
 *  doc), rendered as "–" via formatSplitRef same as any other row without one; [actionLabel]
 *  is what makes a Stop row (or a Start row) visibly distinct from a genuine split rather than
 *  looking like one. Sync state is shown by coloring the row (red until relayed to a mule,
 *  orange until that reaches a genuine sink, green once it does — see LineSyncState) rather
 *  than a separate status line, keeping rows compact enough that more fit on screen at once.
 *  [onClick] is left null in read-only contexts — the row just isn't clickable there. */
@Composable
fun SplitRow(
    splitNumber: Int?,
    elapsedMillis: Long,
    actionLabel: String,
    note: String?,
    syncState: LineSyncState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    syncedToLabel: String? = null,
    editedFromLineNumber: Long? = null,
) {
    val rowColor = when (syncState) {
        LineSyncState.SYNCED -> SyncedGreen
        LineSyncState.RELAYED -> RelayedOrange
        LineSyncState.NOT_SYNCED -> UnsyncedRed
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = withClickSound(onClick)) else it }
            .padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatSplitRef(splitNumber), style = MaterialTheme.typography.bodyLarge, color = rowColor, modifier = Modifier.width(40.dp))
            Text(formatElapsedSplitTime(elapsedMillis), style = MaterialTheme.typography.bodyLarge, color = rowColor, modifier = Modifier.width(72.dp))
            Text(actionLabel, style = MaterialTheme.typography.bodyLarge, color = rowColor, modifier = Modifier.weight(1f))
            if (!note.isNullOrBlank()) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = rowColor, modifier = Modifier.weight(1f))
            }
        }
        if (editedFromLineNumber != null) {
            Text("Edited from ${formatLineRef(editedFromLineNumber)}", style = MaterialTheme.typography.bodySmall, color = rowColor)
        }
        if (!syncedToLabel.isNullOrBlank()) {
            Text("Synced to: $syncedToLabel", style = MaterialTheme.typography.bodySmall, color = rowColor)
        }
    }
}
