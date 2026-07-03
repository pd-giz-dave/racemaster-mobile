package mobile.racemaster.ui.bibsmode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.data.db.entity.BibEntryType
import mobile.racemaster.ui.components.DigitKeypad
import mobile.racemaster.ui.components.ModeScreenTopBar
import mobile.racemaster.ui.components.UndoLastButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BibsModeScreen(
    onChangeMode: () -> Unit,
    viewModel: BibsModeViewModel = viewModel(factory = BibsModeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            ModeScreenTopBar(
                title = "Bibs Mode",
                raceLabel = uiState.raceLabel,
                newRaceEnabled = !uiState.raceInProgress,
                onNewRace = viewModel::startNewRace,
                onChangeMode = onChangeMode,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = uiState.currentDigits.ifEmpty { "Enter bib" },
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            DigitKeypad(
                onDigit = viewModel::onDigit,
                onBackspace = viewModel::onBackspace,
                onClear = viewModel::onClear,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.submit(BibEntryType.START) },
                    enabled = uiState.canSubmit,
                    modifier = Modifier.weight(1f).height(64.dp),
                ) { Text("Start") }
                Button(
                    onClick = { viewModel.submit(BibEntryType.FINISH) },
                    enabled = uiState.canSubmit,
                    modifier = Modifier.weight(1f).height(64.dp),
                ) { Text("Finish") }
                Button(
                    onClick = { viewModel.submit(BibEntryType.RETIRE) },
                    enabled = uiState.canSubmit,
                    modifier = Modifier.weight(1f).height(64.dp),
                ) { Text("Retire") }
            }

            UndoLastButton(
                enabled = uiState.canUndo,
                description = uiState.entries.firstOrNull()?.let {
                    "Remove bib ${it.bibNumber} (${it.type.name.lowercase()}${it.splitNumber?.let { n -> " #$n" }.orEmpty()})"
                }.orEmpty(),
                onConfirm = viewModel::undoLast,
            )

            HorizontalDivider()

            val listState = rememberLazyListState()
            LaunchedEffect(uiState.entries.firstOrNull()?.id) {
                if (uiState.entries.isNotEmpty()) listState.scrollToItem(0)
            }
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(uiState.entries, key = { it.id }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Bib #${entry.bibNumber}", style = MaterialTheme.typography.titleMedium)
                        Text(entry.splitNumber?.let { "#$it" } ?: "–", style = MaterialTheme.typography.titleMedium)
                        Text(entry.type.name, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}