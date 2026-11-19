package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mobile.racemaster.data.db.entity.formatLineColumn
import mobile.racemaster.data.db.entity.formatLineRange
import mobile.racemaster.data.db.entity.formatLineRef
import mobile.racemaster.data.db.entity.formatSplitColumn
import mobile.racemaster.data.repository.LineSyncState
import mobile.racemaster.util.formatElapsedSplitTime
import mobile.racemaster.util.formatTimeOfDay

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
    // Non-null only for a synthesized row representing a whole run of collapsed consecutive
    // Ping rows (see collapseConsecutivePings's own doc) — [lineNumber]/[timestampMillis] above
    // are that run's FIRST line, these two are its LAST, and the line-number/time columns below
    // render both ends as a range instead of a single value.
    collapsedThroughLineNumber: Long? = null,
    collapsedThroughTimestampMillis: Long? = null,
) {
    val rowColor = rowColorFor(syncState)
    Column(modifier = historyRowModifier(modifier, onClick)) {
        // Read via LocalConfiguration rather than Locale.getDefault() directly — the latter
        // isn't observable by Compose, so this row wouldn't recompose if the user changes their
        // system locale mid-session (same pattern as ServerStatusLine/SyncStatusLine).
        val locale = LocalConfiguration.current.locales[0]
        val isCollapsedRun = collapsedThroughLineNumber != null
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isCollapsedRun) {
                // A collapsed run's own 3-column layout, not the usual 6 below — split/bib/
                // elapsed are all constant/trivial for every Ping row (see
                // HistoryLineDisplay.isPing's own doc) and so have nothing worth a column of
                // their own here, freeing up the width "Lnnn - Lmmm"/"HH:mm - HH:mm" actually
                // need (confirmed in the field: squeezed into the single-value columns' usual
                // 38.dp width, both ranges were clipped mid-string rather than shown in full).
                Text(
                    formatLineRange(lineNumber, requireNotNull(collapsedThroughLineNumber)),
                    style = MaterialTheme.typography.bodySmall,
                    color = rowColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(100.dp),
                )
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = rowColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${formatTimeOfDay(timestampMillis, locale)} - ${formatTimeOfDay(requireNotNull(collapsedThroughTimestampMillis), locale)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = rowColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(120.dp),
                )
            } else {
                Text(
                    formatLineColumn(lineNumber),
                    style = MaterialTheme.typography.bodySmall,
                    color = rowColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(38.dp),
                )
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
                    // Wide enough for the longest label this column ever shows, "Count Seen"
                    // (HistoryActionLabels.kt) — confirmed in the field truncating at the
                    // previous 60.dp.
                    modifier = Modifier.width(85.dp),
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
        }
        val noteText = if (isUndoMarker) "Undo ${formatLineRef(editedFromLineNumber ?: lineNumber)}" else note
        if (!noteText.isNullOrBlank()) {
            Text(noteText, style = MaterialTheme.typography.bodySmall, color = rowColor)
        }
        HistoryRowTrailingLines(
            rowColor,
            dupSplitRefs = dupSplitRefs,
            // An undo marker's own "Undo L{n}" above already points at what it hid — showing
            // "Edited from" too (editedFromLineNumber is reused as that same pointer, see its
            // call site) would say the same thing twice.
            editedFromLineNumber = editedFromLineNumber.takeIf { !isUndoMarker },
            syncedToLabel = syncedToLabel,
        )
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
    // Set by each caller from its own `action == HistoryAction.PING` check (this class only
    // carries the already-localized [actionLabel] string, not the raw enum) — drives
    // [collapseConsecutivePings] below. A Ping row carries no bib/split/note of its own (see
    // HistoryAction.PING's own doc), so collapsing one run into a single displayed line loses
    // nothing a marshal would ever look for.
    val isPing: Boolean = false,
    // Set only by [collapseConsecutivePings], never by a caller directly — non-null means this
    // entry stands in for a whole run of collapsed Ping rows, [lineNumber]/[timestampMillis]
    // above being the run's first line and these two its last. See [HistoryLineRow]'s own
    // matching params.
    val collapsedThroughLineNumber: Long? = null,
    val collapsedThroughTimestampMillis: Long? = null,
)

