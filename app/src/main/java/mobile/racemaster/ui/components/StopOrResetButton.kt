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
    resetDescription: String,
    onStop: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    labelStyle: TextStyle = MaterialTheme.typography.labelLarge,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    // Stop is easily undoable (the Undo button brings the clock straight back with no loss of
    // time), so it fires immediately with no confirm, same as Undo. Reset is destructive
    // (clears every entry) and keeps its confirm dialog.
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
            title = { Text("Reset?") },
            text = { Text(resetDescription) },
            confirmButton = {
                TextButton(onClick = withClickSound {
                    onReset()
                    showConfirm = false
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = withClickSound { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
