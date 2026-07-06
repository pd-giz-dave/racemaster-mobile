package mobile.racemaster.data.repository

import mobile.racemaster.data.db.entity.BibEntryEntity
import mobile.racemaster.data.db.entity.BibEntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BibValidationTest {

    private fun entry(
        id: Long,
        bibNumber: Int?,
        type: BibEntryType,
        splitNumber: Int,
    ) = BibEntryEntity(
        id = id,
        raceId = 1L,
        bibNumber = bibNumber,
        type = type,
        splitNumber = splitNumber,
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
            entry(1, 101, BibEntryType.FINISH, 1),
            entry(2, 101, BibEntryType.FINISH, 2),
        )
        val dups = findDuplicateSplitRefs(entries)
        assertEquals(listOf(2), dups[1L])
        assertEquals(listOf(1), dups[2L])
    }

    @Test
    fun startForABibAlreadyFinishedIsADuplicate() {
        // Once a bib has finished, a Start logged for it afterwards is a recording error, not
        // a normal two-step workflow — flagged the same as a repeat of the same type.
        val entries = listOf(
            entry(1, 101, BibEntryType.FINISH, 1),
            entry(2, 101, BibEntryType.START, 2),
        )
        val dups = findDuplicateSplitRefs(entries)
        assertEquals(listOf(2), dups[1L])
        assertEquals(listOf(1), dups[2L])
    }

    @Test
    fun startForABibAlreadyRetiredIsADuplicate() {
        val entries = listOf(
            entry(1, 101, BibEntryType.RETIRE, 1),
            entry(2, 101, BibEntryType.START, 2),
        )
        val dups = findDuplicateSplitRefs(entries)
        assertEquals(listOf(2), dups[1L])
        assertEquals(listOf(1), dups[2L])
    }

    @Test
    fun finishForABibAlreadyRetiredIsADuplicate() {
        val entries = listOf(
            entry(1, 101, BibEntryType.RETIRE, 1),
            entry(2, 101, BibEntryType.FINISH, 2),
        )
        val dups = findDuplicateSplitRefs(entries)
        assertEquals(listOf(2), dups[1L])
        assertEquals(listOf(1), dups[2L])
    }

    @Test
    fun retireForABibAlreadyFinishedIsADuplicate() {
        val entries = listOf(
            entry(1, 101, BibEntryType.FINISH, 1),
            entry(2, 101, BibEntryType.RETIRE, 2),
        )
        val dups = findDuplicateSplitRefs(entries)
        assertEquals(listOf(2), dups[1L])
        assertEquals(listOf(1), dups[2L])
    }

    @Test
    fun retireDuplicatedIsFlagged() {
        val entries = listOf(
            entry(1, 101, BibEntryType.RETIRE, 1),
            entry(2, 101, BibEntryType.RETIRE, 2),
        )
        val dups = findDuplicateSplitRefs(entries)
        assertEquals(listOf(2), dups[1L])
        assertEquals(listOf(1), dups[2L])
    }

    @Test
    fun noBibTypesNeverFlaggedRegardlessOfRepetition() {
        val entries = listOf(
            entry(1, null, BibEntryType.CLOCK, 0),
            entry(2, null, BibEntryType.IGNORE, 1),
            entry(3, null, BibEntryType.IGNORE, 2),
            entry(4, null, BibEntryType.SENIORS, 3),
            entry(5, null, BibEntryType.SENIORS, 4),
        )
        assertTrue(findDuplicateSplitRefs(entries).isEmpty())
    }

    @Test
    fun editingOutOfAThreeWayDuplicateGroupUnflagsTheRemainingTwo() {
        val threeWay = listOf(
            entry(1, 101, BibEntryType.FINISH, 1),
            entry(2, 101, BibEntryType.FINISH, 2),
            entry(3, 101, BibEntryType.FINISH, 3),
        )
        val dupsBefore = findDuplicateSplitRefs(threeWay)
        assertEquals(listOf(2, 3), dupsBefore[1L])
        assertEquals(listOf(1, 3), dupsBefore[2L])
        assertEquals(listOf(1, 2), dupsBefore[3L])

        // Entry 3 edited to a different bib — no longer part of the group.
        val afterEdit = listOf(
            entry(1, 101, BibEntryType.FINISH, 1),
            entry(2, 101, BibEntryType.FINISH, 2),
            entry(3, 102, BibEntryType.FINISH, 3),
        )
        val dupsAfter = findDuplicateSplitRefs(afterEdit)
        assertEquals(listOf(2), dupsAfter[1L])
        assertEquals(listOf(1), dupsAfter[2L])
        assertTrue(dupsAfter[3L] == null)
    }

    // countDuplicateExtras

    @Test
    fun noDuplicatesCountsZero() {
        val entries = listOf(
            entry(1, 101, BibEntryType.FINISH, 1),
            entry(2, 102, BibEntryType.FINISH, 2),
        )
        assertEquals(0, countDuplicateExtras(entries))
    }

    @Test
    fun sameBibTwiceCountsOne() {
        val entries = listOf(
            entry(1, 101, BibEntryType.FINISH, 1),
            entry(2, 101, BibEntryType.FINISH, 2),
        )
        assertEquals(1, countDuplicateExtras(entries))
    }

    @Test
    fun sameBibThreeTimesCountsTwo() {
        val entries = listOf(
            entry(1, 101, BibEntryType.FINISH, 1),
            entry(2, 101, BibEntryType.FINISH, 2),
            entry(3, 101, BibEntryType.FINISH, 3),
        )
        assertEquals(2, countDuplicateExtras(entries))
    }

    @Test
    fun twoSeparatePairsCountTwo() {
        val entries = listOf(
            entry(1, 101, BibEntryType.FINISH, 1),
            entry(2, 101, BibEntryType.FINISH, 2),
            entry(3, 102, BibEntryType.FINISH, 3),
            entry(4, 102, BibEntryType.FINISH, 4),
        )
        assertEquals(2, countDuplicateExtras(entries))
    }

    @Test
    fun startForAFinishedBibCountsAsADuplicate() {
        val entries = listOf(
            entry(1, 101, BibEntryType.START, 1),
            entry(2, 101, BibEntryType.FINISH, 2),
        )
        assertEquals(1, countDuplicateExtras(entries))
    }

    // duplicateBibNumbers

    @Test
    fun duplicateBibNumbersListsDistinctBibsInvolvedInAnyDuplicateGroup() {
        val entries = listOf(
            entry(1, 101, BibEntryType.FINISH, 1),
            entry(2, 101, BibEntryType.FINISH, 2),
            entry(3, 105, BibEntryType.FINISH, 3),
            entry(4, 103, BibEntryType.FINISH, 4),
            entry(5, 103, BibEntryType.FINISH, 5),
        )
        assertEquals(listOf(101, 103), duplicateBibNumbers(entries))
    }

    @Test
    fun duplicateBibNumbersEmptyWhenNoDuplicates() {
        val entries = listOf(entry(1, 101, BibEntryType.FINISH, 1))
        assertTrue(duplicateBibNumbers(entries).isEmpty())
    }

    @Test
    fun duplicateBibNumbersIncludesCrossTypeCombinations() {
        // A Start logged for a bib already marked Retire is just as much a dup as the exact
        // same type twice.
        val entries = listOf(
            entry(1, 101, BibEntryType.RETIRE, 1),
            entry(2, 101, BibEntryType.START, 2),
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
            entry(1, 101, BibEntryType.FINISH, 1),
            entry(2, 101, BibEntryType.FINISH, 2),
        )
        assertEquals(2, accountedForRecordCount(entries))
    }

    @Test
    fun retireCountsTowardAccountedForRecords() {
        // A retired bib is accounted for elsewhere and won't cross the line either, so it
        // counts the same as a Finish record for "how many more expected".
        val entries = listOf(entry(1, 101, BibEntryType.RETIRE, 1))
        assertEquals(1, accountedForRecordCount(entries))
    }

    @Test
    fun startDoesNotCountAsAccountedFor() {
        val entries = listOf(entry(1, 102, BibEntryType.START, 1))
        assertEquals(0, accountedForRecordCount(entries))
    }

    // distinctAccountedForBibs / outstandingBibs — naming *which* specific bibs are still
    // outstanding is a different question, where collapsing to a distinct set is correct.

    @Test
    fun duplicateFinishStillNamesTheBibAsSeenOnlyOnce() {
        val entries = listOf(
            entry(1, 101, BibEntryType.FINISH, 1),
            entry(2, 101, BibEntryType.FINISH, 2),
        )
        assertEquals(setOf(101), distinctAccountedForBibs(entries))
        assertEquals(listOf(100, 102, 103, 104), outstandingBibs(entries, rangeStart = 100, rangeCount = 5))
    }

    @Test
    fun retireRemovesABibFromOutstanding() {
        val entries = listOf(entry(1, 103, BibEntryType.RETIRE, 1))
        assertEquals(setOf(103), distinctAccountedForBibs(entries))
        assertEquals(listOf(100, 101, 102, 104), outstandingBibs(entries, rangeStart = 100, rangeCount = 5))
    }

    @Test
    fun startOnlyIsStillOutstanding() {
        val entries = listOf(entry(1, 104, BibEntryType.START, 1))
        assertTrue(distinctAccountedForBibs(entries).isEmpty())
        assertEquals(listOf(100, 101, 102, 103, 104), outstandingBibs(entries, rangeStart = 100, rangeCount = 5))
    }

    @Test
    fun mixOfFinishRetireStartAndOutstandingBibs() {
        val entries = listOf(
            entry(1, 100, BibEntryType.FINISH, 1),
            entry(2, 101, BibEntryType.RETIRE, 2),
            entry(3, 102, BibEntryType.START, 3),
        )
        assertEquals(setOf(100, 101), distinctAccountedForBibs(entries))
        assertEquals(listOf(102, 103, 104), outstandingBibs(entries, rangeStart = 100, rangeCount = 5))
    }

    @Test
    fun outstandingBibsIsEmptyWithoutAConfiguredRange() {
        assertEquals(emptyList<Int>(), outstandingBibs(emptyList(), rangeStart = null, rangeCount = null))
    }
}
