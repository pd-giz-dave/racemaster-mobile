package mobile.racemaster.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.repository.LineSyncState
import mobile.racemaster.ui.bibsmode.displayName

/** One live-screen entry, shared between Bibs Mode and CP Mode's near-identical current-segment
 *  list — both modes' entries have the exact same shape (a split number, an optional bib
 *  number, an action type, an optional note, duplicate flags, and sync state), so this is
 *  reused as-is rather than each mode declaring its own structurally-identical twin. */
data class EntryLogUi(
    val id: Long,
    val bibNumber: Int?,
    val splitNumber: Int,
    val type: HistoryAction,
    val note: String?,
    val dupSplitRefs: List<Int>,
    val syncState: LineSyncState,
)

/** The live current-segment entry list shared by Bibs Mode and CP Mode — auto-scrolls to the
 *  newest entry (list index 0, since entries are already newest-first) whenever one is added.
 *  Stop/Reset marker rows are never clickable/editable — retyping either one's own type/note
 *  would break every query keyed off it (the repository also refuses this as a backstop, but
 *  the UI shouldn't offer it at all). A Clock row (Bibs Mode only — CP never has one) stays
 *  clickable via its own dedicated time-only edit panel (see [EditEntryPanel]). */
@Composable
fun EntryLogList(
    entries: List<EntryLogUi>,
    onEntryClick: (EntryLogUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.firstOrNull()?.id) {
        if (entries.isNotEmpty()) listState.scrollToItem(0)
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(entries, key = { it.id }) { entry ->
            val isMarkerRow = entry.type == HistoryAction.STOP || entry.type == HistoryAction.RESET
            BibEntryRow(
                splitNumber = entry.splitNumber,
                bibNumber = entry.bibNumber,
                typeLabel = entry.type.displayName(),
                note = entry.note,
                dupSplitRefs = entry.dupSplitRefs,
                syncState = entry.syncState,
                onClick = if (isMarkerRow) null else { { onEntryClick(entry) } },
            )
        }
    }
}
