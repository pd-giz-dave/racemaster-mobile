package mobile.racemaster.data.repository

import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.mule.ProgressEntry
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

    // distinctAccountedForBibs — naming *which* specific bibs have been accounted for locally
    // (used by phase 4's outstandingAtLocation/unexpectedBibNumbers below) is a different
    // question from "how many" (accountedForRecordCount), where collapsing to a distinct set is
    // correct.

    @Test
    fun duplicateFinishStillNamesTheBibAsSeenOnlyOnce() {
        val entries = listOf(
            entry(1, 101, HistoryAction.FINISH, 1),
            entry(2, 101, HistoryAction.FINISH, 2),
        )
        assertEquals(setOf(101), distinctAccountedForBibs(entries))
    }

    @Test
    fun retireCountsTowardDistinctAccountedForBibs() {
        val entries = listOf(entry(1, 103, HistoryAction.RETIRE, 1))
        assertEquals(setOf(103), distinctAccountedForBibs(entries))
    }

    @Test
    fun startOnlyDoesNotCountAsAccountedFor() {
        val entries = listOf(entry(1, 104, HistoryAction.START, 1))
        assertTrue(distinctAccountedForBibs(entries).isEmpty())
    }

    // --- Phase 4: progress-record-derived expectation --------------------------------------

    private fun progressEntry(
        bib: Int,
        startTime: String = "",
        finishTime: String = "",
        cpTimes: Map<String, String> = emptyMap(),
    ) = ProgressEntry(bibNumber = bib, startTime = startTime, finishTime = finishTime, cpTimes = cpTimes)

    // starters / finishers / retirees

    @Test
    fun startersAreBibsWithARecordedStartTime() {
        val entries = listOf(progressEntry(101, startTime = "09:00:00"), progressEntry(102))
        assertEquals(setOf(101), starters(entries))
    }

    @Test
    fun finishersAreBibsWithARecordedFinishTime() {
        val entries = listOf(progressEntry(101, finishTime = "10:30:00"), progressEntry(102))
        assertEquals(setOf(101), finishers(entries))
    }

    @Test
    fun retireesAreBibsWithARetireValueAtAnyCp() {
        val entries = listOf(
            progressEntry(101, cpTimes = mapOf("CP1" to "09:10:00", "CP2" to "Retire")),
            progressEntry(102, cpTimes = mapOf("CP1" to "09:12:00")),
        )
        assertEquals(setOf(101), retirees(entries))
    }

    @Test
    fun retireValueIsCaseInsensitive() {
        val entries = listOf(progressEntry(101, cpTimes = mapOf("CP1" to "retire")))
        assertEquals(setOf(101), retirees(entries))
    }

    // observedCpOrder — numeric, not lexicographic

    @Test
    fun observedCpOrderSortsNumericallyNotLexicographically() {
        val entries = listOf(
            progressEntry(101, cpTimes = mapOf("CP10" to "11:00:00", "CP2" to "09:30:00", "CP1" to "09:00:00")),
        )
        // A plain string sort would put "CP10" before "CP2" — this must not.
        assertEquals(listOf(1, 2, 10), observedCpOrder(entries))
    }

    @Test
    fun observedCpOrderDeduplicatesAcrossEntriesAndTakesTheNumberOnlyFromANamedCp() {
        val entries = listOf(
            progressEntry(101, cpTimes = mapOf("CP1" to "09:00:00", "CP2-Bridge" to "09:30:00")),
            progressEntry(102, cpTimes = mapOf("CP1" to "09:05:00")),
        )
        assertEquals(listOf(1, 2), observedCpOrder(entries))
    }

    // expectedBibsAtLocation

    @Test
    fun cp1ExpectsAllStarters() {
        val entries = listOf(
            progressEntry(101, startTime = "09:00:00"),
            progressEntry(102, startTime = "09:00:05"),
            progressEntry(103),
        )
        assertEquals(setOf(101, 102), expectedBibsAtLocation(entries, "CP1"))
    }

    @Test
    fun cp2ExpectsBibsThatPassedCp1AndDidNotRetireThere() {
        val entries = listOf(
            progressEntry(101, startTime = "09:00:00", cpTimes = mapOf("CP1" to "09:10:00")),
            progressEntry(102, startTime = "09:00:05", cpTimes = mapOf("CP1" to "Retire")),
            progressEntry(103, startTime = "09:00:10"),
        )
        assertEquals(setOf(101), expectedBibsAtLocation(entries, "CP2"))
    }

    @Test
    fun finishExpectsBibsThatPassedTheLastObservedCp() {
        val entries = listOf(
            progressEntry(101, cpTimes = mapOf("CP1" to "09:10:00", "CP2" to "09:40:00")),
            progressEntry(102, cpTimes = mapOf("CP1" to "09:11:00")), // hasn't reached CP2 yet
        )
        assertEquals(setOf(101), expectedBibsAtLocation(entries, "Finish"))
    }

    @Test
    fun finishFallsBackToStartersWhenNoCpsHaveBeenObservedAtAll() {
        val entries = listOf(progressEntry(101, startTime = "09:00:00"))
        assertEquals(setOf(101), expectedBibsAtLocation(entries, "Finish"))
    }

    @Test
    fun anUnrecognisedLocationExpectsNobody() {
        // Not "Finish" and not a "CP#..." this race has actually seen a cpTimes entry for —
        // empty, not "everyone", same defensive default the old range model used.
        val entries = listOf(progressEntry(101, startTime = "09:00:00"))
        assertTrue(expectedBibsAtLocation(entries, "Somewhere Else").isEmpty())
        assertTrue(expectedBibsAtLocation(entries, "CP5").isEmpty()) // CP5 never actually observed
    }

    @Test
    fun cpLocationWithANameSuffixStillMatchesByNumber() {
        val entries = listOf(progressEntry(101, startTime = "09:00:00"))
        assertEquals(setOf(101), expectedBibsAtLocation(entries, "CP1-Bridge"))
    }

    // outstandingAtLocation / unexpectedBibNumbers / unexpectedBibWarning

    @Test
    fun outstandingAtLocationIsExpectedMinusLocallyAccountedFor() {
        val progress = listOf(progressEntry(101, startTime = "09:00:00"), progressEntry(102, startTime = "09:00:05"))
        val local = listOf(entry(1, 101, HistoryAction.PASS, 1))
        assertEquals(listOf(102), outstandingAtLocation(local, progress, "CP1"))
    }

    @Test
    fun unexpectedBibNumbersFlagsALocallyRecordedBibNotInTheExpectedSet() {
        val progress = listOf(progressEntry(101, startTime = "09:00:00"))
        val local = listOf(entry(1, 999, HistoryAction.PASS, 1))
        assertEquals(listOf(999), unexpectedBibNumbers(local, progress, "CP1"))
    }

    @Test
    fun unexpectedBibNumbersIsEmptyWhenThereIsNothingToJudgeByYet() {
        val local = listOf(entry(1, 101, HistoryAction.PASS, 1))
        assertTrue(unexpectedBibNumbers(local, emptyList(), "CP1").isEmpty())
    }

    @Test
    fun unexpectedBibWarningNullForExpectedBib() {
        assertEquals(null, unexpectedBibWarning(101, expectedBibs = setOf(101, 102)))
    }

    @Test
    fun unexpectedBibWarningNullWhenNothingIsExpectedYet() {
        assertEquals(null, unexpectedBibWarning(999, expectedBibs = emptySet()))
    }

    @Test
    fun unexpectedBibWarningFlagsABibOutsideTheExpectedSet() {
        assertEquals("not expected at this location", unexpectedBibWarning(999, expectedBibs = setOf(101, 102)))
    }

    // Generic extractor-lambda core (findDuplicateSplitRefs/findDuplicateSplitRefsPerSegment
    // overloads) — this is what MuleSourceDetailViewModel calls to share the exact same
    // duplicate rule for pulled Mule records (keyed by recordUuid, a String) instead of a
    // hand-duplicated copy of the HistoryLineEntity-specific logic above. A synthetic non-
    // HistoryLineEntity row type here is what actually proves the generic core works for a
    // different key type and shape, not just as a same-behavior wrapper.

    private data class FakeRow(val uuid: String, val bibNumber: Int?, val action: HistoryAction, val splitNumber: Int, val lineNumber: Long)

    @Test
    fun genericCoreFlagsDuplicatesKeyedByAnArbitraryKeyType() {
        val rows = listOf(
            FakeRow("a", 101, HistoryAction.FINISH, 1, 1L),
            FakeRow("b", 101, HistoryAction.FINISH, 2, 2L),
        )
        val dups = findDuplicateSplitRefs(rows, { it.uuid }, { it.bibNumber }, { it.action }, { it.splitNumber })
        assertEquals(listOf(2), dups["a"])
        assertEquals(listOf(1), dups["b"])
    }

    @Test
    fun genericPerSegmentCoreRespectsResetAndUndoJustLikeTheHistoryLineEntityOverload() {
        val rows = listOf(
            FakeRow("a", 101, HistoryAction.FINISH, 1, 1L),
            FakeRow("b", 101, HistoryAction.FINISH, 2, 2L),
            FakeRow("undo-b", null, HistoryAction.UNDO, 2, 3L), // undoes "b"
            FakeRow("reset", null, HistoryAction.RESET, 0, 4L),
            FakeRow("c", 101, HistoryAction.FINISH, 1, 5L),
        )
        val dups = findDuplicateSplitRefsPerSegment(
            rows,
            lineNumberOf = { it.lineNumber },
            refLineNumberOf = { if (it.uuid == "undo-b") 2L else null },
            isUndoMarker = { it.action == HistoryAction.UNDO },
            isReset = { it.action == HistoryAction.RESET },
            keyOf = { it.uuid },
            bibNumberOf = { it.bibNumber },
            actionOf = { it.action },
            splitNumberOf = { it.splitNumber },
        )
        // "b" is undone (excluded), "reset" starts a fresh segment, so "a" and "c" are each
        // alone in their own segment — no duplicates anywhere.
        assertTrue(dups.isEmpty())
    }
}
