package mobile.racemaster.ui.racehistory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.ui.components.ScreenTopBar
import mobile.racemaster.ui.help.HelpTopics
import mobile.racemaster.util.formatWallClock

/** "View" for a Race History "Peers" list item — read-only, same shape as
 *  [MuleSourceDetailScreen] (a plain header + LazyColumn of rows). Title echoes the full race
 *  label (TODO.md: "all items should echo the full race name in their title"), same as every
 *  other Race History detail screen; the origin device's own name is the supporting line below
 *  it instead, matching how the list row above it is laid out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerActivityDetailScreen(
    raceGroupId: String,
    mode: HistoryMode,
    originDeviceId: String,
    raceLabel: String,
    originDeviceName: String,
    onBack: () -> Unit,
    onHome: () -> Unit,
    viewModel: PeerActivityDetailViewModel = viewModel(factory = PeerActivityDetailViewModel.factory(raceGroupId, mode, originDeviceId)),
) {
    val lines by viewModel.lines.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = { Text(raceLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onBack = onBack,
                onHome = onHome,
                helpTopic = HelpTopics.HistoryLineFormat,
            )
        },
        // MainActivity's outer Scaffold already reserves the nav bar's bottom inset for every
        // screen — without this, this inner Scaffold reserves it a second time.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Text("Peer activity from $originDeviceName", style = MaterialTheme.typography.titleMedium) }
            if (lines.isEmpty()) {
                item { Text("None yet.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(lines, key = { "${it.originDeviceName}:${it.timestampMillis}:${it.actionLabel}" }) { line ->
                    Text(
                        formatWallClock(line.timestampMillis) + " " + line.actionLabel +
                            (line.beaconNumber?.let { " beacon %03d".format(it) } ?: "") +
                            (line.bibNumber?.let { " bib $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
