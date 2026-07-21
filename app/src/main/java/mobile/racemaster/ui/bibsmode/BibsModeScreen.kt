package mobile.racemaster.ui.bibsmode

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.MainActivity
import mobile.racemaster.data.db.entity.BIB_REQUIRED_ACTIONS
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.formatSplitRef
import mobile.racemaster.ui.components.BibEntryRow
import mobile.racemaster.ui.components.DigitKeypad
import mobile.racemaster.ui.components.ModeScreenTopBar
import mobile.racemaster.ui.components.StopOrResetButton
import mobile.racemaster.ui.components.SyncStatusLine
import mobile.racemaster.ui.components.UndoLastButton
import mobile.racemaster.util.formatBibsExpectedText
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
    onNewRace: () -> Unit,
    onEditRace: (raceId: Long) -> Unit,
    viewModel: BibsModeViewModel = viewModel(factory = BibsModeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()

    // Same external HID trigger mechanism Time Mode uses (MainActivity.onExternalSplitTrigger),
    // registered here while Bibs Mode is on screen so a volume button (or any other recognized
    // HID key) logs the pending event exactly like tapping the Log button.
    val activity = LocalActivity.current as MainActivity
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
                newRaceEnabled = !uiState.raceInProgress,
                thisRaceEnabled = uiState.raceId != null,
                onNewRace = onNewRace,
                onThisRace = { uiState.raceId?.let(onEditRace) },
                onChangeMode = onChangeMode,
            )
        },
        // MainActivity's outer Scaffold (no bottomBar) already reserves the navigation
        // bar's bottom inset for every screen — without this, this inner Scaffold's own
        // default contentWindowInsets reserves it a second time, wasting a whole nav-bar
        // height of blank space above the system bar and leaving less room for the list.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        BibsModeContent(
            uiState = uiState,
            deviceName = deviceName,
            onStart = viewModel::startBibsMode,
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
    deviceName: String?,
    onStart: () -> Unit,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSetPendingEventType: (HistoryAction) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onUndo: () -> Unit,
    onUpdateEntry: (id: Long, bibNumber: Int?, type: HistoryAction, note: String?) -> Unit,
    onUpdateClockTime: (id: Long, raw: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // BoxWithConstraints (not a fixed dp budget) is what makes the header/list split adapt
    // to whatever screen the app is running on: maxHeight below is the actual measured space
    // available for this content on THIS device. The header gets everything except a fixed
    // floor reserved for the list — not an even split — because a flat 50/50 turned out to
    // clip the header's full keypad+buttons even on a tall phone (XCover) where there was
    // never a problem: the header naturally wants ~80% of the space, and on a tall screen
    // that 80% is still generously more than the list's floor in absolute terms, so it fits
    // without scrolling exactly as before. Only on a short screen (e.g. the Cubot), where
    // the header's natural content would eat the floor too, does the cap actually bind.
    BoxWithConstraints(modifier = modifier) {
        val minListHeight = 140.dp
        val headerMaxHeight = (maxHeight - minListHeight).coerceAtLeast(0.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            var editingEntryId by remember { mutableStateOf<Long?>(null) }
            val editingEntry = uiState.entries.firstOrNull { it.id == editingEntryId }
            var showEventPicker by remember { mutableStateOf(false) }

            // The header (title/keypad/buttons/edit panel) is itself scrollable so that when the
            // keyboard comes up to edit a split, it can scroll to keep the edit panel's Save/Cancel
            // fully visible above the keyboard. It's fine for the quick-entry keypad above it to
            // scroll out of view in the process — there's no time pressure entering bibs, runners
            // can wait in the finish funnel while a bib is being recorded.
            //
            // Keying on editingEntryId alone isn't enough: the keyboard's appearance is itself
            // animated (imePadding() shrinks this Column over several frames), so a single
            // scroll-to-max right when editing starts can undershoot — it snapshots maxValue
            // before the keyboard has finished pushing content up, so Save/Cancel can still end
            // up hidden behind it (worse for the Bib field than the Note field, since Bib sits on
            // an earlier row and Android's own "scroll focused field into view" never has reason
            // to also reveal the row below it). Re-keying on the live ime bottom inset re-runs
            // this on every frame of that animation, converging on the correct position once the
            // keyboard settles, regardless of which field triggered it.
            val headerScrollState = rememberScrollState()
            val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
            LaunchedEffect(editingEntryId, imeBottomPx) {
                if (editingEntryId != null) headerScrollState.animateScrollTo(headerScrollState.maxValue)
            }

            Column(
                // Capped so the list below always keeps its floor (see BoxWithConstraints
                // above) — on a short screen where the header's natural content (text lines
                // + keypad + buttons) would otherwise eat the whole screen, this caps it and
                // its own verticalScroll takes over. On a screen tall enough that the header
                // already fits under the cap, heightIn(max=) doesn't force it larger — it
                // just stays its natural size, so the list gets whatever's left over exactly
                // as before.
                modifier = Modifier.heightIn(max = headerMaxHeight).verticalScroll(headerScrollState),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!deviceName.isNullOrBlank()) {
                    Text(text = "Device name: $deviceName", style = MaterialTheme.typography.labelMedium)
                }
                Text(text = "Race name: ${uiState.raceLabel}", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "Next: ${formatSplitRef(uiState.nextSplitNumber)}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (uiState.dupCount > 0) {
                            Text(
                                text = "${uiState.dupCount} dup${if (uiState.dupCount == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        SyncStatusLine(uiState.unsyncedCount, uiState.lastSyncedAtMillis)
                    }
                }
                formatBibsExpectedText(uiState.firstBibNumber, uiState.expectedRunnerCount, uiState.finishedCount)?.let { text ->
                    Text(text = text, style = MaterialTheme.typography.labelMedium)
                }
                if (uiState.duplicateBibNumbers.isNotEmpty()) {
                    Text(
                        text = "Dups: ${uiState.duplicateBibNumbers.joinToString(", ")}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Tied to the same raw "more expected" figure shown above (expected minus
                // accounted-for records), not to how many specific bibs are outstanding — those
                // two can differ while a duplicate is unresolved, and the raw count is the one
                // that should decide when the list becomes worth showing.
                val moreExpected = uiState.expectedRunnerCount?.let { (it - uiState.finishedCount).coerceAtLeast(0) }
                if (uiState.outstandingBibs.isNotEmpty() && moreExpected != null && moreExpected <= 10) {
                    Text(
                        text = "Missing: ${uiState.outstandingBibs.joinToString(", ")}",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!uiState.started) {
                    // Nothing recorded yet for this segment (a fresh race, a race switched
                    // into from a different mode, or one just Reset) — side-effect-free to
                    // just look at, exactly like Time Mode's own pre-Start state. Nothing
                    // below is written until this is pressed (see
                    // BibsModeRepository.startBibsMode).
                    Button(
                        onClick = withClickSound(onStart),
                        enabled = uiState.raceId != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp),
                    ) { Text("START", style = MaterialTheme.typography.displaySmall) }
                } else {
                    Text(
                        text = if (uiState.pendingEventType in BIB_REQUIRED_ACTIONS) {
                            uiState.currentDigits.ifEmpty { "Enter bib" }
                        } else {
                            uiState.pendingEventType.displayName()
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    DigitKeypad(
                        onDigit = onDigit,
                        onBackspace = onBackspace,
                        onClear = onClear,
                        enabled = uiState.raceId != null,
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
                            enabled = uiState.raceId != null && !uiState.stopped,
                            contentPadding = BUTTON_ROW_CONTENT_PADDING,
                            modifier = Modifier.weight(1f).height(BUTTON_HEIGHT_DP.dp),
                        ) { Text("Event") }
                        StopOrResetButton(
                            isStopped = uiState.stopped,
                            stopDescription = "No more bib entries can be logged. Undo the Stop entry to resume.",
                            resetDescription = "This clears every bib entry and resets ready to start again from scratch.",
                            onStop = onStop,
                            onReset = onReset,
                            enabled = uiState.raceId != null,
                            contentPadding = BUTTON_ROW_CONTENT_PADDING,
                            modifier = Modifier.weight(1f).height(BUTTON_HEIGHT_DP.dp),
                        )
                        UndoLastButton(
                            enabled = uiState.raceId != null && uiState.canUndo,
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
            }

            if (uiState.started) {
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
                        // Stop/Reset marker rows are never editable — retyping either one's own
                        // type/note would break every query keyed off it (the repository also
                        // refuses this as a backstop, but the UI shouldn't offer it at all). Clock
                        // stays editable via its own dedicated time-only panel (see
                        // EditBibEntryPanel).
                        val isMarkerRow = entry.type == HistoryAction.STOP || entry.type == HistoryAction.RESET
                        BibEntryRow(
                            splitNumber = entry.splitNumber,
                            bibNumber = entry.bibNumber,
                            typeLabel = entry.type.displayName(),
                            note = entry.note,
                            dupSplitRefs = entry.dupSplitRefs,
                            synced = entry.synced,
                            onClick = if (isMarkerRow) null else { { editingEntryId = entry.id } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EventPickerDialog(
    current: HistoryAction,
    onSelect: (HistoryAction) -> Unit,
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
    onSaveEntry: (bibNumber: Int?, type: HistoryAction, note: String?) -> Unit,
    onSaveClockTime: (raw: String) -> Unit,
    onCancel: () -> Unit,
) {
    if (entry.type == HistoryAction.CLOCK) {
        var timeText by remember(entry.id) { mutableStateOf(entry.note.orEmpty()) }
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Editing ${formatSplitRef(entry.splitNumber)}  Clock", style = MaterialTheme.typography.titleMedium)
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
    val needsBib = editType in BIB_REQUIRED_ACTIONS

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Editing ${formatSplitRef(entry.splitNumber)}", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = withClickSound { showEventPicker = true }) { Text(editType.displayName()) }
            if (needsBib) {
                OutlinedTextField(
                    value = bibText,
                    onValueChange = { bibText = it.filter(Char::isDigit).take(3) },
                    singleLine = true,
                    label = { Text("Bib") },
                    // Full keyboard, not KeyboardType.Number — input is still digit-only via
                    // the filter above. The keyboard covering Save/Cancel is fixed by the
                    // ime-inset-reactive scroll above, not by the keyboard's type.
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

private fun undoDescription(entry: BibEntryUi): String {
    val subject = entry.bibNumber?.let { "bib $it (${entry.type.displayName()})" } ?: entry.type.displayName()
    return "Remove $subject ${formatSplitRef(entry.splitNumber)}"
}
