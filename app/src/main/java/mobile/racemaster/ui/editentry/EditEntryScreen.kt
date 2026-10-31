package mobile.racemaster.ui.editentry

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import mobile.racemaster.data.db.entity.BIB_REQUIRED_ACTIONS
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.formatSplitRef
import mobile.racemaster.data.repository.unexpectedBibWarning
import mobile.racemaster.ui.bibsmode.displayName
import mobile.racemaster.ui.components.ActionPickerDialog
import mobile.racemaster.ui.components.DiscardChangesDialog
import mobile.racemaster.ui.components.HideKeyboardButton
import mobile.racemaster.ui.components.ScreenTopBar
import mobile.racemaster.util.withClickSound

/**
 * A dedicated screen for editing a single Bibs Mode entry — reached by navigating (see
 * Routes.editEntry), replacing an inline editor (`EditEntryPanel`) that used to be composed
 * above the live entries list. See [mobile.racemaster.ui.timemode.EditSplitScreen]'s own doc
 * for why: that inline editor lived inside the mode screen's own height-capped, ime-inset-driven
 * header scroll region, and imePadding()/WindowInsets.ime don't reliably report the keyboard's
 * real height on some devices (confirmed in the field on a Cubot), leaving it genuinely
 * unreachable. (Used to also back CP Mode's own entry editor, picking its own smaller option
 * list via a `mode` arg — removed along with CP Mode itself, see TODO.md: "A rationalisation".)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEntryScreen(
    entryId: Long,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    onHome: () -> Unit,
    viewModel: EditEntryViewModel = viewModel(factory = EditEntryViewModel.factory(entryId)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val entry = uiState.entry
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var editType by remember { mutableStateOf<HistoryAction?>(null) }
    var bibText by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var showActionPicker by remember { mutableStateOf(false) }
    var clockError by remember { mutableStateOf<String?>(null) }
    var entryError by remember { mutableStateOf<String?>(null) }
    // Pre-fill exactly once, the moment the one-shot load lands — same "prefilled" guard
    // RaceDetailsScreen/EditSplitScreen use, so a later recomposition never stomps on what the
    // operator's already typing.
    var prefilled by remember { mutableStateOf(false) }
    // The exact values this screen was seeded with, so leaving can tell whether the operator
    // actually changed anything this visit — see pendingExit's own doc below. Covers
    // both this screen's sub-forms (a plain Clock-row time edit only ever touches noteText; the
    // full type/bib/note edit can touch all three).
    var initialEditType by remember { mutableStateOf<HistoryAction?>(null) }
    var initialBibText by remember { mutableStateOf("") }
    var initialNoteText by remember { mutableStateOf("") }
    LaunchedEffect(entry) {
        if (entry != null && !prefilled) {
            editType = entry.action
            bibText = entry.bibNumber?.toString().orEmpty()
            noteText = entry.note.orEmpty()
            initialEditType = editType
            initialBibText = bibText
            initialNoteText = noteText
            prefilled = true
        }
    }

    val hasChanges = editType != initialEditType || bibText != initialBibText || noteText != initialNoteText
    // Leaving with an unsaved entry edit still on screen needs a confirm — wired to the top
    // bar's own Back/Home buttons, both sub-forms' own in-body Cancel button, and the system
    // back gesture/button, so none of them bypasses this. [pendingExit] carries which of
    // onCancel/onHome to actually run once confirmed.
    var pendingExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun attemptExit(exit: () -> Unit) {
        if (hasChanges) pendingExit = exit else exit()
    }
    BackHandler(onBack = { attemptExit(onCancel) })

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text(entry?.let { "Editing ${formatSplitRef(it.splitNumber)}" } ?: "Editing entry") },
                onBack = { attemptExit(onCancel) },
                onHome = { attemptExit(onHome) },
                extraActions = { HideKeyboardButton() },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (entry == null || editType == null) {
                // Still loading — nothing to edit yet.
                return@Column
            }

            // A Clock row (written by Bibs' own start — see BibsModeRepository.startBibsMode)
            // is a time-only note edit, no type/bib to change, same distinct minimal form the
            // old inline EditEntryPanel gave it.
            if (entry.action == HistoryAction.CLOCK) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    singleLine = true,
                    label = { Text("Offset time (m:ss or ss)") },
                    isError = clockError != null,
                    supportingText = clockError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = withClickSound {
                            scope.launch {
                                val error = viewModel.saveClockTime(noteText)
                                clockError = error
                                if (error == null) onSaved()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Save") }
                    OutlinedButton(onClick = withClickSound { attemptExit(onCancel) }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                }
                return@Column
            }

            val needsBib = editType in BIB_REQUIRED_ACTIONS
            // Non-blocking — see EditEntryViewModel.saveEntry's own doc. Purely informational,
            // so it's shown separately from entryError below rather than tied to the field's
            // own isError (which stays reserved for the genuinely blocking "missing bib" case).
            val expectationWarning = if (needsBib) {
                unexpectedBibWarning(bibText.toIntOrNull(), uiState.expectedBibs)
            } else {
                null
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = withClickSound { showActionPicker = true }) { Text(editType!!.displayName()) }
                if (needsBib) {
                    OutlinedTextField(
                        value = bibText,
                        onValueChange = { bibText = it.filter(Char::isDigit).take(3) },
                        singleLine = true,
                        label = { Text("Bib") },
                        isError = entryError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            expectationWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                singleLine = true,
                label = { Text("Note") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            entryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = withClickSound {
                        scope.launch {
                            val error = viewModel.saveEntry(
                                if (needsBib) bibText.toIntOrNull() else null,
                                editType!!,
                                noteText.trim().ifBlank { null },
                            )
                            entryError = error
                            if (error == null) onSaved()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
                OutlinedButton(onClick = withClickSound { attemptExit(onCancel) }, modifier = Modifier.weight(1f)) { Text("Cancel") }
            }

            if (showActionPicker) {
                ActionPickerDialog(
                    options = viewModel.availableTypes,
                    current = editType!!,
                    onSelect = { type ->
                        editType = type
                        showActionPicker = false
                    },
                    onDismiss = { showActionPicker = false },
                )
            }
        }
    }

    pendingExit?.let { exit ->
        DiscardChangesDialog(
            onConfirm = {
                pendingExit = null
                exit()
            },
            onDismiss = { pendingExit = null },
        )
    }
}
