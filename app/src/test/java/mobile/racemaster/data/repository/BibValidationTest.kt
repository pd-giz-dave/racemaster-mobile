package mobile.racemaster.data.repository

import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BibValidationTest {

    private fun entry(
        id: Long,
        bibNumber: Int?,
        action: HistoryAction,
        splitNumber: Int,
        refLineNumber: Long? = null,
    ) = HistoryLineEntity(
        id = id,
        raceId = 1L,
        mode = HistoryMode.BIBS,
        bibNumber = bibNumber,
        action = action,
        splitNumber = splitNumber,
        lineNumber = id,
        refLineNumber = refLineNumber,
        timestampMillis = 0L,
    )

    // isBibInLegalRange

    @Test
    fun bibAtRangeStartIsInRange() {
        assertTrue(isBibInLegalRange(100, rangeStart = 100, rangeCount = 20))
    }

    @Test
    fun bibAtRangeEndIsInRange() {
        assertTrue(isBibInLegalRange(119, rangeStart = 100, rangeCount = 20))
    }

    @Test
    fun bibOneBelowRangeIsOutOfRange() {
        assertTrue(!isBibInLegalRange(99, rangeStart = 100, rangeCount = 20))
    }

    @Test
    fun bibOneAboveRangeIsOutOfRange() {
        assertTrue(!isBibInLegalRange(120, rangeStart = 100, rangeCount = 20))
    }

    @Test
    fun nullRangeAllowsAnyBib() {
        assertTrue(isBibInLegalRange(999_999, rangeStart = null, rangeCount = null))
    }

    // findDuplicateSplitRefs

    @Test
    fun sameBibTwiceAsFinishFlagsBothWithCrossReference() {
        val entries = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 101, HistoryAction.FINISH, 2),
        )
        val dups = findDuplicateSplitRefs(entries)
        assertEquals(listOf(2), dups[1L])
        assertEquals(listOf(1), dups[2L])
    }

    @Test
    fun startThenFinishForTheSameBibIsNotADuplicate() {
        // The normal two-step workflow — a bib on course (Start), then crossing the line
        // (Finish) — is not a recording error and must never be flagged.
        val entries = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 101, HistoryAction.START, 2),
        )
        assertTrue(findDuplicateSplitRefs(entries).isEmpty())
    }

    @Test
    fun startThenRetireForTheSameBibIsNotADuplicate() {
        val entries = listOf(
            entry(1, 101, HistoryAction.RETIRE, 1),
            entry(2, 101, HistoryAction.START, 2),
        )
        assertTrue(findDuplicateSplitRefs(entries).isEmpty())
    }

    @Test
    fun finishAloneOrRetireAloneIsNotADuplicate() {
        assertTrue(findDuplicateSplitRefs(listOf(entry(1, 101, HistoryAction.FINISH, 1))).isEmpty())
        assertTrue(findDuplicateSplitRefs(listOf(entry(1, 101, HistoryAction.RETIRE, 1))).isEmpty())
    }

    @Test
    fun finishForABibAlreadyRetiredIsADuplicate() {
        // Finish and Retire are both "crossing" records — a bib can have at most one, so
        // having both (in either order) means it was recorded crossing twice.
        val entries = listOf(
            entry(1, 101, HistoryAction.RETIRE, 1),
            entry(2, 101, HistoryAction.FINISH, 2),
        )
        val dups = findDuplicateSplitRefs(entries)
        assertEquals(listOf(2), dups[1L])
        assertEquals(listOf(1), dups[2L])
    }

    @Test
    fun retireForABibAlreadyFinishedIsADuplicate() {
        val entries = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 101, HistoryAction.RETIRE, 2),
        )
        val dups = findDuplicateSplitRefs(entries)
        assertEquals(listOf(2), dups[1L])
        assertEquals(listOf(1), dups[2L])
    }

    @Test
    fun retireDuplicatedIsFlagged() {
        val entries = listOf(
            entry(1, 101, HistoryAction.RETIRE, 1),
            entry(2, 101, HistoryAction.RETIRE, 2),
        )
        val dups = findDuplicateSplitRefs(entries)
        assertEquals(listOf(2), dups[1L])
        assertEquals(listOf(1), dups[2L])
    }

    @Test
    fun startDuplicatedIsFlaggedIndependentlyOfALegitimateFinish() {
        // Two Starts for the same bib is itself the error — flagged between themselves — but
        // must not drag the (legitimate, singular) Finish into the flag.
        val entries = listOf(
            entry(1, 101, HistoryAction.START, 1),
            entry(2, 101, HistoryAction.START, 2),
            entry(3, 101, HistoryAction.FINISH, 3),
        )
        val dups = findDuplicateSplitRefs(entries)
        assertEquals(listOf(2), dups[1L])
        assertEquals(listOf(1), dups[2L])
        assertTrue(dups[3L] == null)
    }

    @Test
    fun noBibTypesNeverFlaggedRegardlessOfRepetition() {
        val entries = listOf(
            entry(1, null, HistoryAction.CLOCK, 0),
            entry(2, null, HistoryAction.IGNORE, 1),
            entry(3, null, HistoryAction.IGNORE, 2),
            entry(4, null, HistoryAction.SENIORS, 3),
            entry(5, null, HistoryAction.SENIORS, 4),
        )
        assertTrue(findDuplicateSplitRefs(entries).isEmpty())
    }

    @Test
    fun editingOutOfAThreeWayDuplicateGroupUnflagsTheRemainingTwo() {
        val threeWay = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 101, HistoryAction.FINISH, 2),
            entry(3, 101, HistoryAction.FINISH, 3),
        )
        val dupsBefore = findDuplicateSplitRefs(threeWay)
        assertEquals(listOf(2, 3), dupsBefore[1L])
        assertEquals(listOf(1, 3), dupsBefore[2L])
        assertEquals(listOf(1, 2), dupsBefore[3L])

        // Entry 3 edited to a different bib — no longer part of the group.
        val afterEdit = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 101, HistoryAction.FINISH, 2),
            entry(3, 102, HistoryAction.FINISH, 3),
        )
        val dupsAfter = findDuplicateSplitRefs(afterEdit)
        assertEquals(listOf(2), dupsAfter[1L])
        assertEquals(listOf(1), dupsAfter[2L])
        assertTrue(dupsAfter[3L] == null)
    }

    // findDuplicateSplitRefsPerSegment — TODO 249's fix: a bib reused after a Reset must not
    // be flagged against an earlier, already-reset-away segment.

    @Test
    fun bibReusedInALaterSegmentIsNotFlaggedAgainstAnEarlierSegment() {
        val entries = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 0, HistoryAction.RESET, 2),
            entry(3, 101, HistoryAction.FINISH, 1),
        )
        assertTrue(findDuplicateSplitRefsPerSegment(entries).isEmpty())
    }

    @Test
    fun duplicateWithinASingleSegmentIsStillFlaggedAfterSegmenting() {
        val entries = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 101, HistoryAction.FINISH, 2),
            entry(3, 0, HistoryAction.RESET, 3),
            entry(4, 202, HistoryAction.FINISH, 1),
        )
        val dups = findDuplicateSplitRefsPerSegment(entries)
        assertEquals(listOf(2), dups[1L])
        assertEquals(listOf(1), dups[2L])
        assertTrue(dups[4L] == null)
    }

    @Test
    fun duplicatesCanBeFlaggedIndependentlyInMultipleSegments() {
        val entries = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 101, HistoryAction.FINISH, 2),
            entry(3, 0, HistoryAction.RESET, 3),
            entry(4, 202, HistoryAction.FINISH, 1),
            entry(5, 202, HistoryAction.FINISH, 2),
        )
        val dups = findDuplicateSplitRefsPerSegment(entries)
        assertEquals(listOf(2), dups[1L])
        assertEquals(listOf(1), dups[2L])
        assertEquals(listOf(2), dups[4L])
        assertEquals(listOf(1), dups[5L])
    }

    // Race History shows the full raw (unfolded) history, but duplicate flagging must still
    // only ever consider what's currently visible — exactly like BibsModeViewModel's own live
    // feed, which folds before ever calling findDuplicateSplitRefs. These two tests cover the
    // cases that were reachable before findDuplicateSplitRefsPerSegment folded each segment
    // itself: an undone entry, and a since-edited entry's stale original.

    @Test
    fun anUndoneEntryIsNotFlaggedAsADuplicateInPerSegmentHistory() {
        val entries = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 101, HistoryAction.FINISH, 2),
            entry(3, null, HistoryAction.UNDO, 2, refLineNumber = 2),
        )
        val dups = findDuplicateSplitRefsPerSegment(entries)
        assertTrue(dups.isEmpty())
    }

    @Test
    fun onlyTheLatestEditOfAnEntryCountsTowardPerSegmentDuplicateDetection() {
        val entries = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 101, HistoryAction.FINISH, 2),
            // Entry 2 edited away to a different bib — its stale original (bib 101) must no
            // longer count against entry 1.
            entry(3, 102, HistoryAction.FINISH, 2, refLineNumber = 2),
        )
        val dups = findDuplicateSplitRefsPerSegment(entries)
        assertTrue(dups.isEmpty())
    }

    // countDuplicateExtras

    @Test
    fun noDuplicatesCountsZero() {
        val entries = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 102, HistoryAction.FINISH, 2),
        )
        assertEquals(0, countDuplicateExtras(entries))
    }

    @Test
    fun sameBibTwiceCountsOne() {
        val entries = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 101, HistoryAction.FINISH, 2),
        )
        assertEquals(1, countDuplicateExtras(entries))
    }

    @Test
    fun sameBibThreeTimesCountsTwo() {
        val entries = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 101, HistoryAction.FINISH, 2),
            entry(3, 101, HistoryAction.FINISH, 3),
        )
        assertEquals(2, countDuplicateExtras(entries))
    }

    @Test
    fun twoSeparatePairsCountTwo() {
        val entries = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 101, HistoryAction.FINISH, 2),
            entry(3, 102, HistoryAction.FINISH, 3),
            entry(4, 102, HistoryAction.FINISH, 4),
        )
        assertEquals(2, countDuplicateExtras(entries))
    }

    @Test
    fun startThenFinishForTheSameBibCountsZero() {
        val entries = listOf(
            entry(1, 101, HistoryAction.START, 1),
            entry(2, 101, HistoryAction.FINISH, 2),
        )
        assertEquals(0, countDuplicateExtras(entries))
    }

    @Test
    fun excessStartAndExcessCrossingBothCountForTheSameBib() {
        val entries = listOf(
            entry(1, 101, HistoryAction.START, 1),
            entry(2, 101, HistoryAction.START, 2),
            entry(3, 101, HistoryAction.FINISH, 3),
            entry(4, 101, HistoryAction.RETIRE, 4),
        )
        // 1 excess Start + 1 excess crossing (Finish and Retire together) = 2.
        assertEquals(2, countDuplicateExtras(entries))
    }

    // duplicateBibNumbers

    @Test
    fun duplicateBibNumbersListsDistinctBibsInvolvedInAnyDuplicateGroup() {
        val entries = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 101, HistoryAction.FINISH, 2),
            entry(3, 105, HistoryAction.FINISH, 3),
            entry(4, 103, HistoryAction.FINISH, 4),
            entry(5, 103, HistoryAction.FINISH, 5),
        )
        assertEquals(listOf(101, 103), duplicateBibNumbers(entries))
    }

    @Test
    fun duplicateBibNumbersEmptyWhenNoDuplicates() {
        val entries = listOf(entry(1, 101, HistoryAction.FINISH, 1))
        assertTrue(duplicateBibNumbers(entries).isEmpty())
    }

    @Test
    fun duplicateBibNumbersIncludesCrossTypeCombinations() {
        // A Finish and a Retire for the same bib are both "crossing" records, so the
        // combination is just as much a dup as the exact same type twice — but Start+Retire
        // (a different, legitimate combination) must not be included.
        val entries = listOf(
            entry(1, 101, HistoryAction.RETIRE, 1),
            entry(2, 101, HistoryAction.FINISH, 2),
            entry(3, 102, HistoryAction.RETIRE, 3),
            entry(4, 102, HistoryAction.START, 4),
        )
        assertEquals(listOf(101), duplicateBibNumbers(entries))
    }

    // accountedForRecordCount — raw count, used for "how many more expected"

    @Test
    fun bibLoggedAsFinishTwiceByMistakeCountsAsTwoRecordsNotOne() {
        // Outstanding-finisher arithmetic is purely expected minus raw accounted-for records
        // — a duplicate Finish tap (operator unsure the first one registered) still
        // represents two recorded events for this purpose, not one. It's a recording error to
        // be corrected later, not something to silently collapse away here.
        val entries = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 101, HistoryAction.FINISH, 2),
        )
        assertEquals(2, accountedForRecordCount(entries))
    }

    @Test
    fun retireCountsTowardAccountedForRecords() {
        // A retired bib is accounted for elsewhere and won't cross the line either, so it
        // counts the same as a Finish record for "how many more expected".
        val entries = listOf(entry(1, 101, HistoryAction.RETIRE, 1))
        assertEquals(1, accountedForRecordCount(entries))
    }

    @Test
    fun startDoesNotCountAsAccountedFor() {
        val entries = listOf(entry(1, 102, HistoryAction.START, 1))
        assertEquals(0, accountedForRecordCount(entries))
    }

    // distinctAccountedForBibs / outstandingBibs — naming *which* specific bibs are still
    // outstanding is a different question, where collapsing to a distinct set is correct.

    @Test
    fun duplicateFinishStillNamesTheBibAsSeenOnlyOnce() {
        val entries = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 101, HistoryAction.FINISH, 2),
        )
        assertEquals(setOf(101), distinctAccountedForBibs(entries))
        assertEquals(listOf(100, 102, 103, 104), outstandingBibs(entries, rangeStart = 100, rangeCount = 5))
    }

    @Test
    fun retireRemovesABibFromOutstanding() {
        val entries = listOf(entry(1, 103, HistoryAction.RETIRE, 1))
        assertEquals(setOf(103), distinctAccountedForBibs(entries))
        assertEquals(listOf(100, 101, 102, 104), outstandingBibs(entries, rangeStart = 100, rangeCount = 5))
    }

    @Test
    fun startOnlyIsStillOutstanding() {
        val entries = listOf(entry(1, 104, HistoryAction.START, 1))
        assertTrue(distinctAccountedForBibs(entries).isEmpty())
        assertEquals(listOf(100, 101, 102, 103, 104), outstandingBibs(entries, rangeStart = 100, rangeCount = 5))
    }

    @Test
    fun mixOfFinishRetireStartAndOutstandingBibs() {
        val entries = listOf(
            entry(1, 100, HistoryAction.FINISH, 1),
            entry(2, 101, HistoryAction.RETIRE, 2),
            entry(3, 102, HistoryAction.START, 3),
        )
        assertEquals(setOf(100, 101), distinctAccountedForBibs(entries))
        assertEquals(listOf(102, 103, 104), outstandingBibs(entries, rangeStart = 100, rangeCount = 5))
    }

    @Test
    fun outstandingBibsIsEmptyWithoutAConfiguredRange() {
        assertEquals(emptyList<Int>(), outstandingBibs(emptyList(), rangeStart = null, rangeCount = null))
    }
}
