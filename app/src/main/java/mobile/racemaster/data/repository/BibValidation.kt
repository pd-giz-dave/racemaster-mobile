package mobile.racemaster.data.repository

import mobile.racemaster.data.db.entity.BIB_REQUIRED_ACTIONS
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity

/** Bib numbers are always 3 digits max, so a configured race range must fit within this. */
const val MIN_BIB_NUMBER = 1
const val MAX_BIB_NUMBER = 999

/** True if no range is configured (defensive default — nothing to reject against). */
fun isBibInLegalRange(bib: Int, rangeStart: Int?, rangeCount: Int?): Boolean {
    if (rangeStart == null || rangeCount == null) return true
    return bib in rangeStart until (rangeStart + rangeCount)
}

// A bib is no longer expected to cross the line once it's FINISH (they crossed) or RETIRE
// (they've been accounted for elsewhere and won't cross) — either way, nothing left to wait
// for from that bib. START doesn't count: they're on course, still expected to show up one
// way or the other.
private val ACCOUNTED_FOR_ACTIONS = setOf(HistoryAction.FINISH, HistoryAction.RETIRE)

/**
 * Maps each entry's id to the split numbers of any other entries it duplicates. A bib has
 * exactly two independent slots, each legitimately filled at most once: a Start (on course,
 * not yet accounted for) and a "crossing" record — a Finish (they crossed) or a Retire
 * (accounted for elsewhere, won't cross either way), never both. So a lone Start, a lone
 * Finish, a lone Retire, a Start+Finish, or a Start+Retire are all normal, non-duplicate
 * states — only *more than one* Start, or *more than one* crossing record (two Finishes, two
 * Retires, or a Finish and a Retire together — any combination means the same bib was
 * recorded crossing more than once), counts as a duplicate. The two slots are flagged
 * independently: an excess Start never flags a legitimate paired Finish/Retire, and vice
 * versa. Recomputed fresh from the live entries list on every emission, so an edit that moves
 * a bib out of a group makes the flags disappear automatically with no separate invalidation
 * step.
 */
fun findDuplicateSplitRefs(entries: List<HistoryLineEntity>): Map<Long, List<Int>> {
    val groups = entries
        .filter { it.action in BIB_REQUIRED_ACTIONS && it.bibNumber != null }
        .groupBy { it.bibNumber }

    val result = mutableMapOf<Long, List<Int>>()
    for (group in groups.values) {
        flagExcess(group.filter { it.action == HistoryAction.START }, result)
        flagExcess(group.filter { it.action in ACCOUNTED_FOR_ACTIONS }, result)
    }
    return result
}

private fun flagExcess(group: List<HistoryLineEntity>, result: MutableMap<Long, List<Int>>) {
    if (group.size <= 1) return
    for (entry in group) {
        result[entry.id] = group.filter { it.id != entry.id }.map { it.splitNumber }
    }
}

/**
 * Splits [entries] into segments bounded by RESET markers (mirroring BibsModeRepository's own
 * segment boundary), folds each segment down to only its currently-visible rows (exactly what
 * BibsModeViewModel's own live current-segment feed already is — see
 * BibsModeRepository.observeCurrentSegmentEntries and HistoryFold.foldLatestVisible), runs
 * [findDuplicateSplitRefs] independently within each folded segment, then merges the results —
 * row ids are globally unique so a plain merge is safe. Two things the live screen already gets
 * for free from only ever seeing the folded current segment, that Race History's full raw
 * multi-segment history must instead account for explicitly: a bib number legitimately reused in
 * a later segment must not be flagged against an earlier, already-reset-away segment; and a row
 * that's since been undone (or superseded by a later edit) must not keep counting toward a
 * duplicate — only whatever's still actually visible should ever be flagged, same as the operator
 * would see live.
 */
fun findDuplicateSplitRefsPerSegment(entries: List<HistoryLineEntity>): Map<Long, List<Int>> {
    val ascending = entries.sortedBy { it.lineNumber }
    val segments = mutableListOf<MutableList<HistoryLineEntity>>()
    var current = mutableListOf<HistoryLineEntity>()
    for (row in ascending) {
        current.add(row)
        if (row.action == HistoryAction.RESET) {
            segments.add(current)
            current = mutableListOf()
        }
    }
    if (current.isNotEmpty()) segments.add(current)
    val result = mutableMapOf<Long, List<Int>>()
    for (segment in segments) {
        val visible = foldLatestVisible(segment, { it.lineNumber }, { it.refLineNumber }, { it.action == HistoryAction.UNDO })
        result.putAll(findDuplicateSplitRefs(visible))
    }
    return result
}

