package mobile.racemaster.ui.racehistory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.ui.bibsmode.displayName
import mobile.racemaster.ui.components.HistoryLineDisplay
import mobile.racemaster.ui.components.HistoryLinesList
import mobile.racemaster.util.formatWallClock
import mobile.racemaster.util.withClickSound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaceHistoryDetailScreen(
    raceId: Long,
    onBack: () -> Unit,
) {
    val viewModel: RaceHistoryDetailViewModel = viewModel(factory = RaceHistoryDetailViewModel.factory(raceId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.raceLabel) },
                navigationIcon = { TextButton(onClick = withClickSound(onBack)) { Text("Back") } },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        // MainActivity's outer Scaffold already reserves the nav bar's bottom inset for
        // every screen — without this, this inner Scaffold reserves it a second time.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (uiState.deviceName.isNotBlank()) {
                Text("From ${uiState.deviceName}", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                "Last synced: ${uiState.lastSyncedAtMillis?.let { formatWallClock(it) } ?: "never"}",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text("History", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            // One true chronology, sorted by lineNumber (not splitNumber, which restarts per
            // segment and per mode and is no longer unique/orderable across either) — a race's
            // Time and Bibs rows already shared one lineNumber sequence even before this screen
            // rendered them together, so a normal single-mode race (the overwhelming common
            // case) looks exactly as it always did; only a genuinely mixed-mode race now
            // visibly interleaves both. Rendered via HistoryLinesList — the same function a
            // Mule source's own detail screen uses — so both look identical.
            HistoryLinesList(
                lines = uiState.lines.sortedBy { it.lineNumber }.map {
                    HistoryLineDisplay(
                        lineNumber = it.lineNumber,
                        splitNumber = it.splitNumber,
                        actionLabel = it.action.displayName(),
                        bibNumber = it.bibNumber,
                        elapsedMillis = if (it.mode == HistoryMode.TIME) it.elapsedMillis else null,
                        note = it.note,
                        synced = it.synced,
                        syncedToLabel = syncedToLabel(it.syncedTo),
                        dupSplitRefs = it.dupSplitRefs,
                        editedFromLineNumber = it.editedFromLineNumber,
                        isUndoMarker = it.isUndoMarker,
                    )
                },
                emptyMessage = "No history recorded",
            )
        }
    }
}

private fun syncedToLabel(syncedTo: List<String>): String? =
    syncedTo.takeIf { it.isNotEmpty() }?.joinToString(", ")
