package mobile.racemaster.ui.racehistory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.ui.timemode.formatElapsed

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
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Time splits", style = MaterialTheme.typography.titleMedium)
            if (uiState.splits.isEmpty()) {
                Text("No splits recorded", style = MaterialTheme.typography.bodyMedium)
            } else {
                uiState.splits.sortedBy { it.splitNumber }.forEach { split ->
                    val labelSuffix = split.label?.let { "  $it" }.orEmpty()
                    Text(
                        "#${split.splitNumber}  ${formatElapsed(split.elapsedMillis)}$labelSuffix",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            Text(
                "Bib entries",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            if (uiState.bibEntries.isEmpty()) {
                Text("No bib entries recorded", style = MaterialTheme.typography.bodyMedium)
            } else {
                uiState.bibEntries.sortedBy { it.id }.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Bib #${entry.bibNumber}", style = MaterialTheme.typography.bodyLarge)
                        Text(entry.splitNumber?.let { "#$it" } ?: "–", style = MaterialTheme.typography.bodyLarge)
                        Text(entry.type.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}