/**
 * Counts "extra" duplicates: for each bib, an excess Start count (beyond the one legitimate
 * Start) plus an excess crossing count (beyond the one legitimate Finish-or-Retire) — see
 * [findDuplicateSplitRefs] for why the two slots are counted independently rather than the
 * group's raw size. A bib entered twice as Finish counts as 1, three times as 2; a bib with
 * both an excess Start *and* an excess crossing counts both. Matches how an operator would
 * describe "how many dups are there".
 */
fun countDuplicateExtras(entries: List<HistoryLineEntity>): Int {
    return entries
        .filter { it.action in BIB_REQUIRED_ACTIONS && it.bibNumber != null }
        .groupBy { it.bibNumber }
        .values
        .sumOf { group ->
            val starts = group.count { it.action == HistoryAction.START }
            val crossings = group.count { it.action in ACCOUNTED_FOR_ACTIONS }
            (starts - 1).coerceAtLeast(0) + (crossings - 1).coerceAtLeast(0)
        }
}

/** Distinct bib numbers involved in any duplicate (see [findDuplicateSplitRefs]'s doc for what
 *  counts), ascending — so the operator can see at a glance which numbers need fixing up. */
fun duplicateBibNumbers(entries: List<HistoryLineEntity>): List<Int> {
    return entries
        .filter { it.action in BIB_REQUIRED_ACTIONS && it.bibNumber != null }
        .groupBy { it.bibNumber }
        .values
        .filter { group ->
            val starts = group.count { it.action == HistoryAction.START }
            val crossings = group.count { it.action in ACCOUNTED_FOR_ACTIONS }
            starts > 1 || crossings > 1
        }
        .mapNotNull { it.first().bibNumber }
        .distinct()
        .sorted()
}

/**
 * How many finishers are still outstanding is purely arithmetic: the expected count minus the
 * raw number of accounted-for records (FINISH or RETIRE) — not a distinct-bib count. A bib
 * logged as FINISH twice by mistake still represents two records for this purpose; it isn't
 * collapsed down to one. That's deliberate: it's a recording error to be corrected later
 * (delete/fix the duplicate), not something the "how many more expected" figure should
 * quietly paper over by guessing which of the two records is the "real" one.
 */
fun accountedForRecordCount(entries: List<HistoryLineEntity>): Int = entries.count { it.action in ACCOUNTED_FOR_ACTIONS }

/** Distinct bib numbers that have at least one FINISH or RETIRE record — used only to name
 *  *which* specific bibs are still outstanding (see [outstandingBibs]), a different question
 *  from "how many" ([accountedForRecordCount]) and one where collapsing duplicates down to a
 *  distinct set is the right thing to do: a bib appearing twice is still just one bib to name.
 *  START doesn't count here either — this is specifically "who's been accounted for". */
fun distinctAccountedForBibs(entries: List<HistoryLineEntity>): Set<Int> =
    entries.filter { it.action in ACCOUNTED_FOR_ACTIONS }.mapNotNull { it.bibNumber }.toSet()

/** Bib numbers within the race's configured range with no FINISH/RETIRE record at all, in
 *  ascending order. Empty (not "everyone", by design) if the range isn't configured. Because
 *  this is based on the distinct set of bibs accounted for (see [distinctAccountedForBibs])
 *  while the "more expected" count is raw ([accountedForRecordCount]), the two can disagree
 *  while a duplicate is still unresolved — expected, not a bug: the list only ever names bibs
 *  never accounted for, regardless of how many records exist for others. */
fun outstandingBibs(entries: List<HistoryLineEntity>, rangeStart: Int?, rangeCount: Int?): List<Int> {
    if (rangeStart == null || rangeCount == null) return emptyList()
    val accountedFor = distinctAccountedForBibs(entries)
    return (rangeStart until rangeStart + rangeCount).filterNot { it in accountedFor }
}
