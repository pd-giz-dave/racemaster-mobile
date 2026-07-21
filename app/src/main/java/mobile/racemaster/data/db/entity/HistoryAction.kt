package mobile.racemaster.data.db.entity

// Unified per-line action, replacing Time Mode's old note-based Start/Stop/Reset/Undo
// convention (FinishSplitEntity had no typed column at all — those markers were reserved
// strings written into the same `note` column used for genuine free-text operator notes) and
// Bibs Mode's own BibEntryType.
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

    // Shared — see this file's own doc for why START/STOP/RESET/UNDO are safe to share.
    START, STOP, RESET, UNDO,
}

/** Actions that carry a real bib number and participate in range/duplicate checks. */
val BIB_REQUIRED_ACTIONS = setOf(HistoryAction.START, HistoryAction.FINISH, HistoryAction.RETIRE)
