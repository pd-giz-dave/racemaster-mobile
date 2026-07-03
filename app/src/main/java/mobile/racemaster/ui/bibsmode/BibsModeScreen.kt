package mobile.racemaster.ui.bibsmode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.MainActivity
import mobile.racemaster.data.db.entity.BIB_REQUIRED_TYPES
import mobile.racemaster.data.db.entity.BibEntryType
import mobile.racemaster.ui.components.DigitKeypad
import mobile.racemaster.ui.components.ModeScreenTopBar
import mobile.racemaster.ui.components.NewBibsRaceDialog
import mobile.racemaster.ui.components.StopOrResetButton
import mobile.racemaster.ui.components.UndoLastButton
import mobile.racemaster.util.withClickSound

private const val BUTTON_HEIGHT_DP = 48

// Default Material button horizontal padding (24dp/side) leaves almost no room for text once
// four buttons share a row — cut it down instead of shrinking the font, so labels like
// "Finish"/"Seniors" stay readable.
private val BUTTON_ROW_CONTENT_PADDING = PaddingValues(horizontal = 4.dp, vertical = 8.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BibsModeScreen(
    onChangeMode: () -> Unit,
    viewModel: BibsModeViewModel = viewModel(factory = BibsModeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Same external HID trigger mechanism Time Mode uses (MainActivity.onExternalSplitTrigger),
    // registered here while Bibs Mode is on screen so a volume button (or any other recognized
    // HID key) logs the pending event exactly like tapping the Log button.
    val activity = LocalContext.current as MainActivity
    val currentOnSubmit by rememberUpdatedState(viewModel::submit)
    val canExternalTrigger by rememberUpdatedState(uiState.canSubmit)
    DisposableEffect(activity) {
        activity.onExternalSplitTrigger = { if (canExternalTrigger) currentOnSubmit() }
        onDispose { activity.onExternalSplitTrigger = null }
    }

    Scaffold(
        topBar = {
            ModeScreenTopBar(
                title = "Bibs Mode",
                raceLabel = uiState.raceLabel,
                newRaceEnabled = !uiState.raceInProgress,
                newRaceDialog = { onDismiss ->
                    NewBibsRaceDialog(
                        onConfirm = { name, start, count ->
                            viewModel.startNewRace(name, start, count)
                            onDismiss()
                        },
                        onDismiss = onDismiss,
                    )
                },
                onChangeMode = onChangeMode,
            )
        },
    ) { padding ->
        BibsModeContent(
            uiState = uiState,
            onDigit = viewModel::onDigit,
            onBackspace = viewModel::onBackspace,
            onClear = viewModel::onClear,
            onSetPendingEventType = viewModel::setPendingEventType,
            onSubmit = viewModel::submit,
            onStop = viewModel::stopBibsMode,
            onReset = viewModel::resetBibsMode,
            onUndo = viewModel::undoLast,
            onUpdateEntry = viewModel::updateEntry,
            onUpdateClockTime = viewModel::updateClockTime,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }

    uiState.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text("Can't log that") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = withClickSound(viewModel::dismissError)) { Text("OK") } },
        )
    }
}

@Composable
private fun BibsModeContent(
    uiState: BibsModeUiState,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSetPendingEventType: (BibEntryType) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    onUpdateEntry: (id: Long, bibNumber: Int?, type: BibEntryType, note: String?) -> Unit,
    onUpdateClockTime: (id: Long, raw: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var editingEntryId by remember { mutableStateOf<Long?>(null) }
        val editingEntry = uiState.entries.firstOrNull { it.id == editingEntryId }
        var showEventPicker by remember { mutableStateOf(false) }

        // The header (title/keypad/buttons/edit panel) is itself scrollable so that when the
        // keyboard comes up to edit a split, it can scroll to keep the edit panel's Save/Cancel
        // fully visible above the keyboard. It's fine for the quick-entry keypad above it to
        // scroll out of view in the process — there's no time pressure entering bibs, runners
        // can wait in the finish funnel while a bib is being recorded.
        val headerScrollState = rememberScrollState()
        LaunchedEffect(editingEntryId) {
            if (editingEntryId != null) headerScrollState.animateScrollTo(headerScrollState.maxValue)
        }

        Column(
            modifier = Modifier.verticalScroll(headerScrollState),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "Next: #${uiState.nextSplitNumber}",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (uiState.dupCount > 0) {
                    Text(
                        text = "${uiState.dupCount} dup${if (uiState.dupCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                text = if (uiState.pendingEventType in BIB_REQUIRED_TYPES) {
                    uiState.currentDigits.ifEmpty { "Enter bib" }
                } else {
                    uiState.pendingEventType.displayName()
                },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            DigitKeypad(
                onDigit = onDigit,
                onBackspace = onBackspace,
                onClear = onClear,
                buttonHeight = 52.dp,
                spacing = 4.dp,
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = withClickSound(onSubmit),
                    enabled = uiState.canSubmit,
                    contentPadding = BUTTON_ROW_CONTENT_PADDING,
                    modifier = Modifier.weight(1f).height(BUTTON_HEIGHT_DP.dp),
                ) { Text(uiState.pendingEventType.displayName()) }
                OutlinedButton(
                    onClick = withClickSound { showEventPicker = true },
                    enabled = !uiState.stopped,
                    contentPadding = BUTTON_ROW_CONTENT_PADDING,
                    modifier = Modifier.weight(1f).height(BUTTON_HEIGHT_DP.dp),
                ) { Text("Event") }
                StopOrResetButton(
                    isStopped = uiState.stopped,
                    stopDescription = "No more bib entries can be logged. Undo the Stop entry to resume.",
                    resetDescription = "This clears every bib entry and resets ready to start again from scratch.",
                    onStop = onStop,
                    onReset = onReset,
                    contentPadding = BUTTON_ROW_CONTENT_PADDING,
                    modifier = Modifier.weight(1f).height(BUTTON_HEIGHT_DP.dp),
                )
                UndoLastButton(
                    enabled = uiState.canUndo,
                    description = uiState.entries.firstOrNull()?.let { undoDescription(it) }.orEmpty(),
                    onConfirm = onUndo,
                    contentPadding = BUTTON_ROW_CONTENT_PADDING,
                    modifier = Modifier.weight(1f).height(BUTTON_HEIGHT_DP.dp),
                )
            }

            if (showEventPicker) {
                EventPickerDialog(
                    current = uiState.pendingEventType,
                    onSelect = { type ->
                        onSetPendingEventType(type)
                        showEventPicker = false
                    },
                    onDismiss = { showEventPicker = false },
                )
            }

            if (editingEntry != null) {
                HorizontalDivider()
                EditBibEntryPanel(
                    entry = editingEntry,
                    onSaveEntry = { bib, type, note ->
                        onUpdateEntry(editingEntry.id, bib, type, note)
                        editingEntryId = null
                    },
                    onSaveClockTime = { raw ->
                        onUpdateClockTime(editingEntry.id, raw)
                        editingEntryId = null
                    },
                    onCancel = { editingEntryId = null },
                )
            }
        }

        HorizontalDivider()

        val listState = rememberLazyListState()
        LaunchedEffect(uiState.entries.firstOrNull()?.id) {
            if (uiState.entries.isNotEmpty()) listState.scrollToItem(0)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(uiState.entries, key = { it.id }) { entry ->
                BibEntryRow(entry = entry, onClick = { editingEntryId = entry.id })
            }
        }
    }
}

