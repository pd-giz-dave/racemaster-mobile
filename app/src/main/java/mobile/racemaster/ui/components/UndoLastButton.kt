package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun UndoLastButton(
    enabled: Boolean,
    description: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConfirm by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { showConfirm = true },
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text("Undo last")
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Undo last entry?") },
            text = { Text(description) },
            confirmButton = {
                TextButton(onClick = {
                    onConfirm()
                    showConfirm = false
                }) { Text("Undo") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}