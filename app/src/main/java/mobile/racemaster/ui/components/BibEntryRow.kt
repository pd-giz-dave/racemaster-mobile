package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mobile.racemaster.data.db.entity.formatSplitRef
import mobile.racemaster.data.repository.LineSyncState

/** One line per Bibs/CP Mode entry — split number, bib, type, note — the live screen's own
 *  current-segment view (Race History uses the separate, more detailed HistoryLineRow instead,
 *  since it needs to show every mode/segment together). Sync state is shown by coloring the row
 *  (see rowColorFor) rather than a separate status line, keeping rows compact enough that more
 *  fit on screen at once. [onClick] is left null in read-only contexts — the row just isn't
 *  clickable there. */
@Composable
fun BibEntryRow(
    splitNumber: Int?,
    bibNumber: Int?,
    typeLabel: String,
    note: String?,
    dupSplitRefs: List<Int?>,
    syncState: LineSyncState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    syncedToLabel: String? = null,
    editedFromLineNumber: Long? = null,
    expectationWarning: String? = null,
) {
    val rowColor = rowColorFor(syncState)
    Column(modifier = historyRowModifier(modifier, onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatSplitRef(splitNumber), style = MaterialTheme.typography.bodyLarge, color = rowColor, modifier = Modifier.width(40.dp))
            Text(bibNumber?.toString() ?: "–", style = MaterialTheme.typography.bodyLarge, color = rowColor, modifier = Modifier.width(48.dp))
            Text(typeLabel, style = MaterialTheme.typography.bodyLarge, color = rowColor, modifier = Modifier.weight(1f))
            if (!note.isNullOrBlank()) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = rowColor, modifier = Modifier.weight(1f))
            }
        }
        HistoryRowTrailingLines(
            rowColor,
            dupSplitRefs = dupSplitRefs,
            expectationWarning = expectationWarning,
            editedFromLineNumber = editedFromLineNumber,
            syncedToLabel = syncedToLabel,
        )
    }
}
