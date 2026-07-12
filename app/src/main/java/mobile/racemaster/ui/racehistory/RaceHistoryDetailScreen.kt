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
import mobile.racemaster.ui.bibsmode.displayName
import mobile.racemaster.ui.components.BibEntryRow
import mobile.racemaster.ui.components.SplitRow
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
                Text("Recorded by ${uiState.deviceName}", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                "Last synced: ${uiState.lastSyncedAtMillis?.let { formatWallClock(it) } ?: "never"}",
                style = MaterialTheme.typography.bodyMedium,
            )

            // Bibs before Time, matching the same ordering used everywhere else a race's two
            // record types are listed together (see RaceHistoryViewModel's Mule-source list).
            Text("Bib entries", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            if (uiState.bibEntries.isEmpty()) {
                Text("No bib entries recorded", style = MaterialTheme.typography.bodyMedium)
            } else {
                uiState.bibEntries.sortedBy { it.splitNumber }.forEach { entry ->
                    BibEntryRow(
                        splitNumber = entry.splitNumber,
                        bibNumber = entry.bibNumber,
                        typeLabel = entry.type.displayName(),
                        note = entry.note,
                        dupSplitRefs = entry.dupSplitRefs,
                        synced = entry.synced,
                    )
                }
            }

            Text("Time splits", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            if (uiState.splits.isEmpty()) {
                Text("No splits recorded", style = MaterialTheme.typography.bodyMedium)
            } else {
                uiState.splits.sortedBy { it.splitNumber }.forEach { split ->
                    SplitRow(
                        splitNumber = split.splitNumber,
                        elapsedMillis = split.elapsedMillis,
                        note = split.note,
                        synced = split.synced,
                    )
                }
            }
        }
    }
}
