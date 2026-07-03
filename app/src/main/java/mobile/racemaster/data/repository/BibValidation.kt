package mobile.racemaster.data.repository

import mobile.racemaster.data.db.entity.BIB_REQUIRED_TYPES
import mobile.racemaster.data.db.entity.BibEntryEntity

/** Bib numbers are always 3 digits max, so a configured race range must fit within this. */
const val MIN_BIB_NUMBER = 1
const val MAX_BIB_NUMBER = 999

/** True if no range is configured (defensive default — nothing to reject against). */
fun isBibInLegalRange(bib: Int, rangeStart: Int?, rangeCount: Int?): Boolean {
    if (rangeStart == null || rangeCount == null) return true
    return bib in rangeStart until (rangeStart + rangeCount)
}

/**
 * Maps each entry's id to the split numbers of any other entries sharing its bib number and
 * type (Start/Finish/Retire only — a bib can have at most one of each). Recomputed fresh from
 * the live entries list on every emission, so an edit that moves a bib/type out of a group
 * makes the flags disappear automatically with no separate invalidation step.
 */
fun findDuplicateSplitRefs(entries: List<BibEntryEntity>): Map<Long, List<Int>> {
    val groups = entries
        .filter { it.type in BIB_REQUIRED_TYPES && it.bibNumber != null }
        .groupBy { it.bibNumber to it.type }

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
 * Counts "extra" duplicates: each (bib, type) group contributes (size - 1) — so a bib entered
 * twice counts as 1, three times counts as 2, and two separate duplicated bibs count as 1 each
 * (2 total). Matches how an operator would describe "how many dups are there".
 */
fun countDuplicateExtras(entries: List<BibEntryEntity>): Int {
    return entries
        .filter { it.type in BIB_REQUIRED_TYPES && it.bibNumber != null }
        .groupBy { it.bibNumber to it.type }
        .values
        .filter { it.size > 1 }
        .sumOf { it.size - 1 }
}