@Composable
private fun EventPickerDialog(
    current: BibEntryType,
    onSelect: (BibEntryType) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose event") },
        text = {
            Column {
                EVENT_PICKER_OPTIONS.forEach { type ->
                    TextButton(onClick = withClickSound { onSelect(type) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (type == current) "${type.displayName()}  ✓" else type.displayName(),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = withClickSound(onDismiss)) { Text("Cancel") } },
    )
}

@Composable
private fun EditBibEntryPanel(
    entry: BibEntryUi,
    onSaveEntry: (bibNumber: Int?, type: BibEntryType, note: String?) -> Unit,
    onSaveClockTime: (raw: String) -> Unit,
    onCancel: () -> Unit,
) {
    if (entry.type == BibEntryType.CLOCK) {
        var timeText by remember(entry.id) { mutableStateOf(entry.note.orEmpty()) }
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Editing #${entry.splitNumber}  Clock", style = MaterialTheme.typography.titleMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { timeText = it },
                    singleLine = true,
                    label = { Text("Offset time (m:ss or ss)") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = withClickSound { onSaveClockTime(timeText) }) { Text("Save") }
                TextButton(onClick = withClickSound(onCancel)) { Text("Cancel") }
            }
        }
        return
    }

    var editType by remember(entry.id) { mutableStateOf(entry.type) }
    var bibText by remember(entry.id) { mutableStateOf(entry.bibNumber?.toString().orEmpty()) }
    var noteText by remember(entry.id) { mutableStateOf(entry.note.orEmpty()) }
    var showEventPicker by remember { mutableStateOf(false) }
    val needsBib = editType in BIB_REQUIRED_TYPES

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Editing #${entry.splitNumber}", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = withClickSound { showEventPicker = true }) { Text(editType.displayName()) }
            if (needsBib) {
                OutlinedTextField(
                    value = bibText,
                    onValueChange = { bibText = it.filter(Char::isDigit).take(3) },
                    singleLine = true,
                    label = { Text("Bib") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                singleLine = true,
                label = { Text("Note") },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = withClickSound {
                onSaveEntry(if (needsBib) bibText.toIntOrNull() else null, editType, noteText.trim().ifBlank { null })
            }) { Text("Save") }
            TextButton(onClick = withClickSound(onCancel)) { Text("Cancel") }
        }
    }

    if (showEventPicker) {
        EventPickerDialog(
            current = editType,
            onSelect = { type ->
                editType = type
                showEventPicker = false
            },
            onDismiss = { showEventPicker = false },
        )
    }
}

@Composable
private fun BibEntryRow(entry: BibEntryUi, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = withClickSound(onClick))
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("#${entry.splitNumber}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(36.dp))
            Text(entry.bibNumber?.toString() ?: "–", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(48.dp))
            Text(entry.type.displayName(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (!entry.note.isNullOrBlank()) {
                Text(entry.note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
        if (entry.dupSplitRefs.isNotEmpty()) {
            Text(
                "dup of ${entry.dupSplitRefs.joinToString(", ") { "#$it" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun undoDescription(entry: BibEntryUi): String {
    val subject = entry.bibNumber?.let { "bib $it (${entry.type.displayName()})" } ?: entry.type.displayName()
    return "Remove $subject #${entry.splitNumber}"
}
