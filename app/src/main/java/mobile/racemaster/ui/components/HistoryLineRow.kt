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
import mobile.racemaster.data.db.entity.formatLineColumn
import mobile.racemaster.data.db.entity.formatLineRef
import mobile.racemaster.data.db.entity.formatSplitColumn
import mobile.racemaster.data.db.entity.formatSplitRef
import mobile.racemaster.ui.theme.SyncedGreen
import mobile.racemaster.ui.theme.UnsyncedRed
import mobile.racemaster.ui.timemode.formatElapsed
import mobile.racemaster.util.withClickSound

/**
 * One rationalized row format for every history line, Bibs or Time, in every screen that
 * shows a race's full (not just current-segment) chronology — Race History and a Mule
 * source's own detail screen. Every line shows exactly the same columns: permanent line
 * number, per-segment split number, action, bib number, elapsed time — with whichever of
 * bib/time doesn't apply to this row's mode shown as "–" rather than omitted, so the column
 * that follows never shifts depending on which mode a given row happens to be. There's no
 * longer a separate mode-prefixed "line label" (the old "B003"/"T012") — the bib/time columns
 * already say which mode a row belongs to just by which one is populated.
 *
 * The primary row's five columns are all a compact-phone-width can reliably fit side by side
 * — the note slot (whichever single piece of context is most relevant: a genuine operator
 * note, or — for an undo marker, which never has a real note of its own — a synthesized
 * "Undo L{n}" pointing at the line it hid) gets its own full-width line below instead of
 * competing with them, same as the other secondary lines (duplicate-bib flags, an edit-echo's
 * "Edited from", "Synced to").
 */
@Composable
fun HistoryLineRow(
    lineNumber: Long,
    splitNumber: Int,
    actionLabel: String,
    bibNumber: Int?,
    elapsedMillis: Long?,
    note: String?,
    synced: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    syncedToLabel: String? = null,
    dupSplitRefs: List<Int> = emptyList(),
    editedFromLineNumber: Long? = null,
    isUndoMarker: Boolean = false,
) {
    val rowColor = if (synced) SyncedGreen else UnsyncedRed
    Column(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = withClickSound(onClick)) else it }
            .padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatLineColumn(lineNumber), style = MaterialTheme.typography.bodySmall, color = rowColor, modifier = Modifier.width(40.dp))
            Text(formatSplitColumn(splitNumber), style = MaterialTheme.typography.bodySmall, color = rowColor, modifier = Modifier.width(40.dp))
            Text(actionLabel, style = MaterialTheme.typography.bodyMedium, color = rowColor, modifier = Modifier.width(64.dp))
            Text(
                // elapsedMillis == null is exactly "this is a Bibs-family row" (both callers
                // only ever pass a real elapsed time for a Time row) — "n/a" for a Bibs row with
                // no bib # (Clock/Stop/Reset/etc.) visually distinguishes a genuinely inapplicable
                // bib from "–", which instead marks the structural cross-mode absence (this is a
                // Time row, which never has a bib # at all).
                bibNumber?.toString() ?: if (elapsedMillis == null) "n/a" else "–",
                style = MaterialTheme.typography.bodyMedium,
                color = rowColor,
                modifier = Modifier.width(32.dp),
            )
            Text(
                elapsedMillis?.let { formatElapsed(it) } ?: "–",
                style = MaterialTheme.typography.bodyMedium,
                color = rowColor,
                modifier = Modifier.weight(1f),
            )
        }
        val noteText = if (isUndoMarker) "Undo ${formatLineRef(editedFromLineNumber ?: lineNumber)}" else note
        if (!noteText.isNullOrBlank()) {
            Text(noteText, style = MaterialTheme.typography.bodySmall, color = rowColor)
        }
        if (dupSplitRefs.isNotEmpty()) {
            Text(
                "dup of ${dupSplitRefs.joinToString(", ") { formatSplitRef(it) }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!isUndoMarker && editedFromLineNumber != null) {
            Text("Edited from ${formatLineRef(editedFromLineNumber)}", style = MaterialTheme.typography.bodySmall, color = rowColor)
        }
        if (!syncedToLabel.isNullOrBlank()) {
            Text("Synced to: $syncedToLabel", style = MaterialTheme.typography.bodySmall, color = rowColor)
        }
    }
}

/** Everything [HistoryLineRow] needs, independent of whether the line came from a local
 *  race (Race History) or a Mule-pulled source (Mule source detail) — lets both screens
 *  render through the exact same [HistoryLinesList] rather than each hand-rolling its own
 *  forEach/HistoryLineRow loop. */
data class HistoryLineDisplay(
    val lineNumber: Long,
    val splitNumber: Int,
    val actionLabel: String,
    val bibNumber: Int?,
    val elapsedMillis: Long?,
    val note: String?,
    val synced: Boolean,
    val syncedToLabel: String? = null,
    val dupSplitRefs: List<Int> = emptyList(),
    val isUndoMarker: Boolean = false,
    val editedFromLineNumber: Long? = null,
)

/** Renders [lines] via [HistoryLineRow], or [emptyMessage] if there are none — the one shared
 *  list-rendering function both Race History and Mule source detail use, so a pulled race's
 *  history looks exactly like a local race's own (no per-line device name; the source's
 *  device is already named once, in that screen's own header — see MuleSourceDetailScreen). */
@Composable
fun HistoryLinesList(
    lines: List<HistoryLineDisplay>,
    emptyMessage: String,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) {
        Text(emptyMessage, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
    } else {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lines.forEach { line ->
                HistoryLineRow(
                    lineNumber = line.lineNumber,
                    splitNumber = line.splitNumber,
                    actionLabel = line.actionLabel,
                    bibNumber = line.bibNumber,
                    elapsedMillis = line.elapsedMillis,
                    note = line.note,
                    synced = line.synced,
                    syncedToLabel = line.syncedToLabel,
                    dupSplitRefs = line.dupSplitRefs,
                    editedFromLineNumber = line.editedFromLineNumber,
                    isUndoMarker = line.isUndoMarker,
                )
            }
        }
    }
}
