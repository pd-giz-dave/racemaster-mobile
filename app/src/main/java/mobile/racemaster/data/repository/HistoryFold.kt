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
