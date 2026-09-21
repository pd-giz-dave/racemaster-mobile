package mobile.racemaster.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import mobile.racemaster.util.withClickSound

/** Shared "you're about to lose unsaved edits" confirmation — every form screen with its own
 *  editable fields (Setup Race, Setup Server, Relocate, Name Device, Edit Split/Entry) shows
 *  this instead of silently discarding when the operator tries to leave with unsaved changes
 *  still on screen, whether via the top bar's own Back button or the system back gesture (see
 *  each screen's own `BackHandler`). [onConfirm] is what actually discards and leaves — for a
 *  screen with its own sticky draft (Setup Race, Setup Server), that means reverting the draft
 *  back to whatever it held when the screen was opened, not merely popping the back stack, so a
 *  later revisit doesn't inherit this visit's abandoned edits as if they were the persisted
 *  baseline (a real, confirmed bug — see SetupRaceViewModel.revertDraft's own doc). */
@Composable
fun DiscardChangesDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discard changes?") },
        text = { Text("You've made changes here that haven't been saved — going back now will lose them.") },
        confirmButton = {
            TextButton(onClick = withClickSound(onConfirm)) { Text("Discard") }
        },
        dismissButton = {
            TextButton(onClick = withClickSound(onDismiss)) { Text("Keep editing") }
        },
    )
}
