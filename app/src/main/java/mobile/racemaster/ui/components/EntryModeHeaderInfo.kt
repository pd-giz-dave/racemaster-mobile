package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mobile.racemaster.data.db.entity.formatSplitRef
import mobile.racemaster.util.formatBibsExpectedText

/** The device/race/location/progress/sync/duplicate/outstanding-bib info block shared by Bibs
 *  Mode and CP Mode's near-identical headers — both modes track the exact same "first bib
 *  number + runner count" range and dup/outstanding logic (see
 *  `data/repository/BibValidation.kt`), so this is one shared block rather than two
 *  copy-pasted ones. Rendered directly into whatever Column the caller already has open (no
 *  Column of its own) — matches how these lines were laid out inline in BibsModeScreen before
 *  this was extracted. */
@Composable
fun EntryModeHeaderInfo(
    deviceName: String?,
    raceLabel: String,
    raceLocation: String,
    nextSplitNumber: Int,
    dupCount: Int,
    unsyncedCount: Int,
    lastSyncedAtMillis: Long?,
    firstBibNumber: Int?,
    expectedRunnerCount: Int?,
    finishedCount: Int,
    duplicateBibNumbers: List<Int>,
    outstandingBibs: List<Int>,
) {
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
            if (dupCount > 0) {
                Text(
                    text = "$dupCount dup${if (dupCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            SyncStatusLine(unsyncedCount, lastSyncedAtMillis)
        }
    }
    formatBibsExpectedText(firstBibNumber, expectedRunnerCount, finishedCount)?.let { text ->
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
    if (duplicateBibNumbers.isNotEmpty()) {
        Text(
            text = "Dups: ${duplicateBibNumbers.joinToString(", ")}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    // Tied to the same raw "more expected" figure shown above (expected minus accounted-for
    // records), not to how many specific bibs are outstanding — those two can differ while a
    // duplicate is unresolved, and the raw count is the one that should decide when the list
    // becomes worth showing.
    val moreExpected = expectedRunnerCount?.let { (it - finishedCount).coerceAtLeast(0) }
    if (outstandingBibs.isNotEmpty() && moreExpected != null && moreExpected <= 10) {
        Text(
            text = "Missing: ${outstandingBibs.joinToString(", ")}",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
