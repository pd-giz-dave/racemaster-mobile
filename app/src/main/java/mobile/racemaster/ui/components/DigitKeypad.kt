package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mobile.racemaster.util.withClickSound

@Composable
fun DigitKeypad(
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    buttonHeight: Dp = 72.dp,
    spacing: Dp = 8.dp,
) {
    val rows = listOf(
        listOf(1, 2, 3),
        listOf(4, 5, 6),
        listOf(7, 8, 9),
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                row.forEach { digit ->
                    KeypadButton(
                        text = digit.toString(),
                        onClick = { onDigit(digit) },
                        height = buttonHeight,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            KeypadButton(text = "C", onClick = onClear, height = buttonHeight, enabled = enabled, modifier = Modifier.weight(1f))
            KeypadButton(text = "0", onClick = { onDigit(0) }, height = buttonHeight, enabled = enabled, modifier = Modifier.weight(1f))
            KeypadButton(text = "⌫", onClick = onBackspace, height = buttonHeight, enabled = enabled, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun KeypadButton(text: String, onClick: () -> Unit, height: Dp, enabled: Boolean, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = withClickSound(onClick),
        enabled = enabled,
        modifier = modifier.height(height),
    ) {
        Text(text = text, style = MaterialTheme.typography.headlineMedium)
    }
}
