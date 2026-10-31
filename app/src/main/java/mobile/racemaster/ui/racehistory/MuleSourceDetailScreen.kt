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
import mobile.racemaster.ui.bibsmode.displayName
import mobile.racemaster.ui.components.HistoryLineDisplay
import mobile.racemaster.ui.components.historyLines
import mobile.racemaster.ui.components.ScreenTopBar
import mobile.racemaster.ui.help.HelpTopics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuleSourceDetailScreen(
    raceLabel: String,
    sourceDeviceId: String,
    onBack: () -> Unit,
    onHome: () -> Unit,
) {
    val viewModel: MuleSourceDetailViewModel = viewModel(factory = MuleSourceDetailViewModel.factory(raceLabel, sourceDeviceId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text(uiState.raceLabel.ifEmpty { "Mule" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
        // actively-syncing source that keeps growing needs this rather than eagerly composing
        // every row up front. The header text below rides along as the list's first item so
        // there's still exactly one scroll container for the whole screen.
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (uiState.deviceName.isNotBlank()) {
                item { Text("From ${uiState.deviceName}", style = MaterialTheme.typography.titleMedium) }
            }
            // Rendered via historyLines — the same function Race History's own detail screen
            // uses — so a pulled source's history looks exactly like a local race's: the
            // source device is already named once above, not repeated per line.
            historyLines(
                lines = uiState.records.map {
                    HistoryLineDisplay(
                        lineNumber = it.lineNumber,
                        splitNumber = it.splitNumber,
                        actionLabel = it.action.displayName(),
                        bibNumber = it.bibNumber,
                        elapsedMillis = it.elapsedMillis,
                        timestampMillis = it.timestampMillis,
                        note = it.note,
                        syncState = it.syncState,
                        syncedToLabel = it.syncedToLabel,
                        dupSplitRefs = it.dupSplitRefs,
                        isUndoMarker = it.isUndoMarker,
                        editedFromLineNumber = it.editedFromLineNumber,
                    )
                },
                emptyMessage = "No records",
            )
        }
    }
}
