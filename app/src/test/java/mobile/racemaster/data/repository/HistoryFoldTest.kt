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
}
