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
import mobile.racemaster.ui.components.HistoryLineRow
import mobile.racemaster.util.withClickSound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuleSourceDetailScreen(
    raceLabel: String,
    onBack: () -> Unit,
) {
    val viewModel: MuleSourceDetailViewModel = viewModel(factory = MuleSourceDetailViewModel.factory(raceLabel))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.raceLabel.ifEmpty { "Mule" }) },
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
                Text("From ${uiState.deviceName}", style = MaterialTheme.typography.titleMedium)
            }
            if (uiState.records.isEmpty()) {
                Text("No records", style = MaterialTheme.typography.bodyMedium)
            } else {
                uiState.records.forEach { record ->
                    Column {
                        // A Mule source can hold records pulled from more than one physical
                        // phone under the same race label, unlike a local race's own history
                        // (always one device) — so, unlike HistoryLineRow's other usage, the
                        // device name is shown per record here rather than once for the whole
                        // screen.
                        HistoryLineRow(
                            lineNumber = record.lineNumber,
                            splitNumber = record.splitNumber,
                            actionLabel = record.action,
                            bibNumber = record.number,
                            elapsedMillis = if (record.isTimeRecord) record.elapsedMillis else null,
                            note = record.note,
                            synced = record.synced,
                            isUndoMarker = record.isUndoMarker,
                            editedFromLineNumber = record.editedFromLineNumber,
                        )
                        if (record.deviceName.isNotBlank()) {
                            Text(record.deviceName, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
