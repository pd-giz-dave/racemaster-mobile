package mobile.racemaster.data.repository

import mobile.racemaster.data.db.entity.BIB_REQUIRED_TYPES
import mobile.racemaster.data.db.entity.BibEntryEntity
import mobile.racemaster.data.db.entity.BibEntryType

/** Bib numbers are always 3 digits max, so a configured race range must fit within this. */
const val MIN_BIB_NUMBER = 1
const val MAX_BIB_NUMBER = 999

/** True if no range is configured (defensive default — nothing to reject against). */
fun isBibInLegalRange(bib: Int, rangeStart: Int?, rangeCount: Int?): Boolean {
    if (rangeStart == null || rangeCount == null) return true
    return bib in rangeStart until (rangeStart + rangeCount)
}

/**
 * Maps each entry's id to the split numbers of any other entries sharing its bib number
 * (Start/Finish/Retire only — grouped by bib alone, not bib+type: once a bib has been
 * finished or retired, *any* further Start/Finish/Retire record for it is a duplicate, not
 * just a repeat of the exact same type. A Start logged for a bib that's already finished, or
 * a Finish logged for a bib that's already retired, are just as much a recording error as the
 * same type twice). Recomputed fresh from the live entries list on every emission, so an edit
 * that moves a bib out of a group makes the flags disappear automatically with no separate
 * invalidation step.
 */
fun findDuplicateSplitRefs(entries: List<BibEntryEntity>): Map<Long, List<Int>> {
    val groups = entries
        .filter { it.type in BIB_REQUIRED_TYPES && it.bibNumber != null }
        .groupBy { it.bibNumber }

    val result = mutableMapOf<Long, List<Int>>()
    for (group in groups.values) {
        if (group.size <= 1) continue
        for (entry in group) {
            result[entry.id] = group.filter { it.id != entry.id }.map { it.splitNumber }
        }
    }
    return result
}

/**
 * Counts "extra" duplicates: each bib's group contributes (size - 1) — so a bib entered twice
 * (any combination of Start/Finish/Retire) counts as 1, three times counts as 2, and two
 * separate duplicated bibs count as 1 each (2 total). Matches how an operator would describe
 * "how many dups are there".
 */
fun countDuplicateExtras(entries: List<BibEntryEntity>): Int {
    return entries
        .filter { it.type in BIB_REQUIRED_TYPES && it.bibNumber != null }
        .groupBy { it.bibNumber }
        .values
        .filter { it.size > 1 }
        .sumOf { it.size - 1 }
}

/** Distinct bib numbers involved in any duplicate group, ascending — e.g. a bib logged as
 *  Finish twice, or logged as Start after already being marked Finish/Retire, so the operator
 *  can see at a glance which numbers need fixing up. */
fun duplicateBibNumbers(entries: List<BibEntryEntity>): List<Int> {
    return entries
        .filter { it.type in BIB_REQUIRED_TYPES && it.bibNumber != null }
        .groupBy { it.bibNumber }
        .values
        .filter { it.size > 1 }
        .mapNotNull { it.first().bibNumber }
        .distinct()
        .sorted()
}

// A bib is no longer expected to cross the line once it's FINISH (they crossed) or RETIRE
// (they've been accounted for elsewhere and won't cross) — either way, nothing left to wait
// for from that bib. START doesn't count: they're on course, still expected to show up one
// way or the other.
private val ACCOUNTED_FOR_TYPES = setOf(BibEntryType.FINISH, BibEntryType.RETIRE)

/**
 * How many finishers are still outstanding is purely arithmetic: the expected count minus the
 * raw number of accounted-for records (FINISH or RETIRE) — not a distinct-bib count. A bib
 * logged as FINISH twice by mistake still represents two records for this purpose; it isn't
 * collapsed down to one. That's deliberate: it's a recording error to be corrected later
 * (delete/fix the duplicate), not something the "how many more expected" figure should
 * quietly paper over by guessing which of the two records is the "real" one.
 */
fun accountedForRecordCount(entries: List<BibEntryEntity>): Int = entries.count { it.type in ACCOUNTED_FOR_TYPES }

/** Distinct bib numbers that have at least one FINISH or RETIRE record — used only to name
 *  *which* specific bibs are still outstanding (see [outstandingBibs]), a different question
 *  from "how many" ([accountedForRecordCount]) and one where collapsing duplicates down to a
 *  distinct set is the right thing to do: a bib appearing twice is still just one bib to name.
 *  START doesn't count here either — this is specifically "who's been accounted for". */
fun distinctAccountedForBibs(entries: List<BibEntryEntity>): Set<Int> =
    entries.filter { it.type in ACCOUNTED_FOR_TYPES }.mapNotNull { it.bibNumber }.toSet()

/** Bib numbers within the race's configured range with no FINISH/RETIRE record at all, in
 *  ascending order. Empty (not "everyone", by design) if the range isn't configured. Because
 *  this is based on the distinct set of bibs accounted for (see [distinctAccountedForBibs])
 *  while the "more expected" count is raw ([accountedForRecordCount]), the two can disagree
 *  while a duplicate is still unresolved — expected, not a bug: the list only ever names bibs
 *  never accounted for, regardless of how many records exist for others. */
fun outstandingBibs(entries: List<BibEntryEntity>, rangeStart: Int?, rangeCount: Int?): List<Int> {
    if (rangeStart == null || rangeCount == null) return emptyList()
    val accountedFor = distinctAccountedForBibs(entries)
    return (rangeStart until rangeStart + rangeCount).filterNot { it in accountedFor }
}
