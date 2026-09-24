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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mobile.racemaster.data.db.entity.formatLineColumn
import mobile.racemaster.data.db.entity.formatLineRef
import mobile.racemaster.data.db.entity.formatSplitColumn
import mobile.racemaster.data.db.entity.formatSplitRef
import mobile.racemaster.data.repository.LineSyncState
import mobile.racemaster.ui.theme.RelayedOrange
import mobile.racemaster.ui.theme.SyncedGreen
import mobile.racemaster.ui.theme.UnsyncedRed
import mobile.racemaster.util.formatElapsedSplitTime
import mobile.racemaster.util.formatTimeOfDay
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
 * The primary row's six columns (line #, split #, action, bib, elapsed, wall-clock time) are
 * all a compact-phone-width can reliably fit side by side — the time column is deliberately
 * terse ("HH:mm", no seconds) and capped at a single line (maxLines = 1) so a long locale-
 * specific rendering clips rather than wrapping the row onto a second visual line. The note
 * slot (whichever single piece of context is most relevant: a genuine operator note, or — for
 * an undo marker, which never has a real note of its own — a synthesized "Undo L{n}" pointing
 * at the line it hid) gets its own full-width line below instead of competing with them, same
 * as the other secondary lines (duplicate-bib flags, an edit-echo's "Edited from", "Synced to").
 */
@Composable
fun HistoryLineRow(
    lineNumber: Long,
    splitNumber: Int?,
    actionLabel: String,
    bibNumber: Int?,
    elapsedMillis: Long?,
    timestampMillis: Long,
    note: String?,
    syncState: LineSyncState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    syncedToLabel: String? = null,
    dupSplitRefs: List<Int?> = emptyList(),
    editedFromLineNumber: Long? = null,
    isUndoMarker: Boolean = false,
    // Verbatim text for the split column in place of formatSplitColumn(splitNumber) — used only
    // for the MODE_START boundary-marker row (see HistoryAction.MODE_START's own doc), whose
    // split slot names which mode started rather than showing a split number it never had.
    splitLabelOverride: String? = null,
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
        // Read via LocalConfiguration rather than Locale.getDefault() directly — the latter
        // isn't observable by Compose, so this row wouldn't recompose if the user changes their
        // system locale mid-session (same pattern as ServerStatusLine/SyncStatusLine).
        val locale = LocalConfiguration.current.locales[0]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatLineColumn(lineNumber), style = MaterialTheme.typography.bodySmall, color = rowColor, modifier = Modifier.width(38.dp))
            Text(
                splitLabelOverride ?: formatSplitColumn(splitNumber),
                style = MaterialTheme.typography.bodySmall,
                color = rowColor,
                modifier = Modifier.width(38.dp),
            )
            Text(
                actionLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = rowColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(60.dp),
            )
            Text(
                // "–" for any row with no bib # — a Time row (which never has one at all) and a
                // Bibs/CP boundary marker (Clock/Stop/Reset/etc., which has no bib of its own)
                // are shown identically, matching every other null field in this row (and the
                // racemaster web app's own Mobile Files table) rather than singling bib out with
                // its own "n/a" sentinel.
                bibNumber?.toString() ?: "–",
                style = MaterialTheme.typography.bodyMedium,
                color = rowColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(30.dp),
            )
            Text(
                elapsedMillis?.let { formatElapsedSplitTime(it) } ?: "–",
                style = MaterialTheme.typography.bodyMedium,
                color = rowColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatTimeOfDay(timestampMillis, locale),
                style = MaterialTheme.typography.bodySmall,
                color = rowColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(38.dp),
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
    val splitNumber: Int?,
    val actionLabel: String,
    val bibNumber: Int?,
    val elapsedMillis: Long?,
    val timestampMillis: Long,
    val note: String?,
    val syncState: LineSyncState,
    val syncedToLabel: String? = null,
    val dupSplitRefs: List<Int?> = emptyList(),
    val isUndoMarker: Boolean = false,
    val editedFromLineNumber: Long? = null,
    // See HistoryLineRow's own splitLabelOverride param doc.
    val splitLabelOverride: String? = null,
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
                    timestampMillis = line.timestampMillis,
                    note = line.note,
                    syncState = line.syncState,
                    syncedToLabel = line.syncedToLabel,
                    dupSplitRefs = line.dupSplitRefs,
                    editedFromLineNumber = line.editedFromLineNumber,
                    isUndoMarker = line.isUndoMarker,
                    splitLabelOverride = line.splitLabelOverride,
                )
            }
        }
    }
}
