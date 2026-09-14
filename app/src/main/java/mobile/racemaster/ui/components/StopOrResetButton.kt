package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import mobile.racemaster.util.withClickSound

@Composable
fun StopOrResetButton(
    isStopped: Boolean,
    // Caption for the confirm dialog's Reset choice — see onReset's own doc. Reset is
    // non-destructive: it never deletes a row, only adds a Reset marker and starts a fresh
    // count from it — real deletion only ever happens explicitly, from the Races list.
    resetDescription: String,
    onStop: () -> Unit,
    // Inserts a Reset marker and starts a fresh count from it — see *ModeRepository.resetXMode.
    // Never deletes anything; every prior entry stays in Race History regardless. For a new
    // operator's own practice attempt, not for correcting an accidental Stop — see Race
    // History's own "Resume" action for that instead (RaceRepository.switchActiveRace).
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    labelStyle: TextStyle = MaterialTheme.typography.labelLarge,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    // Stop is easily undoable (the Undo button brings the clock straight back with no loss of
    // time), so it fires immediately with no confirm, same as Undo. Reset stays behind a confirm
    // dialog since it's consequential (wipes the current segment, even though nothing is ever
    // actually deleted by it) and, unlike Stop, has no single-tap undo of its own.
    var showConfirm by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = withClickSound { if (isStopped) showConfirm = true else onStop() },
        enabled = enabled,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(if (isStopped) "RESET" else "STOP", style = labelStyle)
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Reset course?") },
            text = { Text(resetDescription) },
            confirmButton = {
                TextButton(onClick = withClickSound { onReset(); showConfirm = false }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = withClickSound { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
