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
import mobile.racemaster.data.mule.DEVICE_ROLE_BIBS
import mobile.racemaster.ui.components.BibEntryRow
import mobile.racemaster.ui.components.SplitRow
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
            Text("Pulled via Mule (${uiState.deviceRole})", style = MaterialTheme.typography.titleMedium)
            if (uiState.records.isEmpty()) {
                Text("No records", style = MaterialTheme.typography.bodyMedium)
            } else {
                uiState.records.forEach { record ->
                    Column {
                        // A Mule source can hold records pulled from more than one physical
                        // phone under the same race label, unlike a local race's own history
                        // (always one device) — so, unlike SplitRow/BibEntryRow's live usage,
                        // the device name is shown per record here rather than once for the
                        // whole screen.
                        if (uiState.deviceRole == DEVICE_ROLE_BIBS) {
                            BibEntryRow(
                                splitNumber = record.splitNumber,
                                bibNumber = record.number,
                                typeLabel = record.action,
                                note = record.note,
                                dupSplitRefs = emptyList(),
                                synced = record.synced,
                            )
                        } else {
                            SplitRow(
                                splitNumber = record.splitNumber,
                                elapsedMillis = record.elapsedMillis,
                                note = record.note,
                                synced = record.synced,
                            )
                        }
                        if (record.deviceName.isNotBlank()) {
                            Text(record.deviceName, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
