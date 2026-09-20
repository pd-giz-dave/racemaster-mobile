package mobile.racemaster.data.repository

/**
 * Reconstructs "what's currently visible" from a raw, append-only current-segment row list
 * where Undo/Edit no longer delete/mutate but instead append a new row (see
 * HistoryLineEntity's `refLineNumber` doc). Groups rows by their logical
 * identity (`refLineNumberOf(it) ?: lineNumberOf(it)` — the original ROOT row's lineNumber),
 * keeps only the highest-`lineNumber` row per group (the latest echo, or the original if it
 * was never edited), drops any group whose latest row is an undo-marker, and sorts by the
 * ROOT key rather than the latest row's own (possibly much later) lineNumber — so editing an
 * entry updates its displayed content without moving its position in the newest-first list.
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
 * Slices [rows] (already RESET-bounded — the live current-segment query's own boundary) down to
 * "since the last relocation, inclusive" — a bib/split recorded at one station must never be
 * treated as a duplicate of, or already-accounted-for against, the same bib/split number at a
 * station the operator has since moved to (see HistoryAction.LOCATION's own doc). This is a
 * genuinely separate, additional boundary from RESET: unlike RESET, a LOCATION marker is
 * deliberately NOT baked into observeCurrentSegment/getCurrentSegmentSnapshot's own SQL boundary,
 * specifically so it stays reachable to Undo (see this repository's own undoMostRecent) — this
 * function is what gives duplicate-detection/bib-accounting the narrower view they need without
 * taking away Undo's visibility of it.
 *
 * Inclusive of the marker row itself (`>=`, not RESET's own strict `>`) — the marker must stay in
 * the returned list so it remains visible/undoable in the live entry list, the same slot a
 * Clock/Start marker already occupies at the bottom of a fresh segment. No LOCATION row present
 * at all is a no-op — returns [rows] unchanged.
 */
fun <T> sinceLastLocationMarker(
    rows: List<T>,
    lineNumberOf: (T) -> Long,
    isLocationMarker: (T) -> Boolean,
): List<T> {
    val boundary = rows.filter(isLocationMarker).maxOfOrNull { lineNumberOf(it) } ?: return rows
    return rows.filter { lineNumberOf(it) >= boundary }
}
