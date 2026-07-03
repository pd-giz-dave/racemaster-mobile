package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import mobile.racemaster.data.repository.MAX_BIB_NUMBER
import mobile.racemaster.data.repository.MIN_BIB_NUMBER
import mobile.racemaster.util.withClickSound

@Composable
fun NewBibsRaceDialog(
    onConfirm: (name: String, bibsRangeStart: Int, bibsRangeCount: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var startText by remember { mutableStateOf("") }
    var countText by remember { mutableStateOf("") }

    val start = startText.toIntOrNull()
    val count = countText.toIntOrNull()
    val rangeEnd = if (start != null && count != null) start + count - 1 else null
    val valid = name.isNotBlank() &&
        start != null && start in MIN_BIB_NUMBER..MAX_BIB_NUMBER &&
        count != null && count >= 1 &&
        rangeEnd != null && rangeEnd <= MAX_BIB_NUMBER

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this race") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Race name") },
                )
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it.filter(Char::isDigit).take(3) },
                    singleLine = true,
                    label = { Text("First bib number (1–999)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = countText,
                    onValueChange = { countText = it.filter(Char::isDigit).take(3) },
                    singleLine = true,
                    label = { Text("Number of runners") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = withClickSound { onConfirm(name.trim(), start!!, count!!) },
                enabled = valid,
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = withClickSound(onDismiss)) { Text("Cancel") }
        },
    )
}
