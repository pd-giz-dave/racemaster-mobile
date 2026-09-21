package mobile.racemaster.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import mobile.racemaster.data.mule.BtPollingStatus
import mobile.racemaster.data.mule.ServerStatusState
import mobile.racemaster.util.formatBibsExpectedText

/** The device/race/location/progress/sync/duplicate/outstanding-bib info block shared by Bibs
 *  Mode and CP Mode's near-identical headers — both modes track the exact same "first bib
 *  number + runner count" range and dup/outstanding logic (see
 *  `data/repository/BibValidation.kt`), so this is one shared block rather than two
 *  copy-pasted ones. Rendered directly into whatever Column the caller already has open (no
 *  Column of its own) — matches how these lines were laid out inline in BibsModeScreen before
 *  this was extracted. Wraps [RaceProgressSummary] for the prefix common to every mode's own
 *  header (and Mode Picker's own summary), appending the dup-count badge into its "Next" row and
 *  then this pair's own extra bib/CP-specific lines below it. */
@Composable
fun EntryModeHeaderInfo(
    deviceName: String?,
    raceLabel: String,
    raceLocation: String,
    nextSplitNumber: Int,
    dupCount: Int,
    unsyncedCount: Int,
    lastSyncedAtMillis: Long?,
    // Each caller's own mode-appropriate "N so far" text (see RaceProgressSummary.progressText's
    // own doc) — e.g. `formatBibsSoFarText`/`formatCpSoFarText` from util/ExpectedRunnersText.kt —
    // passed in pre-formatted since this composable is shared by both modes and can't itself know
    // which noun ("bibs" vs "checkpoint entries") applies.
    soFarText: String,
    expectedCount: Int,
    outstandingCount: Int,
    duplicateBibNumbers: List<Int>,
    outstandingBibs: List<Int>,
    unexpectedBibNumbers: List<Int>,
    serverStatus: ServerStatusState,
    btPollingStatus: BtPollingStatus,
) {
    RaceProgressSummary(
        deviceName = deviceName,
        raceLabel = raceLabel,
        raceLocation = raceLocation,
        nextSplitNumber = nextSplitNumber,
        unsyncedCount = unsyncedCount,
        lastSyncedAtMillis = lastSyncedAtMillis,
        serverStatus = serverStatus,
        btPollingStatus = btPollingStatus,
        progressText = soFarText,
        nextRowTrailingContent = {
            if (dupCount > 0) {
                Text(
                    text = "$dupCount dup${if (dupCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
    formatBibsExpectedText(expectedCount, outstandingCount)?.let { text ->
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
    if (unexpectedBibNumbers.isNotEmpty()) {
        Text(
            text = "Unexpected: ${unexpectedBibNumbers.joinToString(", ")}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    // Only worth listing individually once few enough are left that the list is more useful
    // than just the count above.
    if (outstandingBibs.isNotEmpty() && outstandingCount <= 10) {
        Text(
            text = "Missing: ${outstandingBibs.joinToString(", ")}",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
