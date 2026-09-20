package mobile.racemaster.data.repository

import mobile.racemaster.data.db.entity.BIB_REQUIRED_ACTIONS
import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.mule.ProgressEntry

// A bib is no longer expected to cross the line once it's FINISH (they crossed), PASS (they
// passed this checkpoint — CP Mode's own equivalent of FINISH), or RETIRE (they've been
// accounted for elsewhere and won't cross) — either way, nothing left to wait for from that
// bib. START doesn't count: they're on course, still expected to show up one way or the other.
private val ACCOUNTED_FOR_ACTIONS = setOf(HistoryAction.FINISH, HistoryAction.PASS, HistoryAction.RETIRE)

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
fun findDuplicateSplitRefs(entries: List<HistoryLineEntity>): Map<Long, List<Int?>> =
    findDuplicateSplitRefs(entries, { it.id }, { it.bibNumber }, { it.action }, { it.splitNumber })

/**
 * Generic core behind [findDuplicateSplitRefs] above — pulled out with extractor lambdas (same
 * pattern as [foldLatestVisible]) so a *pulled* Mule record can share this exact duplicate rule
 * with a local race's own [HistoryLineEntity] rows, rather than a hand-duplicated copy that
 * risks silently drifting out of sync — see
 * [mobile.racemaster.ui.racehistory.MuleSourceDetailViewModel] for that call site.
 */
fun <T, K> findDuplicateSplitRefs(
    entries: List<T>,
    keyOf: (T) -> K,
    bibNumberOf: (T) -> Int?,
    actionOf: (T) -> HistoryAction,
    splitNumberOf: (T) -> Int?,
): Map<K, List<Int?>> {
    val groups = entries
        .filter { actionOf(it) in BIB_REQUIRED_ACTIONS && bibNumberOf(it) != null }
        .groupBy { bibNumberOf(it) }

    val result = mutableMapOf<K, List<Int?>>()
    for (group in groups.values) {
        flagExcess(group.filter { actionOf(it) == HistoryAction.START }, keyOf, splitNumberOf, result)
        flagExcess(group.filter { actionOf(it) in ACCOUNTED_FOR_ACTIONS }, keyOf, splitNumberOf, result)
    }
    return result
}

private fun <T, K> flagExcess(group: List<T>, keyOf: (T) -> K, splitNumberOf: (T) -> Int?, result: MutableMap<K, List<Int?>>) {
    if (group.size <= 1) return
    for (entry in group) {
        val key = keyOf(entry)
        result[key] = group.filter { keyOf(it) != key }.map { splitNumberOf(it) }
    }
}

/**
 * Splits [entries] into segments bounded by RESET **or** LOCATION markers (mirroring
 * BibsModeRepository's own segment boundary — see HistoryFold.sinceLastLocationMarker's own doc
 * for why the live screen needs the LOCATION half of this too), folds each segment down to only
 * its currently-visible rows (exactly what BibsModeViewModel's own live current-segment feed
 * already is — see BibsModeRepository.observeCurrentSegmentEntries and
 * HistoryFold.foldLatestVisible), runs [findDuplicateSplitRefs] independently within each folded
 * segment, then merges the results — row ids are globally unique so a plain merge is safe. Two
 * things the live screen already gets for free from only ever seeing the folded current segment,
 * that Race History's full raw multi-segment history must instead account for explicitly: a bib
 * number legitimately reused in a later segment (whether the boundary between them was a Reset or
 * a relocation) must not be flagged against an earlier one; and a row that's since been undone (or
 * superseded by a later edit) must not keep counting toward a duplicate — only whatever's still
 * actually visible should ever be flagged, same as the operator would see live.
 */
fun findDuplicateSplitRefsPerSegment(entries: List<HistoryLineEntity>): Map<Long, List<Int?>> =
    findDuplicateSplitRefsPerSegment(
        entries,
        lineNumberOf = { it.lineNumber },
        refLineNumberOf = { it.refLineNumber },
        isUndoMarker = { it.action == HistoryAction.UNDO },
        isSegmentBoundary = { it.action == HistoryAction.RESET || it.action == HistoryAction.LOCATION },
        keyOf = { it.id },
        bibNumberOf = { it.bibNumber },
        actionOf = { it.action },
        splitNumberOf = { it.splitNumber },
    )

/**
 * Generic core behind [findDuplicateSplitRefsPerSegment] above — see its doc for the actual
 * segment/fold rules this applies; pulled out with extractor lambdas for the same reason
 * [findDuplicateSplitRefs]'s own generic core is, so a pulled Mule record can share it too.
 */
