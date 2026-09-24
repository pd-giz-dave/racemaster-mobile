package mobile.racemaster.ui.racehistory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import mobile.racemaster.util.formatWallClock
import mobile.racemaster.util.withClickSound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaceHistoryScreen(
    onBack: () -> Unit,
    onRaceSelected: (Long) -> Unit,
    onMuleSourceSelected: (raceLabel: String, sourceDeviceId: String) -> Unit,
    onProgressSelected: (raceId: Long) -> Unit,
    // Called right after switching this device's active race back to a resumed one — see
    // RaceHistoryViewModel.resumeRace's own doc. The caller decides where that lands (the Mode
    // Picker, so the operator picks whichever mode it was recording in).
    onRaceResumed: () -> Unit,
    viewModel: RaceHistoryViewModel = viewModel(factory = RaceHistoryViewModel.Factory),
) {
    val items by viewModel.historyItems.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<HistoryItemUi.LocalRace?>(null) }
    var pendingMuleSourceDelete by remember { mutableStateOf<HistoryItemUi.MuleSource?>(null) }
    var pendingProgressDelete by remember { mutableStateOf<HistoryItemUi.ProgressFile?>(null) }
    // A separate dialog from pendingDelete above — an active race needs its stuck mode(s)
    // cleared first (see RaceRepository.forceResetActiveModes' own doc), not immediate deletion.
    var pendingForceReset by remember { mutableStateOf<HistoryItemUi.LocalRace?>(null) }
    var pendingDeleteAllStale by remember { mutableStateOf(false) }
    // Recomputed on every recomposition (a plain list scan, cheap) rather than a dedicated
    // ViewModel StateFlow — see staleDeletionSummary's own doc.
    val staleSummary = staleDeletionSummary(items)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Races") },
                actions = {
                    // Hidden entirely rather than disabled when there's nothing to sweep — this
                    // is an occasional maintenance action, not a primary control, and most
                    // visits to this screen have zero stale items to offer.
                    if (staleSummary.total > 0) {
                        TextButton(onClick = withClickSound { pendingDeleteAllStale = true }) { Text("Delete stale") }
                    }
                    TextButton(onClick = withClickSound(onBack)) { Text("Back") }
                },
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
                            is HistoryItemUi.ProgressFile -> "progress-${it.raceId}"
                        }
                    },
                ) { item ->
                    when (item) {
                        is HistoryItemUi.LocalRace -> ListItem(
                            headlineContent = { Text(item.label) },
                            supportingContent = {
                                val entryWord = if (item.entryCount == 1) "entry" else "entries"
                                val parts = listOfNotNull(
                                    "${item.entryCount} $entryWord from ${item.createdByDeviceName} (self)"
                                        .takeIf { item.createdByDeviceName.isNotBlank() },
                                    // Names the actual mode(s) still keeping this active rather
                                    // than a bare "Active" — the operator may be looking at this
                                    // screen precisely because a different mode's own screen
                                    // already looks fully idle (see activeModeLabels' own doc).
                                    "Active in ${item.activeModeLabels.joinToString(" + ")}".takeIf { item.isActive },
                                )
                                Column {
                                    if (parts.isNotEmpty()) Text(parts.joinToString(" — "))
                                    // Mirrors Race History's own detail screen, one level up on
                                    // the list row itself.
                                    Text("Last synced: ${item.lastSyncedAtMillis?.let { formatWallClock(it) } ?: "never"}")
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
                                Row {
                                    // Offered only for a race that's still active (an un-Reset
                                    // started mode) but isn't this device's current one any
                                    // more — see HistoryItemUi.LocalRace.isCurrentActiveRace's
                                    // own doc for exactly why: this is the "accidentally
                                    // stopped, still runners on course" recovery path.
                                    if (item.isActive && !item.isCurrentActiveRace) {
                                        TextButton(
                                            onClick = withClickSound {
                                                viewModel.resumeRace(item.id)
                                                onRaceResumed()
                                            },
                                        ) { Text("Resume") }
                                    }
                                    // An active race routes to the force-reset dialog instead of
                                    // straight to delete-confirmation — RaceRepository.deleteRace
                                    // still refuses it as a backstop either way, but the button
                                    // stays tappable rather than dangling disabled with no way
                                    // forward: the mode that's actually still active may no
                                    // longer be reachable from its own screen at all (see
                                    // forceResetActiveModes' own doc for the scenario this fixes).
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
                        // Progress files — race-wide bib-allocation/status data received from the
                        // server or web app (see ProgressRepository's own doc) — get the same
                        // plain race-name-headline shape as a LocalRace/MuleSource row above (no
                        // leading icon of their own any more), just tertiary-colored so they
                        // still read as a genuinely different kind of entry at a glance.
                        is HistoryItemUi.ProgressFile -> ListItem(
                            headlineContent = {
                                Text(item.raceName.ifBlank { item.raceLabel }, color = MaterialTheme.colorScheme.tertiary)
                            },
                            supportingContent = {
                                Text("Progress as at ${formatGeneratedAt(item.generatedAt)}, ${item.entryCount} entries")
                            },
                            trailingContent = {
                                // Always deletable — see RaceHistoryViewModel.deleteProgress's
                                // own doc for why this needs no active-race-style guard.
                                IconButton(onClick = withClickSound { pendingProgressDelete = item }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete progress file",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                            modifier = Modifier.clickable(onClick = withClickSound { onProgressSelected(item.raceId) }),
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

    pendingProgressDelete?.let { progress ->
        AlertDialog(
            onDismissRequest = { pendingProgressDelete = null },
            title = { Text("Delete this progress file?") },
            text = {
                Text(
                    "This removes \"${progress.raceName.ifBlank { progress.raceLabel }}\"'s progress data from " +
                        "this device only. It can be fetched again from the server, or delivered again over " +
                        "Bluetooth, whenever it's next needed.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = withClickSound {
                        viewModel.deleteProgress(progress.raceId)
                        pendingProgressDelete = null
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = withClickSound { pendingProgressDelete = null }) { Text("Cancel") }
            },
        )
    }

    if (pendingDeleteAllStale) {
        AlertDialog(
            onDismissRequest = { pendingDeleteAllStale = false },
            title = { Text("Delete ${staleSummary.total} stale race${if (staleSummary.total == 1) "" else "s"}?") },
            text = {
                Text(
                    listOfNotNull(
                        "${staleSummary.localRaceCount} race(s) will be permanently deleted with their history."
                            .takeIf { staleSummary.localRaceCount > 0 },
                        "${staleSummary.muleSourceCount} pulled record set(s) will be removed locally " +
                            "(still safely re-pullable from their source)."
                            .takeIf { staleSummary.muleSourceCount > 0 },
                    ).joinToString("\n\n"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = withClickSound {
                        viewModel.deleteAllStale()
                        pendingDeleteAllStale = false
                    },
                ) { Text("Delete all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = withClickSound { pendingDeleteAllStale = false }) { Text("Cancel") }
            },
        )
    }
}

// generatedAt is the racemaster server's own ISO-8601 timestamp string (e.g.
// "2026-08-23T10:00:00.000Z") — trimmed to "yyyy-MM-dd HH:mm:ss" for a compact, still exact
// display here rather than pulling in real date parsing just for this one label. Falls back to
// the raw string untouched if it's ever not shaped the way we expect, rather than showing
// nothing or crashing.
internal fun formatGeneratedAt(generatedAt: String): String =
    generatedAt.replace('T', ' ').substringBefore('.').ifBlank { generatedAt }
