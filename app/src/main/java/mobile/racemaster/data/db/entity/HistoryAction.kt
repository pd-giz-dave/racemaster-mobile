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

    // A runner passing a checkpoint — the CPn equivalent of FINISH (see
    // BibValidation.ACCOUNTED_FOR_ACTIONS/BIB_REQUIRED_ACTIONS below, where it's treated
    // identically to FINISH for dup-detection/outstanding-bib purposes). Originally CP Mode's
    // own action; now also Bibs Mode's own default at a CPn location (TODO.md: "A
    // rationalisation" — Bibs is location-aware). RETIRE has no CP-specific variant — a
    // retirement means the same thing regardless of which station recorded it.
    PASS,

    // Shared — see this file's own doc for why START/RESET/UNDO are safe to share. STOP was
    // removed (see git history) — its own "operator paused recording" state turned out to
    // carry no semantic weight nothing else already provided (Undo's own root-action branch for
    // it, and every RaceEntity.*StoppedAtMillis-driven UI read, were removed in the same change):
    // pressing Start again on an already-started race already resumes in place with zero rows
    // written (see TimeModeViewModel.startStopwatch/BibsModeViewModel.startBibsMode's own
    // already-started guard), so a Stop marker's only remaining value was "a polite goodbye" —
    // real liveness is now covered instead by a periodic PING (see that action's own doc).
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

    // Close Mode only — one beacon's post-race battery read + power-off. bibNumber is the
    // resolved bib if known at the time (nullable — a beacon can be closed before its bib
    // pairing is known), note carries "beacon=<n> battery=<pct>" (see CloseModeRepository's own
    // doc). Deliberately NOT deduplicated/upserted: a beacon re-presented after already being
    // closed (TODO.md/docs/beacon-detection-plan.md's Close mode checklist) just gets a second
    // CLOSE row — the same append-only, lineNumber-keyed storage every other action already
    // uses, so "closed ×N" is simply "how many CLOSE rows this race name this beacon."
    CLOSE,

    // Close Mode only — written once a beacon already marked closed (its most recent CLOSE-mode
    // row is a CLOSE) is heard advertising again — i.e. it's been powered back on and therefore
    // needs closing again. note carries "beacon=<n>" (see CloseModeRepository's own
    // recordReopenedIfClosed, which checks the beacon's latest CLOSE-mode row fresh inside its own
    // write transaction before writing this, the same race-avoidance CountModeRepository.
    // recordCount's own doc explains). A beacon's current closed/outstanding status is always
    // "whichever of CLOSE/CLOSE_REOPENED is its most recent row by lineNumber" — never "has it
    // ever had a CLOSE row" — which is also how the web app's own progress.json computation must
    // read it (see docs/beacon-detection-plan.md and the mirrored server-side change).
    CLOSE_REOPENED,

    // Count Mode only — a beacon seen at close range, before the start, for the pre-race
    // headcount (docs/beacon-detection-plan.md's Count mode). bibNumber is the resolved bib if
    // known (nullable, same reasoning as CLOSE), note carries "beacon=<n> seen=<n> of=<m>" — the
    // running/expected tally at the moment this beacon was seen, written once by
    // CountModeRepository.recordCount (counted fresh inside its own write transaction, not
    // computed by the ViewModel from its own reactive cache — see that function's own doc for
    // the race this avoids) rather than re-derived by whatever later displays this row. A beacon
    // only ever gets a second COUNT_SEEN row if it was ejected (COUNT_EJECTED, below) and later
    // let back in — never otherwise, since CountModeViewModel's own already-seen check gates
    // every other call.
    COUNT_SEEN,

    // Count Mode only — TODO.md's own "a means to eject one seen beacon" (a runner goes away and
    // comes back later having forgotten their kit, or the operator needs to un-count one entered
    // by mistake). refLineNumber points at the beacon's own COUNT_SEEN row, the exact same
    // append-only "undo marker" shape BIBS/TIME's own UNDO uses (see
    // EntryLogModeEngine.undoMostRecent's own doc) — CountModeRepository's own currentSegment
    // treats this action as an undo-marker too, so the targeted COUNT_SEEN row's whole fold group
    // (see HistoryFold.foldLatestVisible's own doc) drops out of the live counted-set entirely,
    // the beacon reverting to outstanding. note still carries a bare "beacon=<n>" (the same form
    // COUNT_SEEN's own note starts with) purely so Race History/the UI can read the beacon number
    // straight off this row without resolving the ref first; bibNumber is copied from the root
    // row for the same display convenience, same as UNDO's own bibNumber copy. Unlike UNDO, this
    // isn't reversible by Undo itself — COUNT_UNEJECTED (below) is Count's own explicit, operator-
    // driven way back, not a second eject-of-the-eject.
    COUNT_EJECTED,

    // Count Mode only — the operator's own explicit "let this one back in" after an eject,
    // written by CountModeViewModel.unejectBeacon when the operator taps the ejected beacon's own
    // link in the outstanding list (TODO.md: it "should un-eject it so it becomes a normal to-be-
    // seen beacon but should remain tagged 'was-ejected' until its re-seen"). Deliberately NOT
    // another undo-marker/refLineNumber row — a COUNT_EJECTED row's own exclusion from the live
    // counted set (see that action's own doc) already makes the beacon outstanding again the
    // moment it's ejected; this row's only job is to flip CountModeRepository.statusByBeacon's own
    // read of "which happened most recently for this beacon" from EJECTED to WAS_EJECTED (a purely
    // informational tag — commitDetection's own already-seen/already-ejected gate treats WAS_EJECTED
    // exactly like "never seen": a fresh COUNT_SEEN row for this beacon, once detected again,
    // supersedes it the normal way). note carries the same bare "beacon=<n>" form.
    COUNT_UNEJECTED,

    // Count Mode only — written once, immediately after the COUNT_SEEN row for whichever beacon
    // brings the running count up to the expected total (see CountModeRepository.recordCount,
    // same transaction, next line number). note carries "of=<m>", the same expected total that
    // row's own COUNT_SEEN siblings carry. Never written if no beacons were ever issued for this
    // race (an expected total of zero can't "complete").
    COUNT_COMPLETE,

    // Count Mode only — written once if Stop (CountModeViewModel.stopMode) is pressed while at
    // least one issued beacon hasn't been seen yet; note carries "outstanding=<n>". Never written
    // if every issued beacon had already been seen (COUNT_COMPLETE already said so) — Stop after
    // a genuine completion needs no second marker.
    COUNT_STOPPED,
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
