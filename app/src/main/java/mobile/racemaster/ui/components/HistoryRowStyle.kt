package mobile.racemaster.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import mobile.racemaster.data.db.entity.formatLineRef
import mobile.racemaster.data.db.entity.formatSplitRef
import mobile.racemaster.data.repository.LineSyncState
import mobile.racemaster.ui.theme.RelayedOrange
import mobile.racemaster.ui.theme.SyncedGreen
import mobile.racemaster.ui.theme.UnsyncedRed
import mobile.racemaster.util.withClickSound

/** Shared by every history-line row (SplitRow, BibEntryRow, HistoryLineRow) — red until relayed
 *  to a mule, orange until that reaches a genuine sink, green once it does (see LineSyncState). */
fun rowColorFor(syncState: LineSyncState): Color = when (syncState) {
    LineSyncState.SYNCED -> SyncedGreen
    LineSyncState.RELAYED -> RelayedOrange
    LineSyncState.NOT_SYNCED -> UnsyncedRed
}

/** The outer [Modifier] every history-line row wraps its content in — clickable only when
 *  [onClick] is given (read-only contexts leave it null), with the same compact vertical
 *  padding every row uses to fit as many as possible on screen at once. */
@Composable
fun historyRowModifier(modifier: Modifier, onClick: (() -> Unit)?): Modifier =
    modifier
        .fillMaxWidth()
        .let { if (onClick != null) it.clickable(onClick = withClickSound(onClick)) else it }
        .padding(vertical = 2.dp)

/** The trailing, full-width lines shared by every history-line row below its primary columns —
 *  a duplicate-bib flag, an unexpected-bib warning (Bibs only), an edit-echo's "Edited from",
 *  and "Synced to" — always in this order, each shown only when it applies. */
@Composable
fun ColumnScope.HistoryRowTrailingLines(
    rowColor: Color,
    dupSplitRefs: List<Int?> = emptyList(),
    expectationWarning: String? = null,
    editedFromLineNumber: Long? = null,
    syncedToLabel: String? = null,
) {
    if (dupSplitRefs.isNotEmpty()) {
        Text(
            "dup of ${dupSplitRefs.joinToString(", ") { formatSplitRef(it) }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (!expectationWarning.isNullOrBlank()) {
        Text(expectationWarning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (editedFromLineNumber != null) {
        Text("Edited from ${formatLineRef(editedFromLineNumber)}", style = MaterialTheme.typography.bodySmall, color = rowColor)
    }
    if (!syncedToLabel.isNullOrBlank()) {
        Text("Synced to: $syncedToLabel", style = MaterialTheme.typography.bodySmall, color = rowColor)
    }
}
