package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mobile.racemaster.data.db.entity.BIB_REQUIRED_ACTIONS
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.formatSplitRef
import mobile.racemaster.ui.bibsmode.displayName
import mobile.racemaster.util.withClickSound

/** A small "pick one of these action types" dialog — shared by Bibs Mode (offering
 *  `EVENT_PICKER_OPTIONS`) and CP Mode (offering `CP_ACTION_OPTIONS`, just Pass/Retire) for
 *  changing an existing entry's type while editing (see [EditEntryPanel]). [options] is what
 *  makes this reusable rather than each mode needing its own copy of an otherwise-identical
 *  dialog. */
@Composable
fun ActionPickerDialog(
    options: List<HistoryAction>,
    current: HistoryAction,
    onSelect: (HistoryAction) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose event") },
        text = {
            Column {
                options.forEach { type ->
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

/** The edit-in-place panel shared by Bibs Mode and CP Mode for changing an already-recorded
 *  entry's type/bib/note — [availableTypes] is what [ActionPickerDialog] offers (Bibs' full
 *  event list, or CP's Pass/Retire pair), the one genuine difference between the two modes'
 *  editing needs. The [HistoryAction.CLOCK] branch is Bibs-only in practice (a time-only note
 *  edit, no type/bib to change) — simply never reached for a CP entry, which never has a Clock
 *  row to begin with, so no CP-specific gating is needed here. */
@Composable
fun EditEntryPanel(
    entry: EntryLogUi,
    availableTypes: List<HistoryAction>,
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
    var showActionPicker by remember { mutableStateOf(false) }
    val needsBib = editType in BIB_REQUIRED_ACTIONS

    // The keyboard has no physical Tab key, and the blanket "scroll to max on every ime inset
    // change" the caller applies to its own scroll container only ever reaches whatever's
    // already at the very bottom (Save/Cancel) — it doesn't help the Bib field reach the Note
    // field below it, and on a budget/older device the ime inset it's keyed on can settle too
    // late or not fire at all. Each field bringing itself into view on focus, plus an explicit
    // ImeAction.Next + KeyboardActions.onNext (Bib -> Note), together give the keyboard's own
    // "next" arrow the same effect a physical Tab key would have.
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val bibFieldRequester = remember(entry.id) { BringIntoViewRequester() }
    val noteFieldRequester = remember(entry.id) { BringIntoViewRequester() }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Editing ${formatSplitRef(entry.splitNumber)}", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = withClickSound { showActionPicker = true }) { Text(editType.displayName()) }
            if (needsBib) {
                OutlinedTextField(
                    value = bibText,
                    onValueChange = { bibText = it.filter(Char::isDigit).take(3) },
                    singleLine = true,
                    label = { Text("Bib") },
                    // Full keyboard, not KeyboardType.Number — input is still digit-only via
                    // the filter above.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier
                        .weight(1f)
                        .bringIntoViewRequester(bibFieldRequester)
                        .onFocusEvent { state ->
                            if (state.isFocused) scope.launch { bibFieldRequester.bringIntoView() }
                        },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                singleLine = true,
                label = { Text("Note") },
                // Last field — "Done" dismisses the keyboard.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .weight(1f)
                    .bringIntoViewRequester(noteFieldRequester)
                    .onFocusEvent { state ->
                        if (state.isFocused) scope.launch { noteFieldRequester.bringIntoView() }
                    },
            )
            TextButton(onClick = withClickSound {
                onSaveEntry(if (needsBib) bibText.toIntOrNull() else null, editType, noteText.trim().ifBlank { null })
            }) { Text("Save") }
            TextButton(onClick = withClickSound(onCancel)) { Text("Cancel") }
        }
    }

    if (showActionPicker) {
        ActionPickerDialog(
            options = availableTypes,
            current = editType,
            onSelect = { type ->
                editType = type
                showActionPicker = false
            },
            onDismiss = { showActionPicker = false },
        )
    }
}
