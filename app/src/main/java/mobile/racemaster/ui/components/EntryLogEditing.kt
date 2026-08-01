package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.ui.bibsmode.displayName
import mobile.racemaster.util.withClickSound

/** A small "pick one of these action types" dialog — shared by Bibs Mode (offering
 *  `EVENT_PICKER_OPTIONS`) and CP Mode (offering `CP_ACTION_OPTIONS`, just Pass/Retire) for
 *  changing an existing entry's type while editing (see
 *  [mobile.racemaster.ui.editentry.EditEntryScreen]). [options] is what makes this reusable
 *  rather than each mode needing its own copy of an otherwise-identical dialog. */
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
