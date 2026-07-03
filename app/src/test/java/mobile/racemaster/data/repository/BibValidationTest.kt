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
    fun startAndFinishSameBibIsNotADuplicate() {
        val entries = listOf(
            entry(1, 101, BibEntryType.START, 1),
            entry(2, 101, BibEntryType.FINISH, 2),
        )
        assertTrue(findDuplicateSplitRefs(entries).isEmpty())
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
    fun startAndFinishSameBibDoesNotCountTowardDuplicates() {
        val entries = listOf(
            entry(1, 101, BibEntryType.START, 1),
            entry(2, 101, BibEntryType.FINISH, 2),
        )
        assertEquals(0, countDuplicateExtras(entries))
    }
}
