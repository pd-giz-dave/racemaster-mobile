package mobile.racemaster.ui.bibsmode

import mobile.racemaster.data.db.entity.BIB_REQUIRED_ACTIONS
import mobile.racemaster.data.db.entity.HistoryAction

fun HistoryAction.displayName(): String = when (this) {
    HistoryAction.SPLIT -> "Split"
    HistoryAction.START -> "Start"
    HistoryAction.FINISH -> "Finish"
    HistoryAction.RETIRE -> "Retire"
    HistoryAction.IGNORE -> "Ignore"
    HistoryAction.SENIORS -> "Seniors"
    HistoryAction.JUNIORS -> "Juniors"
    HistoryAction.MALE -> "Male"
    HistoryAction.FEMALE -> "Female"
    HistoryAction.CLOCK -> "Clock"
    HistoryAction.PASS -> "Pass"
    HistoryAction.RESET -> "Reset"
    HistoryAction.UNDO -> "Undo"
    // Race History's full chronology is the only place this is ever shown (see
    // HistoryAction.PING's own doc) — a pure heartbeat, never operator-interactive.
    HistoryAction.PING -> "Ping"
    // Race History is the only place this is ever shown (see HistoryAction.MODE_START's own
    // doc) — same "Start" text its own real Start/Clock marker already shows there, just on
    // its own separate boundary-marker row.
    HistoryAction.MODE_START -> "Start"
    // Unlike MODE_START, this one IS shown on the live screen too (see
    // HistoryAction.LOCATION's own doc — it must stay visible to be undoable). Covers both a
    // race's initial location (set at Setup Race time) and a later mid-race relocation with one
    // neutral word — no need to tell them apart here, since the actual destination is always
    // carried in `note` and rendered alongside this label the same way any other row's note
    // already is.
    HistoryAction.LOCATION -> "Location"
    // Race History is the only place this is ever shown (see HistoryAction.NEW_RACE's own doc)
    // — a pure invalidation marker for other devices/the server, never operator-interactive.
    HistoryAction.NEW_RACE -> "New Race"
}

// Deliberately excludes every marker action (CLOCK/RESET/UNDO/PING, plus the Time-only SPLIT,
// none of which are ever relevant to this Bibs-only picker): Clock only ever exists once, as
// the fixed auto-inserted split 0 (re-selecting it ad hoc doesn't fit that model, and none of
// its web-app time semantics are consumed anywhere in this codebase yet); Reset is only ever
// inserted by BibsModeRepository.resetBibsMode as a fixed history boundary marker; Undo is only
// ever inserted by BibsModeRepository.undoMostRecent as a fixed history marker; Ping is only
// ever inserted by the background heartbeat loop — none are ever operator-selectable here. Pass
// is included since Bibs is location-aware (TODO.md: "A rationalisation") and can record at a
// CPn location. This is the full candidate list — BibsModeViewModel.uiState further narrows
// whatever subset of it (and of BIBS_STANDALONE_OPTIONS below) it actually offers at any given
// moment down to what data/repository/LocationValidation.kt's isBibsActionAllowedAtLocation
// permits at the race's current location (e.g. Pass/Retire/Ignore only at a CP, never Pass at
// Start or Finish).
val EVENT_PICKER_OPTIONS: List<HistoryAction> = listOf(
    HistoryAction.FINISH,
    HistoryAction.START,
    HistoryAction.PASS,
    HistoryAction.RETIRE,
    HistoryAction.IGNORE,
    HistoryAction.SENIORS,
    HistoryAction.JUNIORS,
    HistoryAction.MALE,
    HistoryAction.FEMALE,
)

// Bibs Mode's Event button offers this narrower list instead of the full EVENT_PICKER_OPTIONS
// whenever there's no just-auto-saved entry to retag (see BibsModeViewModel.canRetagFlow) —
// none of these carry a bib, so they're the only types that still make sense to log as a
// standalone entry with nothing typed in the keypad.
val BIBS_STANDALONE_OPTIONS: List<HistoryAction> = EVENT_PICKER_OPTIONS.filterNot { it in BIB_REQUIRED_ACTIONS }

// Bibs Mode's own compact default-action selector (see BibsModeScreen's DefaultBibsActionSelector)
// — the three common cases an operator pre-selects for a whole segment (e.g. a handicap race's
// staggered starts, or a CPn station). Pass joins Start/Finish now that Bibs is location-aware
// (TODO.md: "A rationalisation") — RaceRepository.recordModeStart seeds this selector from
// location automatically. This is the full candidate list, not what's actually shown: like
// EVENT_PICKER_OPTIONS above, BibsModeUiState.defaultActionOptions narrows it by
// isBibsActionAllowedAtLocation for the race's current location first — just [PASS] at a CP,
// [START, FINISH] (no Pass) at Start or Finish — so the operator can only ever land on a choice
// that's valid for where they actually are. Retire is deliberately left out of this list
// entirely: it's an occasional, one-off correction for a single bib, never something you'd want
// every subsequent 3-digit entry to auto-save as — still reachable via Event the same as any
// other correction.
val BIBS_DEFAULT_ACTION_OPTIONS: List<HistoryAction> = listOf(
    HistoryAction.START,
    HistoryAction.PASS,
    HistoryAction.FINISH,
)