/** Replaces every run of two-or-more *consecutive* entries (in [lines]'s own order — a
 *  non-Ping row in between breaks the run, same as a Ping row in a different [LineSyncState])
 *  that are both [HistoryLineDisplay.isPing] and share the same [HistoryLineDisplay.syncState]
 *  with a single entry spanning that run (TODO.md's "Tweaks": a long-idle race's periodic
 *  heartbeat otherwise pads this view with dozens of identical-looking rows once sync catches
 *  them all up to the same state). A run of exactly one Ping row is left alone — nothing to
 *  collapse. [lines] is expected pre-sorted by lineNumber ascending, same as every caller
 *  already sorts it for display. */
fun collapseConsecutivePings(lines: List<HistoryLineDisplay>): List<HistoryLineDisplay> {
    val result = mutableListOf<HistoryLineDisplay>()
    var i = 0
    while (i < lines.size) {
        val first = lines[i]
        if (!first.isPing) {
            result += first
            i++
            continue
        }
        var j = i + 1
        while (j < lines.size && lines[j].isPing && lines[j].syncState == first.syncState) j++
        result += if (j - i == 1) {
            first
        } else {
            val last = lines[j - 1]
            first.copy(collapsedThroughLineNumber = last.lineNumber, collapsedThroughTimestampMillis = last.timestampMillis)
        }
        i = j
    }
    return result
}

/** Adds [lines] to the enclosing LazyColumn, one [HistoryLineRow] per entry (or [emptyMessage]
 *  if there are none) — the one shared list-rendering function both Race History and Mule
 *  source detail use, so a pulled race's history looks exactly like a local race's own (no
 *  per-line device name; the source's device is already named once, in that screen's own
 *  header — see MuleSourceDetailScreen).
 *
 *  A LazyColumn item range, not a plain eagerly-composed Column, because a still-recording
 *  race can run into the thousands of rows and keeps growing live via sync — composing and
 *  laying out every row regardless of whether it's actually on screen made this screen
 *  progressively slower to open the longer a race ran, re-doing that full pass on every new
 *  synced row's recomposition; confirmed in the field as a 15+ second open on an actively
 *  syncing 1000+-row race, with the same live growth risking a genuine OOM/ANR crash on a
 *  long enough race. */
fun LazyListScope.historyLines(lines: List<HistoryLineDisplay>, emptyMessage: String) {
    if (lines.isEmpty()) {
        item { Text(emptyMessage, style = MaterialTheme.typography.bodyMedium) }
    } else {
        val collapsed = collapseConsecutivePings(lines)
        items(collapsed, key = { it.lineNumber }) { line ->
            HistoryLineRow(
                lineNumber = line.lineNumber,
                splitNumber = line.splitNumber,
                actionLabel = line.actionLabel,
                bibNumber = line.bibNumber,
                elapsedMillis = line.elapsedMillis,
                timestampMillis = line.timestampMillis,
                note = line.note,
                syncState = line.syncState,
                // Dropped on a collapsed row — "synced to" is per-line detail that doesn't
                // mean much averaged across a whole run, and every Ping row's own target set
                // already agrees via the syncState match collapsing requires anyway.
                syncedToLabel = line.syncedToLabel.takeIf { line.collapsedThroughLineNumber == null },
                dupSplitRefs = line.dupSplitRefs,
                editedFromLineNumber = line.editedFromLineNumber,
                isUndoMarker = line.isUndoMarker,
                splitLabelOverride = line.splitLabelOverride,
                collapsedThroughLineNumber = line.collapsedThroughLineNumber,
                collapsedThroughTimestampMillis = line.collapsedThroughTimestampMillis,
            )
        }
    }
}
