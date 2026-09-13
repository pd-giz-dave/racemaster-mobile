package mobile.racemaster.ui.racehistory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.data.mule.ProgressEntry
import mobile.racemaster.util.withClickSound

/** "View" for a Races-page progress file — read-only, one-shot loaded snapshot of a single
 *  race's progress.json (see ProgressDetailViewModel's own doc). Mirrors MuleSourceDetailScreen's
 *  own shape (a plain header + LazyColumn of rows) rather than anything table-like, matching the
 *  rest of this app's row-based detail screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressDetailScreen(
    raceId: Long,
    onBack: () -> Unit,
    viewModel: ProgressDetailViewModel = viewModel(factory = ProgressDetailViewModel.factory(raceId)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.raceName.ifBlank { uiState.raceLabel }.ifBlank { "Progress" }) },
                navigationIcon = { TextButton(onClick = withClickSound(onBack)) { Text("Back") } },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        // MainActivity's outer Scaffold already reserves the nav bar's bottom inset for every
        // screen — without this, this inner Scaffold reserves it a second time.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (uiState.raceLabel.isNotBlank()) {
                Text(
                    "From ${uiState.raceLabel} — generated ${formatGeneratedAt(uiState.generatedAt)}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
            }
            when {
                !uiState.loaded -> {}
                uiState.entries.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No entries in this progress file")
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(uiState.entries, key = { it.bibNumber }) { entry ->
                            ProgressEntryRow(entry)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressEntryRow(entry: ProgressEntry) {
    ListItem(
        headlineContent = {
            Text("Bib ${entry.bibNumber}" + (entry.name.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""))
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val categoryAndCourse = listOfNotNull(
                    entry.category.takeIf { it.isNotBlank() },
                    entry.course.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (categoryAndCourse.isNotEmpty()) Text(categoryAndCourse)
                val times = listOfNotNull(
                    "Start ${entry.startTime}".takeIf { entry.startTime.isNotBlank() },
                    "Finish ${entry.finishTime}".takeIf { entry.finishTime.isNotBlank() },
                ) + entry.cpTimes.entries.sortedBy { it.key.toIntOrNull() ?: 0 }.map { (cp, time) -> "CP$cp $time" }
                if (times.isNotEmpty()) {
                    Text(times.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Not yet seen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}
