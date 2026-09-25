package mobile.racemaster.data.db.entity

// Unified per-line action for bibs mode and time mode.
//
// START/RESET/UNDO are shared verbatim between the two families rather than each having
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

    // Shared — see this file's own doc for why START/RESET/UNDO are safe to share. STOP was
    // removed (see git history) — its own "operator paused recording" state turned out to
    // carry no semantic weight nothing else already provided (Undo's own root-action branch for
    // it, and every RaceEntity.*StoppedAtMillis-driven UI read, were removed in the same change):
    // pressing Start again on an already-started race already resumes in place with zero rows
    // written (see TimeModeRepository.resumeStopwatch/EntryLogModeEngine.resume), so a Stop
    // marker's only remaining value was "a polite goodbye" — real liveness is now covered
    // instead by a periodic PING (see that action's own doc).
    START, RESET, UNDO,

    // Shared — written once by RaceRepository.recordModeStart (Setup Race / Relocate), the
    // moment a mode is chosen, immediately after its own LOCATION marker (see that action's own
    // doc) — a boundary flag for the racemaster web app to recognise "a mode began here" once
    // this race's history file reaches it, not something the operator is meant to see or
    // interact with, so it's excluded from every live current-segment view/undo target (see
    // EntryLogModeEngine/TimeModeRepository's own current-segment queries) and only ever shows up
    // in Race History's full chronology. States its mode explicitly in `note` (e.g. "Time"/
    // "Bibs"/"CP" — see AppMode.wireName()) rather than being inferred from bibNumber/splitTime
    // nullness (see SyncRecord's own doc for why neither field means anything on this row any
    // more); bibNumber and splitNumber are always null here, regardless of mode.
    MODE_START,

    // Written once whenever the operator sets up or relocates the race ("Setup Race"/Relocate
    // screen, see RaceRepository.recordModeStart) — always mode-scoped, always immediately
    // followed by that same call's own MODE_START row. Carries the new location in `note`.
    // Deliberately NOT filtered out of the live current-segment view — it must stay visible and
    // undoable (an operator relocating by mistake needs to be able to undo it). Every LOCATION
    // row starts one "segment" (location+mode) — see HistoryFold's own doc for how the current
    // segment (and RESET's own walk-back-by-segment behavior) is computed from these, including
    // how relocating back to an earlier, not-yet-reset location resumes that location's own
    // segment instead of starting fresh. HistoryLineEntity's own priorSplitCounter/
    // previousLocation/previousMode columns exist solely to let undoMostRecent restore this
    // marker's mode-level split counter and RaceEntity.location/mode exactly, since simply not
    // decrementing (the way START is handled) isn't enough — the counter was reset (or resumed
    // to some other value) by this marker's own forward write, not incremented.
    LOCATION,

    // A periodic heartbeat, written only when nothing else has happened for a mode in a while
    // (see the new ping-interval setting and its own scheduling loop in MuleSyncEngine) — lets
    // the web app tell a phone is still alive and well even when nobody's actively recording
    // anything. Carries no bib/split/note, consumes a real line number like every other row, but
    // is excluded from every mode-screen list/computation the same way MODE_START/NEW_RACE/RESET
    // already are — shows only in Race History's own full chronology. Deliberately unrelated to,
    // and easily confused with, the pre-existing PingOutcome/MuleSyncClient.ping() (a plain HTTP
    // server-reachability probe) — the name collision is coincidental, not a shared concept.
    PING,

    // Written once, always as a brand-new race's very own first history line (before even its
    // first LOCATION/MODE_START pair — see RaceRepository.recordModeStart, gated on
    // `race.mode == null`, the same "is this the race's genuine first-ever call" signal already
    // used for previousMode) — never on a later Relocate. A pure invalidation signal for every
    // recipient of this device's history stream (the server, a Mule's own pull cache, the web
    // app if it received it directly offline): "discard whatever you already hold for this exact
    // race+device identity before applying what follows" — since a brand-new local race reusing
    // an already-used label/device combo means any previously-synced content elsewhere is from a
    // different, since-superseded race, not this one, and must never be merged with it. Like
    // MODE_START, never operator-interactive — excluded from every live current-segment
    // view/undo target the same way (see TimeModeRepository/EntryLogModeEngine's own
    // undoMostRecent filtering) and only ever shown in Race History's full chronology.
    NEW_RACE,
}

/** A NEW_RACE row with this note is a deletion tombstone: the race was deleted on this device,
 *  and its whole history replaced by this one row so every recipient (server, mules, web app)
 *  drops its copy — see RaceRepository.requestDeleteRace. Carried on the wire as-is in `note`. */
const val DELETED_RACE_NOTE = "Deleted"

/** Actions that carry a real bib number and participate in expected/duplicate checks. */
val BIB_REQUIRED_ACTIONS = setOf(HistoryAction.START, HistoryAction.FINISH, HistoryAction.RETIRE, HistoryAction.PASS)

/** Boundary/marker rows that a Bibs/CP "N so far" running-tally count (see
 *  [mobile.racemaster.util.formatBibsSoFarText]/[mobile.racemaster.util.formatCpSoFarText]) must
 *  exclude — CLOCK is the fixed Start marker, LOCATION is the only marker that can appear in the
 *  live current-segment view alongside genuine entries (see HistoryAction.LOCATION's own doc:
 *  it's deliberately never excluded from that view so it stays undoable), neither of which
 *  represents an operator-recorded bib/CP crossing. RESET/PING/UNDO/MODE_START/NEW_RACE are never
 *  reachable in that view in the first place (all filtered out before the view is built — see
 *  HistoryFold's own doc), so they're omitted here rather than listed defensively. */
val NON_ENTRY_ACTIONS = setOf(HistoryAction.CLOCK, HistoryAction.LOCATION)
