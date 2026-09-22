package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.MaterialTheme
import mobile.racemaster.util.withClickSound

/** The single Reset action for a mode screen — replaces the old dual-mode Stop/Reset toggle (see
 *  HistoryAction's own doc for why Stop was dropped: nothing depended on it that pressing Start
 *  again on an already-started race doesn't already cover for free). Always behind a confirm
 *  dialog, since Reset is now the only consequential action here — closes out the current
 *  segment (see RaceRepository.closeCurrentSegment's own doc), walkable by pressing it again.
 *  Nothing is ever actually deleted by it — every prior entry stays in Race History regardless. */
@Composable
fun ResetButton(
    // Caption for the confirm dialog explaining what this closes — see onReset's own doc.
    resetDescription: String,
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
        Text("RESET", style = labelStyle)
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Reset this segment?") },
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