fun <T, K> findDuplicateSplitRefsPerSegment(
    entries: List<T>,
    lineNumberOf: (T) -> Long,
    refLineNumberOf: (T) -> Long?,
    isUndoMarker: (T) -> Boolean,
    isSegmentBoundary: (T) -> Boolean,
    keyOf: (T) -> K,
    bibNumberOf: (T) -> Int?,
    actionOf: (T) -> HistoryAction,
    splitNumberOf: (T) -> Int?,
): Map<K, List<Int?>> {
    val ascending = entries.sortedBy { lineNumberOf(it) }
    val segments = mutableListOf<MutableList<T>>()
    var current = mutableListOf<T>()
    for (row in ascending) {
        current.add(row)
        if (isSegmentBoundary(row)) {
            segments.add(current)
            current = mutableListOf()
        }
    }
    if (current.isNotEmpty()) segments.add(current)
    val result = mutableMapOf<K, List<Int?>>()
    for (segment in segments) {
        val visible = foldLatestVisible(segment, lineNumberOf, refLineNumberOf, isUndoMarker)
        result.putAll(findDuplicateSplitRefs(visible, keyOf, bibNumberOf, actionOf, splitNumberOf))
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

/** Distinct bib numbers that have at least one FINISH/PASS/RETIRE record — used to name *which*
 *  specific bibs this device has already accounted for at its own location (see
 *  [outstandingAtLocation]/[unexpectedBibNumbers]), collapsing duplicates down to a distinct set
 *  since a bib appearing twice is still just one bib to name. START doesn't count here either —
 *  this is specifically "who's been accounted for". */
fun distinctAccountedForBibs(entries: List<HistoryLineEntity>): Set<Int> =
    entries.filter { it.action in ACCOUNTED_FOR_ACTIONS }.mapNotNull { it.bibNumber }.toSet()

// --- Phase 4: progress-record-derived expectation (replaces the old configured-range model) ---
//
// TODO.md's own model: the course is ordered CP1..CPn..Finish, with the order itself derived
// purely from whichever CP labels actually appear across every phone's own contributed
// [ProgressEntry.cpTimes] — never a separately-configured course definition (that concept was
// dropped in phase 1). All starters are expected at CP1; a bib that's passed (recorded, but not
// retired) CPn is expected at CPn+1; a bib that's passed the last CP is expected at Finish.

private val CP_NUMBER_REGEX = Regex("^CP(\\d+)", RegexOption.IGNORE_CASE)

/** Extracts the CP number from a location/cpTimes-key string like "CP2" or "CP2-Bridge" — null
 *  for anything that doesn't look like a CP label at all (e.g. "Finish", "Start", or a free-form
 *  Bibs/Time Mode location no one's bothered to make CP-shaped). */
private fun cpNumber(label: String): Int? = CP_NUMBER_REGEX.find(label.trim())?.groupValues?.get(1)?.toIntOrNull()

/** The full CP1..CPn ordering for a race — ascending by number (so CP10 sorts after CP2, not
 *  before it, unlike a plain string sort), deduplicated across however many entries/devices
 *  contributed a cpTimes key for the same CP. */
fun observedCpOrder(entries: List<ProgressEntry>): List<Int> =
    entries.flatMap { it.cpTimes.keys }.mapNotNull(::cpNumber).distinct().sorted()

// A cpTimes value of "Retire" means retired at that CP specifically — everywhere else in this
// file "retired"/"passed" are judged per-CP, matching TODO.md's own "passing, and not retiring,
// CPn" qualifier.
private fun cpTimeAt(entry: ProgressEntry, cpNum: Int): String? =
    entry.cpTimes.entries.firstOrNull { cpNumber(it.key) == cpNum }?.value

private fun passedCp(entry: ProgressEntry, cpNum: Int): Boolean {
    val value = cpTimeAt(entry, cpNum) ?: return false
    return value.isNotBlank() && !value.equals("Retire", ignoreCase = true)
}

/** Distinct bib numbers with a recorded start — TODO.md's "starters". */
fun starters(entries: List<ProgressEntry>): Set<Int> =
    entries.filter { it.startTime.isNotBlank() }.map { it.bibNumber }.toSet()

/** Distinct bib numbers with a recorded finish — TODO.md's "finishers". */
fun finishers(entries: List<ProgressEntry>): Set<Int> =
    entries.filter { it.finishTime.isNotBlank() }.map { it.bibNumber }.toSet()

/** Distinct bib numbers retired at any CP at all — TODO.md's "retirees". A bib can only retire
 *  once in practice, but this doesn't assume that; any "Retire" value anywhere in its cpTimes
 *  counts. */
fun retirees(entries: List<ProgressEntry>): Set<Int> =
    entries.filter { e -> e.cpTimes.values.any { it.equals("Retire", ignoreCase = true) } }.map { it.bibNumber }.toSet()

/** Which bibs are expected to arrive at [raceLocation] right now, derived purely from shared
 *  progress records — see this section's own top-of-file doc for the CP1..CPn..Finish model.
 *  Empty — not "everyone", by the same defensive-default convention [distinctAccountedForBibs]'s
 *  own callers already rely on — when [raceLocation] doesn't correspond to a recognizable
 *  station at all (not "Finish", and not a "CP#..." location whose number the race has actually
 *  seen a cpTimes entry for). */
fun expectedBibsAtLocation(entries: List<ProgressEntry>, raceLocation: String): Set<Int> {
    val trimmedLocation = raceLocation.trim()
    if (trimmedLocation.equals("Finish", ignoreCase = true)) {
        // The last CP is the one genuinely-derived piece here — Finish itself carries no
        // number of its own, so there's no arithmetic predecessor to fall back on the way
        // CPn's own CP(n-1) below can. No CPs observed at all yet (a course with none, or one
        // where nobody's reached the first one yet) falls back to starters, same reasoning as
        // CP1 below.
        val lastCp = observedCpOrder(entries).lastOrNull() ?: return starters(entries)
        return entries.filter { passedCp(it, lastCp) }.map { it.bibNumber }.toSet()
    }
    val here = cpNumber(trimmedLocation) ?: return emptySet()
    // CPn's predecessor is always CP(n-1) by the numbering convention itself (isValidCpLocation
    // already requires "CP" + a number) — deliberately plain arithmetic, not a position derived
    // from observedCpOrder: an unobserved CP(n-1) must mean "nobody's expected here yet"
    // (empty, via passedCp safely returning false for everyone), never "treat CPn as if it
    // were the first station" just because nothing earlier happens to be in the data yet — only
    // CP1 itself (literally numbered 1) is ever the unconditional starters case.
    if (here == 1) return starters(entries)
    val previousCp = here - 1
    return entries.filter { passedCp(it, previousCp) }.map { it.bibNumber }.toSet()
}

/** Bib numbers expected at [raceLocation] (see [expectedBibsAtLocation]) that this device
 *  hasn't itself already accounted for (see [distinctAccountedForBibs] — a device only ever
 *  records what happens at its own station, so "accounted for" is inherently local), ascending.
 *  This is [outstandingBibs]'s phase-4 replacement: the expected set now comes from shared
 *  progress records instead of a configured contiguous range, but the "expected minus
 *  already-accounted-for-here" shape stays identical. */
fun outstandingAtLocation(
    localEntries: List<HistoryLineEntity>,
    progressEntries: List<ProgressEntry>,
    raceLocation: String,
): List<Int> {
    val expected = expectedBibsAtLocation(progressEntries, raceLocation)
    val accountedFor = distinctAccountedForBibs(localEntries)
    return (expected - accountedFor).sorted()
}

/** Distinct bib numbers this device has locally recorded (see [distinctAccountedForBibs]) that
 *  aren't in the expected set for this location at all — flagged for operator awareness
 *  (TODO.md: "allowed but flagged in a similar manner to the current range/duplicate check"),
 *  never blocked, ascending. Empty when nothing's expected to compare against yet (see
 *  [expectedBibsAtLocation]'s own defensive-empty doc) — an empty expected set here means "no
 *  data to judge by", not "everything's unexpected". */
fun unexpectedBibNumbers(
    localEntries: List<HistoryLineEntity>,
    progressEntries: List<ProgressEntry>,
    raceLocation: String,
): List<Int> {
    val expected = expectedBibsAtLocation(progressEntries, raceLocation)
    if (expected.isEmpty()) return emptyList()
    return distinctAccountedForBibs(localEntries).filterNot { it in expected }.sorted()
}

/** Per-entry flagged-warning text for a bib outside [expectedBibs] — the phase-4 replacement for
 *  the old range-based per-entry warning, attached the same way EntryLogUi's own warning field
 *  already surfaces one. Null when [bib] is null, expected, or [expectedBibs] is empty (nothing
 *  to judge by yet — same defensive default as [unexpectedBibNumbers]). */
fun unexpectedBibWarning(bib: Int?, expectedBibs: Set<Int>): String? {
    if (bib == null || expectedBibs.isEmpty() || bib in expectedBibs) return null
    return "not expected at this location"
}
