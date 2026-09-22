package mobile.racemaster.data.repository

/**
 * Reconstructs "what's currently visible" from a raw, append-only row list where Undo/Edit no
 * longer delete/mutate but instead append a new row (see HistoryLineEntity's `refLineNumber`
 * doc). Groups rows by their logical identity (`refLineNumberOf(it) ?: lineNumberOf(it)` — the
 * original ROOT row's lineNumber), keeps only the highest-`lineNumber` row per group (the latest
 * echo, or the original if it was never edited), drops any group whose latest row is an
 * undo-marker, and sorts by the ROOT key rather than the latest row's own (possibly much later)
 * lineNumber — so editing an entry updates its displayed content without moving its position in
 * the newest-first list.
 *
 * [rows] must have RESET (and PING) rows already filtered out before being passed in — a RESET
 * row's own `refLineNumber` points at a *different* row (the LOCATION it invalidates, not its
 * own edit history — see HistoryAction.RESET's own doc), so leaving it in would let it hijack
 * that LOCATION row's own fold group and silently make the LOCATION vanish as if edited away.
 */
fun <T> foldLatestVisible(
    rows: List<T>,
    lineNumberOf: (T) -> Long,
    refLineNumberOf: (T) -> Long?,
    isUndoMarker: (T) -> Boolean,
): List<T> =
    rows.groupBy { refLineNumberOf(it) ?: lineNumberOf(it) }
        .mapValues { (_, group) -> group.maxBy { lineNumberOf(it) } }
        .values
        .filterNot { isUndoMarker(it) }
        .sortedByDescending { refLineNumberOf(it) ?: lineNumberOf(it) }

/**
 * One visit to a location, within one mode's history — the span from one LOCATION row
 * (inclusive, [locationLineNumber]/[note] are that row's own) up to, but not including, the next
 * LOCATION row of any note. [rows] carries every row belonging to this visit, in the same order
 * they were passed to [visits].
 */
data class LocationVisit<T>(val locationLineNumber: Long, val note: String?, val rows: List<T>)

/**
 * Splits [rows] (already fold-collapsed — see [foldLatestVisible] — and ascending by lineNumber)
 * into [LocationVisit]s. Rows before the first surviving LOCATION marker are bucketed into an
 * implicit leading visit of their own (`locationLineNumber = 0`, `note = null`) rather than
 * dropped — this normally never happens on freshly-written data (a race's very first row for a
 * mode is always its own LOCATION marker, see RaceRepository.recordModeStart), but a LOCATION
 * marker that's since been *undone* has its whole fold group removed by [foldLatestVisible]
 * before this ever runs, which would otherwise leave whatever came before it (e.g. an earlier
 * real entry) with no visit to belong to and silently invisible — confirmed as a real, reachable
 * bug: relocate, then immediately Undo the relocate, must reveal whatever was visible before it,
 * exactly as it did before location-grouping existed. A real RESET row's own `refLineNumber` can
 * never target line 0, so this implicit visit can never be individually closed by one either —
 * same "no boundary recognized, show everything" degradation.
 */
fun <T> visits(
    rows: List<T>,
    lineNumberOf: (T) -> Long,
    noteOf: (T) -> String?,
    isLocationMarker: (T) -> Boolean,
): List<LocationVisit<T>> {
    val result = mutableListOf<LocationVisit<T>>()
    var currentLine: Long? = null
    var currentNote: String? = null
    var currentRows = mutableListOf<T>()
    for (row in rows) {
        if (isLocationMarker(row)) {
            if (currentLine != null) result.add(LocationVisit(currentLine, currentNote, currentRows))
            currentLine = lineNumberOf(row)
            currentNote = noteOf(row)
            currentRows = mutableListOf(row)
        } else {
            if (currentLine == null) currentLine = 0L
            currentRows.add(row)
        }
    }
    if (currentLine != null) result.add(LocationVisit(currentLine, currentNote, currentRows))
    return result
}

/**
 * Every LOCATION lineNumber a RESET row (anywhere in [rawRows], which — unlike [visits]' own
 * input — must NOT have RESET rows filtered out) has targeted via its own `refLineNumber` — see
 * HistoryAction.RESET's own doc. A visit whose own `locationLineNumber` is in this set has been
 * individually closed and never becomes current again, even if a later visit shares its note.
 */
fun <T> resetTargets(
    rawRows: List<T>,
    refLineNumberOf: (T) -> Long?,
    isResetMarker: (T) -> Boolean,
): Set<Long> = rawRows.filter(isResetMarker).mapNotNull(refLineNumberOf).toSet()

/**
 * The current segment for one mode: every visit sharing the most recent LOCATION's own note,
 * except any individually closed by [resetTargetLineNumbers] — see HistoryAction's own top-of-
 * file "What does reset mean?" doc. This is what makes relocating back to an earlier, not-yet-
 * reset location resume that location's own segment (merging its old and new rows into one live
 * view) rather than starting fresh: TODO.md's own "when returning to a previous location the
 * mode screen should look like it was when they left". Empty when there are no visits at all, or
 * when the most recent one has itself been reset (nothing current for this mode right now).
 */
fun <T> currentSegmentVisits(
    allVisits: List<LocationVisit<T>>,
    resetTargetLineNumbers: Set<Long>,
): List<LocationVisit<T>> {
    val mostRecent = allVisits.lastOrNull() ?: return emptyList()
    if (mostRecent.locationLineNumber in resetTargetLineNumbers) return emptyList()
    return allVisits.filter { it.note == mostRecent.note && it.locationLineNumber !in resetTargetLineNumbers }
}

/**
 * The live, display-ready current-segment row list for one mode — folds [rawRows] (unfiltered:
 * every action, every mode-scoped row this race has ever written), excludes [isDisplayExcluded]
 * rows (MODE_START/NEW_RACE/RESET/PING — see each action's own doc) before folding, groups what's
 * left into [visits], and unions whichever visits make up [currentSegmentVisits] — sorted
 * newest-first, same convention [foldLatestVisible] alone already used before this merge was
 * possible. This is the single source of truth both the live mode screens (current-segment
 * splits/entries) and Undo (whose target is always this list's own first entry) now share.
 */
fun <T> currentSegmentRows(
    rawRows: List<T>,
    lineNumberOf: (T) -> Long,
    refLineNumberOf: (T) -> Long?,
    noteOf: (T) -> String?,
    isLocationMarker: (T) -> Boolean,
    isResetMarker: (T) -> Boolean,
    isUndoMarker: (T) -> Boolean,
    isDisplayExcluded: (T) -> Boolean,
): List<T> {
    val targets = resetTargets(rawRows, refLineNumberOf, isResetMarker)
    val displayFiltered = rawRows.filterNot(isDisplayExcluded)
    val folded = foldLatestVisible(displayFiltered, lineNumberOf, refLineNumberOf, isUndoMarker)
    val ascending = folded.sortedBy { lineNumberOf(it) }
    val allVisits = visits(ascending, lineNumberOf, noteOf, isLocationMarker)
    val current = currentSegmentVisits(allVisits, targets)
    return current.flatMap { it.rows }.sortedByDescending { lineNumberOf(it) }
}
