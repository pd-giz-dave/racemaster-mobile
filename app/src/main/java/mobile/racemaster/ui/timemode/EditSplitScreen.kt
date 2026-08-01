package mobile.racemaster.ui.timemode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import mobile.racemaster.data.db.entity.formatSplitRef
import mobile.racemaster.ui.components.HideKeyboardButton
import mobile.racemaster.util.formatElapsedSplitTime
import mobile.racemaster.util.withClickSound

/**
 * A dedicated screen for editing a single Time Mode split's note — reached by navigating (see
 * Routes.editSplit), replacing an inline editor that used to be composed above the live splits
 * list. That inline editor lived inside the mode screen's own height-capped, ime-inset-driven
 * header scroll region — confirmed in the field on a Cubot that imePadding()/WindowInsets.ime
 * don't reliably report the keyboard's real height there, leaving Save/Cancel genuinely
 * unreachable with no way to scroll to them. A full screen of its own gets the same simple,
 * already-working Scaffold/imePadding/verticalScroll layout RaceDetailsScreen uses, with its
 * own always-visible TopAppBar (Cancel — title — [HideKeyboardButton]) immune to body scroll
 * entirely — dismissing the keyboard from there reveals Save without needing to scroll at all,
 * since this form is short enough to fit on screen on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSplitScreen(
    splitId: Long,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    viewModel: EditSplitViewModel = viewModel(factory = EditSplitViewModel.factory(splitId)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var noteText by remember { mutableStateOf("") }
    // Pre-fill exactly once, the moment the one-shot load lands — same "prefilled" guard
    // RaceDetailsScreen uses, so a later recomposition never stomps on what the operator's
    // already typing.
    var prefilled by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.loaded) {
        if (uiState.loaded && !prefilled) {
            noteText = uiState.note.orEmpty()
            prefilled = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (uiState.loaded) {
                            "Editing ${formatSplitRef(uiState.splitNumber)}  ${formatElapsedSplitTime(uiState.elapsedMillis)}"
                        } else {
                            "Editing split"
                        },
                    )
                },
                navigationIcon = { TextButton(onClick = withClickSound(onCancel)) { Text("Cancel") } },
                actions = { HideKeyboardButton() },
                windowInsets = WindowInsets(0, 0, 0, 0),
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
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                singleLine = true,
                enabled = uiState.loaded,
                label = { Text("Note") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = withClickSound {
                        scope.launch {
                            viewModel.save(noteText)
                            onSaved()
                        }
                    },
                    enabled = uiState.loaded,
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
                OutlinedButton(onClick = withClickSound(onCancel), modifier = Modifier.weight(1f)) { Text("Cancel") }
            }
        }
    }
}
