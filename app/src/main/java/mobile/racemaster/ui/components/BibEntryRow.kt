package mobile.racemaster.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mobile.racemaster.ui.theme.SyncedGreen
import mobile.racemaster.ui.theme.UnsyncedRed
import mobile.racemaster.util.withClickSound

/** One line per Bibs Mode entry — #number, bib, type, note — shared between the live Bibs
 *  Mode screen and race history so the two can never visually drift apart. Sync state is
 *  shown by coloring the row (red while unsynced, green once synced) rather than a
 *  separate "unsynced" line, keeping rows compact enough that more fit on screen at once.
 *  [onClick] is left null in read-only contexts (history) — the row just isn't clickable
 *  there. */
@Composable
fun BibEntryRow(
    splitNumber: Int,
    bibNumber: Int?,
    typeLabel: String,
    note: String?,
    dupSplitRefs: List<Int>,
    synced: Boolean,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val rowColor = if (synced) SyncedGreen else UnsyncedRed
    Column(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = withClickSound(onClick)) else it }
            .padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("#$splitNumber", style = MaterialTheme.typography.bodyLarge, color = rowColor, modifier = Modifier.width(36.dp))
            Text(bibNumber?.toString() ?: "–", style = MaterialTheme.typography.bodyLarge, color = rowColor, modifier = Modifier.width(48.dp))
            Text(typeLabel, style = MaterialTheme.typography.bodyLarge, color = rowColor, modifier = Modifier.weight(1f))
            if (!note.isNullOrBlank()) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = rowColor, modifier = Modifier.weight(1f))
            }
        }
        if (dupSplitRefs.isNotEmpty()) {
            Text(
                "dup of ${dupSplitRefs.joinToString(", ") { "#$it" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
