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
    stopDescription: String,
    resetDescription: String,
    onStop: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    labelStyle: TextStyle = MaterialTheme.typography.labelLarge,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    var showConfirm by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = withClickSound { showConfirm = true },
        enabled = enabled,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(if (isStopped) "RESET" else "STOP", style = labelStyle)
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(if (isStopped) "Reset?" else "Stop?") },
            text = { Text(if (isStopped) resetDescription else stopDescription) },
            confirmButton = {
                TextButton(onClick = withClickSound {
                    if (isStopped) onReset() else onStop()
                    showConfirm = false
                }) { Text(if (isStopped) "Reset" else "Stop") }
            },
            dismissButton = {
                TextButton(onClick = withClickSound { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
