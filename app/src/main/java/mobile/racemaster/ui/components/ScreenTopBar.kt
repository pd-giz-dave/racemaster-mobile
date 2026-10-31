package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import mobile.racemaster.ui.help.HelpTopic
import mobile.racemaster.util.withClickSound

/** Shared top bar for every non-dashboard screen — one copy of the `TopAppBar` + Back/Home
 *  pair that used to be hand-copied into each screen individually. Back pops one level, same as
 *  every screen's own Back always has; Home (see RacemasterNavHost.navigateHome) jumps straight
 *  to the Mode Picker regardless of how deep the current screen is. Both always sit in the same
 *  two rightmost slots so the layout reads the same everywhere, after any screen-specific
 *  [extraActions] (e.g. Race History's own "Delete stale", or a form's HideKeyboardButton).
 *
 *  [helpTopic], when non-null, adds a leading "?" icon opening a [HelpTopicDialog] for it — see
 *  [mobile.racemaster.ui.help.HelpTopics]'s own doc for why only some screens pass one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTopBar(
    title: @Composable () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    helpTopic: HelpTopic? = null,
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    var showHelp by remember { mutableStateOf(false) }
    TopAppBar(
        title = title,
        actions = {
            if (helpTopic != null) {
                IconButton(onClick = withClickSound { showHelp = true }) {
                    Icon(Icons.Filled.HelpOutline, contentDescription = "Help")
                }
            }
            extraActions()
            IconButton(onClick = withClickSound(onBack)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            IconButton(onClick = withClickSound(onHome)) {
                Icon(Icons.Filled.Home, contentDescription = "Home")
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
    if (showHelp && helpTopic != null) {
        HelpTopicDialog(topic = helpTopic, onDismiss = { showHelp = false })
    }
}
