package mobile.racemaster.ui.racehistory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.util.withClickSound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaceHistoryScreen(
    onBack: () -> Unit,
    onRaceSelected: (Long) -> Unit,
    onMuleSourceSelected: (raceLabel: String, sourceDeviceId: String) -> Unit,
    viewModel: RaceHistoryViewModel = viewModel(factory = RaceHistoryViewModel.Factory),
) {
    val items by viewModel.historyItems.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<HistoryItemUi.LocalRace?>(null) }
    var pendingMuleSourceDelete by remember { mutableStateOf<HistoryItemUi.MuleSource?>(null) }
    // A separate dialog from pendingDelete above — an active race needs its stuck mode(s)
    // cleared first (see RaceRepository.forceResetActiveModes' own doc), not immediate deletion.
    var pendingForceReset by remember { mutableStateOf<HistoryItemUi.LocalRace?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Past Races") },
                navigationIcon = { TextButton(onClick = withClickSound(onBack)) { Text("Back") } },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        // MainActivity's outer Scaffold already reserves the nav bar's bottom inset for
        // every screen — without this, this inner Scaffold reserves it a second time.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (items.isEmpty()) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No races yet")
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(
                    items,
                    key = {
                        when (it) {
                            is HistoryItemUi.LocalRace -> "race-${it.id}"
                            is HistoryItemUi.MuleSource -> "mule-${it.raceLabel}-${it.sourceDeviceId}"
                        }
                    },
                ) { item ->
                    when (item) {
                        is HistoryItemUi.LocalRace -> ListItem(
                            headlineContent = { Text(item.label) },
                            supportingContent = {
                                val parts = listOfNotNull(
                                    "From ${item.createdByDeviceName} (self)".takeIf { item.createdByDeviceName.isNotBlank() },
                                    // Names the actual mode(s) still keeping this active rather
                                    // than a bare "Active" — the operator may be looking at this
                                    // screen precisely because a different mode's own screen
                                    // already looks fully idle (see activeModeLabels' own doc).
                                    "Active in ${item.activeModeLabels.joinToString(" + ")}, can't be deleted".takeIf { item.isActive },
                                )
                                Column {
                                    if (parts.isNotEmpty()) Text(parts.joinToString(" — "))
                                    // Doesn't mean this race's own data is unsynced — it means
                                    // Mule has simply stopped re-checking it against the server
                                    // (see MuleRepository.raceLabelLastTouchedAtMillis's own
                                    // doc); everything up to whenever it was last touched is
                                    // presumably already there.
                                    if (item.serverSyncSkippedAsStale) {
                                        Text(
                                            "Too old for server sync — no longer checked against the server",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                // An active race routes to the force-reset dialog instead of
                                // straight to delete-confirmation — RaceRepository.deleteRace
                                // still refuses it as a backstop either way, but the button stays
                                // tappable rather than dangling disabled with no way forward: the
                                // mode that's actually still active may no longer be reachable
                                // from its own screen at all (see forceResetActiveModes' own doc
                                // for the scenario this fixes).
                                IconButton(
                                    onClick = withClickSound {
                                        if (item.isActive) pendingForceReset = item else pendingDelete = item
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete race",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                            modifier = Modifier.clickable(onClick = withClickSound { onRaceSelected(item.id) }),
                        )
                        is HistoryItemUi.MuleSource -> ListItem(
                            headlineContent = { Text(item.raceLabel.ifEmpty { "Mule" }) },
                            supportingContent = {
                                Column {
                                    if (item.deviceName.isNotBlank()) Text("From ${item.deviceName}")
                                    // See the LocalRace branch above for what this does (and
                                    // doesn't) mean.
                                    if (item.serverSyncSkippedAsStale) {
                                        Text(
                                            "Too old for server sync — no longer checked against the server",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                // No active-race guard here (unlike LocalRace's delete above) —
                                // a Mule source is just a relayed copy, safely re-pullable from
                                // its origin device at any time; see
                                // RaceHistoryViewModel.deleteMuleSource's own doc.
                                IconButton(onClick = withClickSound { pendingMuleSourceDelete = item }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete pulled records",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                            modifier = Modifier.clickable(
                                onClick = withClickSound { onMuleSourceSelected(item.raceLabel, item.sourceDeviceId) },
                            ),
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    pendingDelete?.let { race ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this race?") },
            text = { Text("This permanently deletes \"${race.label}\" and its entire history. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = withClickSound {
                        viewModel.deleteRace(race.id)
                        pendingDelete = null
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = withClickSound { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    pendingForceReset?.let { race ->
        AlertDialog(
            onDismissRequest = { pendingForceReset = null },
            title = { Text("Reset before deleting?") },
            text = {
                Text(
                    "\"${race.label}\" is still active in ${race.activeModeLabels.joinToString(" + ")} " +
                        "— that mode's own screen may no longer show this race at all if you've since " +
                        "moved on to another one. Resetting clears its unfinished segment only; the " +
                        "rest of its history is kept, and you can then delete it separately.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = withClickSound {
                        viewModel.forceResetActiveModes(race.id)
                        pendingForceReset = null
                    },
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = withClickSound { pendingForceReset = null }) { Text("Cancel") }
            },
        )
    }

    pendingMuleSourceDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingMuleSourceDelete = null },
            title = { Text("Delete these pulled records?") },
            text = {
                Text(
                    "This removes \"${source.raceLabel.ifEmpty { "Mule" }}\" (from ${source.deviceName}) " +
                        "from this device only. If that device is still around, Mule will pull its " +
                        "full history again automatically — nothing is deleted from the source device " +
                        "or the server.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = withClickSound {
                        viewModel.deleteMuleSource(source.raceLabel, source.sourceDeviceId)
                        pendingMuleSourceDelete = null
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = withClickSound { pendingMuleSourceDelete = null }) { Text("Cancel") }
            },
        )
    }
}
