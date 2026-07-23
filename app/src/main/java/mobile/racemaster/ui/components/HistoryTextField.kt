package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.launch
import mobile.racemaster.util.withClickSound

/**
 * An [OutlinedTextField] with a dropdown of previously-entered values it can be re-filled
 * from — a plain [DropdownMenu] anchored to this composable's own position (not
 * ExposedDropdownMenuBox, which assumes a read-only "combo box" field) rather than a
 * fully-freeform one, since the field must stay directly editable either way. The dropdown
 * toggle only appears once [history] is non-empty, so a field with nothing recorded yet looks
 * like an ordinary text field. [onPick] defaults to [onValueChange] (just fill the field) but
 * callers that need picking one field to also fill in related ones (e.g. Setup Server's URL
 * filling in username/password) can override it independently. [extraTrailingIcon] lets a
 * caller add its own trailing icon (e.g. the password field's show/hide toggle) alongside the
 * history dropdown toggle, rather than the two competing for the same trailing-icon slot.
 */
@Composable
fun HistoryTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    history: List<String>,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit = onValueChange,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    extraTrailingIcon: @Composable (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    // Scrolls this field into view the moment it gains focus, rather than relying on the
    // keyboard's own animated inset (see RaceDetailsScreen's own doc for why that alone isn't
    // reliable everywhere — confirmed in the field on a budget/older device where the ime
    // inset either never fires or settles too late, leaving a field the operator just tapped
    // still hidden behind the keyboard until they dismiss it and tap again). bringIntoView()
    // asks the nearest scrollable ancestor (the form's own verticalScroll Column) to scroll
    // just enough to fit this field's current bounds in whatever viewport imePadding() has
    // already shrunk to — correct regardless of whether the keyboard's own inset animation is
    // reported smoothly, in one jump, or at all.
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            label = { Text(label) },
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            trailingIcon = if (extraTrailingIcon != null || history.isNotEmpty()) {
                {
                    Row {
                        extraTrailingIcon?.invoke()
                        if (history.isNotEmpty()) {
                            IconButton(onClick = withClickSound { expanded = true }) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Show previous values")
                            }
                        }
                    }
                }
            } else {
                null
            },
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoViewRequester)
                .onFocusEvent { state ->
                    if (state.isFocused) {
                        coroutineScope.launch { bringIntoViewRequester.bringIntoView() }
                    }
                },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            history.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item) },
                    onClick = withClickSound {
                        onPick(item)
                        expanded = false
                    },
                )
            }
        }
    }
}
