package mobile.racemaster.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import mobile.racemaster.util.withClickSound

/**
 * An explicit, always-available way to close the soft keyboard, independent of the system back
 * button — confirmed in the field on some devices (e.g. a Cubot) a back press meant only to
 * dismiss the keyboard isn't consumed by the IME alone and also leaks through to the app's own
 * back handling (navigating away, or discarding an in-progress edit) instead of just hiding the
 * keyboard. Deliberately does NOT also clear focus from the field — confirmed in the field that
 * doing so immediately re-triggers a fresh onStartInput/onStartInputView from the IME (visible
 * in logcat right after the tap) and the keyboard pops straight back up, since the field is
 * left as the only focusable target for Compose's focus system to fall back to. Hiding the IME
 * alone, leaving the field still (invisibly) focused, is what actually stays dismissed — exactly
 * what a normal back-press dismiss does too. Always placed in a screen's TopAppBar (not inline
 * in scrollable body content) so it stays reachable no matter how far the body is scrolled.
 */
@Composable
fun HideKeyboardButton(modifier: Modifier = Modifier) {
    val keyboardController = LocalSoftwareKeyboardController.current
    IconButton(
        onClick = withClickSound { keyboardController?.hide() },
        modifier = modifier,
    ) {
        Icon(Icons.Filled.KeyboardHide, contentDescription = "Hide keyboard")
    }
}
