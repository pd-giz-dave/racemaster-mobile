package mobile.racemaster.ui.racehistory

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.data.mule.ProgressEntry
import mobile.racemaster.ui.components.ScreenTopBar
import mobile.racemaster.ui.components.historyRowModifier

/** "View" for a Races-page progress file — read-only, one-shot loaded snapshot of a single
 *  race's progress.json (see ProgressDetailViewModel's own doc). Mirrors MuleSourceDetailScreen's
 *  own shape (a plain header + LazyColumn of rows) rather than anything table-like, matching the
 *  rest of this app's row-based detail screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressDetailScreen(
    raceId: Long,
    onBack: () -> Unit,
    onHome: () -> Unit,
    viewModel: ProgressDetailViewModel = viewModel(factory = ProgressDetailViewModel.factory(raceId)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            ScreenTopBar(
                title = {
                    // TODO.md "all items should echo the full race name in their title" — the
                    // full race label first, same as every other Race History detail screen;
                    // raceName (the server's own short progress.json name) is still shown, just
                    // demoted to the "From ..." line below.
                    Text(
                        uiState.raceLabel.ifBlank { uiState.raceName }.ifBlank { "Progress" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                onBack = onBack,
                onHome = onHome,
            )
        },
        // MainActivity's outer Scaffold already reserves the nav bar's bottom inset for every
        // screen — without this, this inner Scaffold reserves it a second time.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (uiState.raceLabel.isNotBlank()) {
                // The full race label is now the title bar's own job (see its own doc) — this
                // line just adds what the title doesn't: the server's own short race name (if
                // different) and when this snapshot was generated.
                Text(
                    listOfNotNull(
                        uiState.raceName.takeIf { it.isNotBlank() && it != uiState.raceLabel },
                        "generated ${formatGeneratedAt(uiState.generatedAt)}",
                    ).joinToString(" — "),
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
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        items(uiState.entries, key = { it.bibNumber }) { entry ->
                            ProgressEntryRow(entry)
                        }
                    }
                }
            }
        }
    }
}

/** Two lines per bib — matches [mobile.racemaster.ui.components.HistoryLineRow]'s own primary-
 *  row-plus-secondary-line shape (TODO.md: "progress view lines should be the same style as the
 *  other history view") rather than a multi-line [androidx.compose.material3.ListItem] card.
 *  Line 1 is this entry's own identity (bib, name, category, course); line 2, indented like
 *  HistoryLineRow's own note/"Synced to" line, is where (if anywhere) this runner has actually
 *  been seen. */
@Composable
private fun ProgressEntryRow(entry: ProgressEntry) {
    val identity = listOfNotNull(
        "Bib ${entry.bibNumber}" + (entry.name.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""),
        entry.category.takeIf { it.isNotBlank() },
        entry.course.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val seenAt = listOfNotNull(
        // Count Mode's own beacon-at-the-pen signal (ProgressEntry.beaconCountedAt's own doc —
        // blank means absent, same convention beaconClosedAt uses) — a genuinely different event
        // from startTime (Bibs Mode's manual Start), so it sits alongside the rest of this list
        // rather than replacing it: once a runner is later also seen at a CP or the finish, this
        // entry keeps its place rather than getting dropped, since the only thing that clears
        // the "no activity at all yet" fallback below is this whole list staying empty.
        "Seen at start".takeIf { !entry.beaconCountedAt.isNullOrBlank() },
        "Start ${entry.startTime}".takeIf { entry.startTime.isNotBlank() },
        "Finish ${entry.finishTime}".takeIf { entry.finishTime.isNotBlank() },
    ) + entry.cpTimes.entries.sortedBy { it.key.toIntOrNull() ?: 0 }.map { (cp, time) -> "CP$cp $time" }
    val hasActivity = seenAt.isNotEmpty()
    val rowColor = if (hasActivity) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = historyRowModifier(Modifier, onClick = null)) {
        Text(identity, style = MaterialTheme.typography.bodySmall, color = rowColor)
        Text(
            if (hasActivity) seenAt.joinToString(" · ") else "Not yet seen",
            style = MaterialTheme.typography.bodySmall,
            color = rowColor,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
