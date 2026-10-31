package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import mobile.racemaster.util.withClickSound

/** Shared top bar for every non-dashboard screen — one copy of the `TopAppBar` + Back/Home
 *  pair that used to be hand-copied into each screen individually. Back pops one level, same as
 *  every screen's own Back always has; Home (see RacemasterNavHost.navigateHome) jumps straight
 *  to the Mode Picker regardless of how deep the current screen is. Both always sit in the same
 *  two rightmost slots so the layout reads the same everywhere, after any screen-specific
 *  [extraActions] (e.g. Race History's own "Delete stale", or a form's HideKeyboardButton). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTopBar(
    title: @Composable () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = title,
        actions = {
            extraActions()
            TextButton(onClick = withClickSound(onBack)) { Text("Back") }
            IconButton(onClick = withClickSound(onHome)) {
                Icon(Icons.Filled.Home, contentDescription = "Home")
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}
