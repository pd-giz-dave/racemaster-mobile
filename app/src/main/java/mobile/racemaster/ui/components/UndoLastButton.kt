package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import mobile.racemaster.util.withClickSound

@Composable
fun UndoLastButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = MaterialTheme.typography.labelLarge,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    OutlinedButton(
        onClick = withClickSound(onClick),
        enabled = enabled,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text("Undo last", style = labelStyle)
    }
}
