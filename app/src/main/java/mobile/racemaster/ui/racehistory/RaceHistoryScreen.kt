package mobile.racemaster.ui.racehistory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
                                    "Active, can't be deleted".takeIf { item.isActive },
                                )
                                if (parts.isNotEmpty()) Text(parts.joinToString(" — "))
                            },
                            trailingContent = {
                                // Never offered for the active race — RaceRepository.deleteRace
                                // also refuses it as a backstop, but the UI shouldn't dangle a
                                // control in front of the operator that would silently no-op.
                                IconButton(
                                    onClick = withClickSound { pendingDelete = item },
                                    enabled = !item.isActive,
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete race",
                                        tint = if (item.isActive) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                            modifier = Modifier.clickable(onClick = withClickSound { onRaceSelected(item.id) }),
                        )
                        is HistoryItemUi.MuleSource -> ListItem(
                            headlineContent = { Text(item.raceLabel.ifEmpty { "Mule" }) },
                            supportingContent = {
                                if (item.deviceName.isNotBlank()) Text("From ${item.deviceName}")
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
