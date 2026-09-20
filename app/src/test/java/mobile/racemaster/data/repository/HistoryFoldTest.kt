package mobile.racemaster.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFoldTest {

    private data class Row(val lineNumber: Long, val refLineNumber: Long?, val content: String, val isUndo: Boolean = false)

    private fun fold(rows: List<Row>): List<Row> =
        foldLatestVisible(rows, { it.lineNumber }, { it.refLineNumber }, { it.isUndo })

    @Test
    fun unrelatedRowsFoldToThemselvesInTodaysNewestFirstOrder() {
        val rows = listOf(
            Row(1L, null, "a"),
            Row(2L, null, "b"),
            Row(3L, null, "c"),
        )

        val folded = fold(rows)

        assertEquals(listOf("c", "b", "a"), folded.map { it.content })
    }

    @Test
    fun editingAMiddleEntryKeepsItsPositionWithUpdatedContent() {
        val rows = listOf(
            Row(1L, null, "a"),
            Row(2L, null, "b-original"),
            Row(3L, null, "c"),
            // An echo of line 2, appended much later, but still rooted at line 2.
            Row(10L, 2L, "b-edited"),
        )

        val folded = fold(rows)

        // Sorted by ROOT key (3, 2, 1), not by the echo's own much-later lineNumber (10) —
        // the edited entry stays exactly where it always was, just with new content.
        assertEquals(listOf("c", "b-edited", "a"), folded.map { it.content })
    }

    @Test
    fun editingAnEntryTwicePicksTheLatestEcho() {
        val rows = listOf(
            Row(1L, null, "original"),
            Row(5L, 1L, "first edit"),
            Row(9L, 1L, "second edit"),
        )

        val folded = fold(rows)

        assertEquals(listOf("second edit"), folded.map { it.content })
    }

    @Test
    fun anUndoneEntrysWholeGroupIsExcluded() {
        val rows = listOf(
            Row(1L, null, "a"),
            Row(2L, null, "b"),
            Row(3L, null, isUndo = true, content = "undo of b"),
        )
        // The undo-marker (line 3) targets root line 2 ("b").
        val undoRow = rows[2].copy(refLineNumber = 2L)
        val withUndo = listOf(rows[0], rows[1], undoRow)

        val folded = fold(withUndo)

        assertEquals(listOf("a"), folded.map { it.content })
    }

    @Test
    fun undoingAnAlreadyEditedEntryHidesTheLatestEchoNotTheOriginal() {
        val rows = listOf(
            Row(1L, null, "original"),
            Row(5L, 1L, "edited"),
            Row(9L, 1L, isUndo = true, content = "undo"),
        )

        val folded = fold(rows)

        assertEquals(emptyList<String>(), folded.map { it.content })
    }

    private data class MarkerRow(val lineNumber: Long, val content: String, val isLocation: Boolean = false)

    private fun sinceLocation(rows: List<MarkerRow>): List<MarkerRow> =
        sinceLastLocationMarker(rows, { it.lineNumber }, { it.isLocation })

    @Test
    fun noLocationMarkerAtAllIsANoOp() {
        val rows = listOf(MarkerRow(1L, "a"), MarkerRow(2L, "b"), MarkerRow(3L, "c"))

        assertEquals(rows, sinceLocation(rows))
    }

    @Test
    fun keepsOnlyRowsAtOrAfterTheMostRecentLocationMarkerInclusive() {
        val rows = listOf(
            MarkerRow(1L, "old-a"),
            MarkerRow(2L, "old-b"),
            MarkerRow(3L, "relocated", isLocation = true),
            MarkerRow(4L, "new-a"),
            MarkerRow(5L, "new-b"),
        )

        val sliced = sinceLocation(rows)

        // Inclusive of the marker itself (line 3) — it must stay visible/undoable, same as a
        // fresh segment's own Clock/Start marker already does.
        assertEquals(listOf("relocated", "new-a", "new-b"), sliced.map { it.content })
    }

    @Test
    fun onlyTheMostRecentOfMultipleLocationMarkersDefinesTheBoundary() {
        val rows = listOf(
            MarkerRow(1L, "cp1-a"),
            MarkerRow(2L, "relocate-to-cp2", isLocation = true),
            MarkerRow(3L, "cp2-a"),
            MarkerRow(4L, "relocate-to-cp3", isLocation = true),
            MarkerRow(5L, "cp3-a"),
        )

        val sliced = sinceLocation(rows)

        assertEquals(listOf("relocate-to-cp3", "cp3-a"), sliced.map { it.content })
    }
}
