package mobile.racemaster.ui.timemode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.MainActivity
import mobile.racemaster.ui.components.ModeScreenTopBar
import mobile.racemaster.ui.components.UndoLastButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeModeScreen(
    onChangeMode: () -> Unit,
    viewModel: TimeModeViewModel = viewModel(factory = TimeModeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Registers this screen's split action as the target for an external USB/Bluetooth
    // trigger (see MainActivity.onExternalSplitTrigger) while it's on screen, only firing
    // when there's actually a running race to split against.
    val activity = LocalContext.current as MainActivity
    val currentOnSplit by rememberUpdatedState(viewModel::recordSplit)
    val canExternalSplit by rememberUpdatedState(uiState.stopwatchStarted && !uiState.stopwatchStopped)
    DisposableEffect(activity) {
        activity.onExternalSplitTrigger = { if (canExternalSplit) currentOnSplit() }
        onDispose { activity.onExternalSplitTrigger = null }
    }

    Scaffold(
        topBar = {
            ModeScreenTopBar(
                title = "Time Mode",
                raceLabel = uiState.raceLabel,
                newRaceEnabled = !uiState.raceInProgress,
                onNewRace = viewModel::startNewRace,
                onChangeMode = onChangeMode,
            )
        },
    ) { padding ->
        TimeModeContent(
            uiState = uiState,
            onStart = viewModel::startStopwatch,
            onSplit = viewModel::recordSplit,
            onStop = viewModel::stopStopwatch,
            onReset = viewModel::resetStopwatch,
            onUndo = viewModel::undoLast,
            onUpdateLabel = viewModel::updateSplitLabel,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TimeModeContent(
    uiState: TimeModeUiState,
    onStart: () -> Unit,
    onSplit: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    onUpdateLabel: (Long, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Tracks which single split (if any) is showing its label editor. The editor is
        // pinned above the list — not inline in the row being edited — for two reasons:
        // it stays put (and visible) even as new splits get added above where that row
        // used to sit, and it keeps the row's original line on screen so the operator can
        // see what they're tweaking while they type.
        var editingSplitId by remember { mutableStateOf<Long?>(null) }
        val editingSplit = uiState.splits.firstOrNull { it.id == editingSplitId }

        // This header (ticker/main button/Stop-Reset/Undo/editor) is itself scrollable so
        // that on a short screen with the keyboard up, it can scroll to keep the label
        // editor fully visible above the keyboard instead of being clipped — while the main
        // button stays reachable by scrolling back up.
        val headerScrollState = rememberScrollState()
        LaunchedEffect(editingSplitId) {
            if (editingSplitId != null) headerScrollState.animateScrollTo(headerScrollState.maxValue)
        }

        Column(
            modifier = Modifier.verticalScroll(headerScrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (uiState.stopwatchStarted) formatElapsed(uiState.liveElapsedMillis) else "",
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            // Plain single-tap Button — deliberately no combinedClickable/long-press/double-tap
            // gesture handling here. Two finishers crossing close together means two fast taps
            // must both register as splits; any gesture-disambiguation delay would risk
            // swallowing or merging the second one.
            Button(
                onClick = if (uiState.stopwatchStarted) onSplit else onStart,
                enabled = !uiState.stopwatchStopped,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
            ) {
                val label = when {
                    uiState.stopwatchStopped -> "STOPPED"
                    uiState.stopwatchStarted -> "SPLIT"
                    else -> "START"
                }
                Text(label, style = MaterialTheme.typography.displaySmall)
            }

            if (uiState.stopwatchStarted) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StopOrResetButton(
                        isStopped = uiState.stopwatchStopped,
                        onStop = onStop,
                        onReset = onReset,
                        modifier = Modifier.weight(1f),
                    )
                    UndoLastButton(
                        enabled = uiState.canUndo,
                        description = uiState.splits.firstOrNull()?.let {
                            val labelSuffix = it.label?.let { label -> " · $label" }.orEmpty()
                            "Remove split #${it.splitNumber}$labelSuffix (${formatElapsed(it.elapsedMillis)})"
                        }.orEmpty(),
                        onConfirm = onUndo,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (editingSplit != null) {
                    HorizontalDivider()
                    EditSplitLabelPanel(
                        split = editingSplit,
                        onSave = { label ->
                            onUpdateLabel(editingSplit.id, label)
                            editingSplitId = null
                        },
                        onCancel = { editingSplitId = null },
                    )
                }
            }
        }

        if (uiState.stopwatchStarted) {
            HorizontalDivider()

            val listState = rememberLazyListState()
            LaunchedEffect(uiState.splits.firstOrNull()?.id) {
                if (uiState.splits.isNotEmpty()) listState.scrollToItem(0)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(uiState.splits, key = { it.id }) { split ->
                    SplitRow(split = split, onStartEdit = { editingSplitId = split.id })
                }
            }
        }
    }
}

@Composable
private fun EditSplitLabelPanel(
    split: FinishSplitUi,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(split.id) { mutableStateOf(split.label.orEmpty()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Editing #${split.splitNumber}  ${formatElapsed(split.elapsedMillis)}",
            style = MaterialTheme.typography.titleMedium,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Label") },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onSave(text) }) { Text("Save") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun SplitRow(split: FinishSplitUi, onStartEdit: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onStartEdit)
            .padding(vertical = 8.dp),
    ) {
        Text(
            "#${split.splitNumber}  ${formatElapsed(split.elapsedMillis)}",
            style = MaterialTheme.typography.titleMedium,
        )
        if (!split.label.isNullOrBlank()) {
            Text(split.label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StopOrResetButton(
    isStopped: Boolean,
    onStop: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConfirm by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { showConfirm = true },
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(if (isStopped) "RESET" else "STOP")
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(if (isStopped) "Reset?" else "Stop?") },
            text = {
                Text(
                    if (isStopped) {
                        "This clears every split and resets ready to start again from scratch (under the same race name)."
                    } else {
                        "The clock will stop and no more splits can be recorded. The clock will resume (with no loss of time) if you undo the Stop split line."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isStopped) onReset() else onStop()
                    showConfirm = false
                }) { Text(if (isStopped) "Reset" else "Stop") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}