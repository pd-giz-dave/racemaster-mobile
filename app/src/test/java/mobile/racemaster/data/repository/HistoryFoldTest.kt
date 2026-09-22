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

    // ---- visits / resetTargets / currentSegmentVisits / currentSegmentRows ----
    // See HistoryFold.kt's own doc for the design: every LOCATION row starts a "visit"; the
    // current segment is every not-yet-individually-Reset visit sharing the most recent
    // LOCATION's own note (which is what makes relocating back to an already-visited, not-yet-
    // reset location merge its old and new rows back into one view). A single row shape carrying
    // every field currentSegmentRows needs, since a real HistoryLineEntity carries them all
    // together.
    private data class SegmentRow(
        val lineNumber: Long,
        val refLineNumber: Long? = null,
        val note: String? = null,
        val content: String,
        val isUndo: Boolean = false,
        val isLocation: Boolean = false,
        val isReset: Boolean = false,
        val isExcluded: Boolean = false, // stands in for MODE_START/NEW_RACE/PING
    )

    private fun currentSegment(rows: List<SegmentRow>): List<SegmentRow> =
        currentSegmentRows(
            rows,
            { it.lineNumber },
            { it.refLineNumber },
            { it.note },
            { it.isLocation },
            { it.isReset },
            { it.isUndo },
            { it.isExcluded || it.isReset },
        )

    @Test
    fun aSingleOpenVisitIsFullyVisible() {
        val rows = listOf(
            SegmentRow(1L, note = "Finish", content = "relocated", isLocation = true),
            SegmentRow(2L, content = "a"),
            SegmentRow(3L, content = "b"),
        )

        assertEquals(listOf("b", "a", "relocated"), currentSegment(rows).map { it.content })
    }

    @Test
    fun onlyTheMostRecentLocationsOwnVisitIsCurrentWhenNothingIsRevisited() {
        val rows = listOf(
            SegmentRow(1L, note = "CP1", content = "relocate-to-cp1", isLocation = true),
            SegmentRow(2L, content = "cp1-a"),
            SegmentRow(3L, note = "CP2", content = "relocate-to-cp2", isLocation = true),
            SegmentRow(4L, content = "cp2-a"),
        )

        assertEquals(listOf("cp2-a", "relocate-to-cp2"), currentSegment(rows).map { it.content })
    }

    @Test
    fun relocatingBackToAnAlreadyVisitedNotYetResetLocationMergesBothVisits() {
        val rows = listOf(
            SegmentRow(1L, note = "Finish", content = "relocate-to-finish", isLocation = true),
            SegmentRow(2L, content = "finish-a"),
            SegmentRow(3L, note = "CP1", content = "relocate-to-cp1", isLocation = true),
            SegmentRow(4L, content = "cp1-a"),
            SegmentRow(5L, note = "Finish", content = "relocate-back-to-finish", isLocation = true),
            SegmentRow(6L, content = "finish-b"),
        )

        // Both of Finish's own visits (lines 1-2 and 5-6) are merged; CP1's own visit is not.
        assertEquals(
            listOf("finish-b", "relocate-back-to-finish", "finish-a", "relocate-to-finish"),
            currentSegment(rows).map { it.content },
        )
    }

    @Test
    fun aResetTargetingTheMostRecentVisitClosesItEntirely() {
        val rows = listOf(
            SegmentRow(1L, note = "Finish", content = "relocated", isLocation = true),
            SegmentRow(2L, content = "a"),
            SegmentRow(3L, isReset = true, refLineNumber = 1L, content = "reset"),
        )

        assertEquals(emptyList<String>(), currentSegment(rows).map { it.content })
    }

    @Test
    fun aResetTargetingAnOlderVisitLeavesTheCurrentOneUntouched() {
        val rows = listOf(
            SegmentRow(1L, note = "Finish", content = "relocate-to-finish", isLocation = true),
            SegmentRow(2L, content = "finish-a"),
            SegmentRow(3L, note = "CP1", content = "relocate-to-cp1", isLocation = true),
            SegmentRow(4L, content = "cp1-a"),
            SegmentRow(5L, isReset = true, refLineNumber = 1L, content = "reset-of-finish"),
        )

        assertEquals(listOf("cp1-a", "relocate-to-cp1"), currentSegment(rows).map { it.content })
    }

    @Test
    fun relocatingBackAfterTheEarlierVisitWasResetStartsFreshRatherThanResuming() {
        val rows = listOf(
            SegmentRow(1L, note = "Finish", content = "relocate-to-finish", isLocation = true),
            SegmentRow(2L, content = "finish-a"),
            SegmentRow(3L, isReset = true, refLineNumber = 1L, content = "reset"),
            SegmentRow(4L, note = "Finish", content = "relocate-back-to-finish", isLocation = true),
            SegmentRow(5L, content = "finish-b"),
        )

        // The Reset visit (lines 1-2) is excluded even though its note matches — only the fresh
        // visit (lines 4-5) is current.
        assertEquals(listOf("finish-b", "relocate-back-to-finish"), currentSegment(rows).map { it.content })
    }

    @Test
    fun excludedMarkerRowsNeverAppearInTheSegment() {
        val rows = listOf(
            SegmentRow(1L, isExcluded = true, content = "mode-start"),
            SegmentRow(2L, note = "Finish", content = "relocated", isLocation = true),
            SegmentRow(3L, content = "a"),
            SegmentRow(4L, isExcluded = true, content = "ping"),
        )

        assertEquals(listOf("a", "relocated"), currentSegment(rows).map { it.content })
    }

    @Test
    fun undoingTheMostRecentLocationMarkerRevealsWhateverWasVisibleBeforeIt() {
        // "stop" (line 1) -> relocate (LOCATION, line 2) -> undo of the relocate (line 3, ref=2).
        val rows = listOf(
            SegmentRow(1L, content = "stop"),
            SegmentRow(2L, note = "CP1", content = "relocated", isLocation = true),
            SegmentRow(3L, refLineNumber = 2L, isUndo = true, content = "undo of relocate"),
        )

        // The undone LOCATION marker's whole group is dropped by folding, leaving "stop" with no
        // real LOCATION row to belong to any more — bucketed into the implicit leading visit (see
        // HistoryFold.visits' own doc) rather than becoming invisible, correctly re-exposing it as
        // the entry the operator's next Undo needs to reach.
        assertEquals(listOf("stop"), currentSegment(rows).map { it.content })
    }

    @Test
    fun rowsWithNoLocationMarkerAtAllAreStillFullyVisible() {
        // Degrades gracefully for data with no LOCATION marker at all (older/degraded data) —
        // same "no boundary recognized, show everything" fallback as the undo case above.
        val rows = listOf(SegmentRow(1L, content = "a"), SegmentRow(2L, content = "b"))

        assertEquals(listOf("b", "a"), currentSegment(rows).map { it.content })
    }
}
