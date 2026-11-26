package mobile.racemaster.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Race History's own "label items as race, progress or peers with a suitable icon" (TODO.md) —
 *  a small hand-drawn glyph (deliberately not androidx.compose.material.icons, which has nothing
 *  that reads as "a race"/"a progress snapshot"/"a peer's own phone" at a glance) plus a plain
 *  text label, meant for a [androidx.compose.material3.ListItem]'s own `overlineContent` slot —
 *  the one row of real estate Material3 gives a list row for exactly this kind of "what kind of
 *  thing is this" eyebrow, above the headline rather than competing with it. */
@Composable
fun ItemTypeBadge(label: String, icon: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        icon()
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private val BADGE_ICON_SIZE = 14.dp

/** A race's own data — whether recorded on this device (HistoryItemUi.LocalRace) or pulled from
 *  another (HistoryItemUi.MuleSource): both are "a race," just a different origin, so they share
 *  this one glyph/label rather than needing a 4th category. A simple pennant flag (pole + a
 *  forward-pointing triangle) — the plainest "race" shorthand there is. */
@Composable
fun RaceTypeIcon(tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier = Modifier.size(BADGE_ICON_SIZE)) {
        val poleX = size.width * 0.18f
        drawLine(
            color = tint,
            start = Offset(poleX, 0f),
            end = Offset(poleX, size.height),
            strokeWidth = size.width * 0.14f,
        )
        val pennant = Path().apply {
            moveTo(poleX, size.height * 0.05f)
            lineTo(size.width, size.height * 0.32f)
            lineTo(poleX, size.height * 0.58f)
            close()
        }
        drawPath(pennant, color = tint)
    }
}

/** A race-wide progress/bib-allocation snapshot (HistoryItemUi.ProgressFile) — three ascending
 *  bars, the plainest "progress" shorthand there is. */
@Composable
fun ProgressTypeIcon(tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier = Modifier.size(BADGE_ICON_SIZE)) {
        val barWidth = size.width / 4f
        val gap = barWidth / 2f
        val heights = listOf(0.4f, 0.7f, 1.0f)
        heights.forEachIndexed { index, heightFraction ->
            val barHeight = size.height * heightFraction
            val left = index * (barWidth + gap)
            drawRect(
                color = tint,
                topLeft = Offset(left, size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
            )
        }
    }
}

/** A same-mode peer's own cross-device activity (HistoryItemUi.PeerActivity) — two overlapping
 *  phone/device outlines, the plainest "another nearby device" shorthand there is. */
@Composable
fun PeersTypeIcon(tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier = Modifier.size(BADGE_ICON_SIZE)) {
        val deviceSize = androidx.compose.ui.geometry.Size(size.width * 0.5f, size.height * 0.85f)
        val stroke = Stroke(width = size.width * 0.1f)
        drawRoundRect(
            color = tint,
            topLeft = Offset(0f, size.height * 0.15f),
            size = deviceSize,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.12f),
            style = stroke,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.5f, 0f),
            size = deviceSize,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.12f),
            style = stroke,
        )
    }
}
