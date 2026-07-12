package mobile.racemaster.ui.racehistory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    onMuleSourceSelected: (deviceRole: String, raceLabel: String) -> Unit,
    viewModel: RaceHistoryViewModel = viewModel(factory = RaceHistoryViewModel.Factory),
) {
    val items by viewModel.historyItems.collectAsStateWithLifecycle()

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
                            is HistoryItemUi.MuleSource -> "mule-${it.deviceRole}-${it.raceLabel}"
                        }
                    },
                ) { item ->
                    when (item) {
                        is HistoryItemUi.LocalRace -> ListItem(
                            headlineContent = { Text(item.label) },
                            supportingContent = {
                                if (item.createdByDeviceName.isNotBlank()) Text("Created by ${item.createdByDeviceName}")
                            },
                            modifier = Modifier.clickable(onClick = withClickSound { onRaceSelected(item.id) }),
                        )
                        is HistoryItemUi.MuleSource -> ListItem(
                            headlineContent = { Text(item.raceLabel.ifEmpty { item.deviceRole }) },
                            supportingContent = { Text("via Mule (${item.deviceRole}) · ${item.syncedCount}/${item.totalCount} synced") },
                            modifier = Modifier.clickable(
                                onClick = withClickSound { onMuleSourceSelected(item.deviceRole, item.raceLabel) },
                            ),
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
