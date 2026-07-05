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
import mobile.racemaster.util.formatWallClock
import mobile.racemaster.util.withClickSound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuleSourceDetailScreen(
    deviceRole: String,
    raceLabel: String,
    onBack: () -> Unit,
) {
    val viewModel: MuleSourceDetailViewModel = viewModel(factory = MuleSourceDetailViewModel.factory(deviceRole, raceLabel))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.raceLabel.ifEmpty { uiState.deviceRole }) },
                navigationIcon = { TextButton(onClick = withClickSound(onBack)) { Text("Back") } },
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
            Text("Pulled via Mule (${uiState.deviceRole})", style = MaterialTheme.typography.titleMedium)
            if (uiState.records.isEmpty()) {
                Text("No records", style = MaterialTheme.typography.bodyMedium)
            } else {
                uiState.records.forEach { record ->
                    Column {
                        val numberSuffix = record.number?.let { "  #$it" }.orEmpty()
                        val noteSuffix = record.note?.let { "  $it" }.orEmpty()
                        Text(
                            "${record.action}$numberSuffix$noteSuffix",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(formatWallClock(record.timestampMillis), style = MaterialTheme.typography.bodySmall)
                        if (!record.synced) {
                            Text(
                                "● unsynced",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}
