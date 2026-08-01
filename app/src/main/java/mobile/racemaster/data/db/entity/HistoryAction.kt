package mobile.racemaster.data.db.entity

// Unified per-line action for bibs mode and time mode.
//
// START/STOP/RESET/UNDO are shared verbatim between the two families rather than each having
// its own Time-prefixed variant (the old CLOCK_START/CLOCK_STOP/CLOCK_RESET) — every consumer
// that branches on one of these already only ever sees rows already scoped to one family first
// (either a DAO query parameterized with `mode`, or an explicit `mode ==` check upstream — see
// HistoryMode's own doc for why `mode`, not `action`, is what a per-family query filters on),
// so there's no shared `when(action)` branch that could apply one family's race-state side
// effects to the other's row. SPLIT and CLOCK stay their own distinct values since they have no
// real Bibs/Time counterpart to share with.
enum class HistoryAction {
    // Time Mode only
    SPLIT, // an ordinary timing split — every non-marker row was previously untyped

    // Bibs Mode only — kept verbatim from the old BibEntryType (already established, already
    // mapped 1:1 to wire `action` strings via toServerAction()).
    FINISH, RETIRE, IGNORE, SENIORS, JUNIORS, MALE, FEMALE, CLOCK,

    // CP Mode only — a runner passing a checkpoint, the CP equivalent of Bibs' FINISH (see
    // BibValidation.ACCOUNTED_FOR_ACTIONS/BIB_REQUIRED_ACTIONS below, where it's treated
    // identically to FINISH for dup-detection/outstanding-bib purposes). RETIRE is shared with
    // Bibs Mode instead of getting its own CP variant — a retirement means the same thing
    // regardless of which station recorded it.
    PASS,

    // Shared — see this file's own doc for why START/STOP/RESET/UNDO are safe to share.
    START, STOP, RESET, UNDO,

    // Shared — written once by every mode's own start (TimeModeRepository.startStopwatch/
    // BibsModeRepository.startBibsMode/CpModeRepository.startCpMode), immediately before that
    // mode's own real Start/Clock marker, which stays completely unchanged. A separate,
    // dedicated marker rather than reusing START/CLOCK: it's purely a boundary flag for the
    // racemaster web app to later recognise "a mode began here" once this race's history file
    // reaches it, not something the operator is meant to see or interact with — so it's excluded
    // from every live current-segment view/undo target (see EntryLogModeEngine/
    // TimeModeRepository's own current-segment queries) and only ever shows up in Race History's
    // full chronology.
    MODE_START,
}

/** Actions that carry a real bib number and participate in range/duplicate checks. */
val BIB_REQUIRED_ACTIONS = setOf(HistoryAction.START, HistoryAction.FINISH, HistoryAction.RETIRE, HistoryAction.PASS)
