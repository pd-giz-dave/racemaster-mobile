package mobile.racemaster.ui.racehistory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.ui.bibsmode.displayName
import mobile.racemaster.data.db.entity.displayName as modeDisplayName
import mobile.racemaster.ui.components.HistoryLineDisplay
import mobile.racemaster.ui.components.historyLines
import mobile.racemaster.ui.components.ScreenTopBar
import mobile.racemaster.ui.help.HelpTopics
import mobile.racemaster.util.formatWallClock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaceHistoryDetailScreen(
    raceId: Long,
    onBack: () -> Unit,
    onHome: () -> Unit,
) {
    val viewModel: RaceHistoryDetailViewModel = viewModel(factory = RaceHistoryDetailViewModel.factory(raceId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text(uiState.raceLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onBack = onBack,
                onHome = onHome,
                helpTopic = HelpTopics.HistoryLineFormat,
            )
        },
        // MainActivity's outer Scaffold already reserves the nav bar's bottom inset for
        // every screen — without this, this inner Scaffold reserves it a second time.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        // A LazyColumn, not a scrollable Column — see historyLines's own doc for why an
        // actively-recording, still-growing race needs this rather than eagerly composing every
        // row up front. The header texts below ride along as the list's first item so there's
        // still exactly one scroll container for the whole screen.
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (uiState.deviceName.isNotBlank()) {
                        Text("From ${uiState.deviceName}", style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        "Last synced: ${uiState.lastSyncedAtMillis?.let { formatWallClock(it) } ?: "never"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("History", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                }
            }
            // One true chronology, sorted by lineNumber (not splitNumber, which restarts per
            // segment and per mode and is no longer unique/orderable across either) — a race's
            // Time and Bibs rows already shared one lineNumber sequence even before this screen
            // rendered them together, so a normal single-mode race (the overwhelming common
            // case) looks exactly as it always did; only a genuinely mixed-mode race now
            // visibly interleaves both. Rendered via historyLines — the same function a Mule
            // source's own detail screen uses — so both look identical.
            historyLines(
                lines = uiState.lines.sortedBy { it.lineNumber }.map {
                    HistoryLineDisplay(
                        lineNumber = it.lineNumber,
                        splitNumber = it.splitNumber,
                        actionLabel = it.action.displayName(),
                        bibNumber = it.bibNumber,
                        elapsedMillis = it.elapsedMillis,
                        timestampMillis = it.timestampMillis,
                        note = it.note,
                        syncState = it.syncState,
                        syncedToLabel = syncedToLabel(it.syncedTo),
                        dupSplitRefs = it.dupSplitRefs,
                        editedFromLineNumber = it.editedFromLineNumber,
                        isUndoMarker = it.isUndoMarker,
                        splitLabelOverride = if (it.action == HistoryAction.MODE_START) it.mode.modeDisplayName() else null,
                    )
                },
                emptyMessage = "No history recorded",
            )
        }
    }
}

private fun syncedToLabel(syncedTo: List<String>): String? =
    syncedTo.takeIf { it.isNotEmpty() }?.joinToString(", ")
