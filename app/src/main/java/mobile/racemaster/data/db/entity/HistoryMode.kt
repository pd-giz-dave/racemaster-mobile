package mobile.racemaster.data.db.entity

// Which family of history a HistoryLineEntity row belongs to — stamped once at insert by
// whichever thin repository writes it (TimeModeRepository always writes TIME, BibsModeRepository
// always writes BIBS, CpModeRepository always writes CP). Needed as a separate column rather
// than deriving it from `action` alone because HistoryAction.UNDO (and START/STOP/RESET) are
// deliberately shared values across every family — a query scoped to "just this mode's rows"
// (including its own UNDO markers, excluding every other family's) must filter on `mode`, not
// `action`. CP is otherwise structured just like BIBS (its own current-segment/undo-stack/
// counters, independent of a Bibs device recording the same race from a different station) —
// see EntryLogModeEngine, which both BIBS and CP repositories are built on.
enum class HistoryMode { TIME, BIBS, CP }

// Every history line is now shown in one rationalized column format regardless of mode — a
// line no longer needs a mode-prefixed label (the old "B003"/"T012") to identify it, since
// which of a row's bib/time columns is populated already says which mode it belongs to. In
// its place: a fixed-width, zero-padded "L"/"S" column pair (permanent line number, and the
// line's own per-segment split number) so a list of lines lines up for scanning, plus a
// plain, unpadded "L"/"S" form for inline prose that points at another line ("dup of S1",
// "Undo L5", "Edited from L5") — a zero-padded reference would read oddly mid-sentence.

/** Column form — always 3+ digits, overflowing rather than truncating past 999 (e.g. "L1000"). */
fun formatLineColumn(lineNumber: Long): String = "L${lineNumber.toString().padStart(3, '0')}"

/** Column form — always 3+ digits, overflowing rather than truncating past 999 (e.g. "S1000"). */
fun formatSplitColumn(splitNumber: Int): String = "S${splitNumber.toString().padStart(3, '0')}"

/** Inline-prose form — a plain, unpadded number ("L5", not "L005"). */
fun formatLineRef(lineNumber: Long): String = "L$lineNumber"

/** Inline-prose form — a plain, unpadded number ("S1", not "S001"). */
fun formatSplitRef(splitNumber: Int): String = "S$splitNumber"
