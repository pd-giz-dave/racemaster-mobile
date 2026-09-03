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
    HistoryAction.STOP -> "Stop"
    HistoryAction.RESET -> "Reset"
    HistoryAction.UNDO -> "Undo"
    // Race History is the only place this is ever shown (see HistoryAction.MODE_START's own
    // doc) — same "Start" text its own real Start/Clock marker already shows there, just on
    // its own separate boundary-marker row.
    HistoryAction.MODE_START -> "Start"
}

// Deliberately excludes every marker action (CLOCK/STOP/RESET/UNDO, plus the Time-only SPLIT,
// none of which are ever relevant to this Bibs-only picker): Clock only ever exists once, as
// the fixed auto-inserted split 0 (re-selecting it ad hoc doesn't fit that model, and none of
// its web-app time semantics are consumed anywhere in this codebase yet); Stop is only ever
// inserted by the dedicated Stop button, never operator-selectable here; Reset is only ever
// inserted by BibsModeRepository.resetBibsMode as a fixed history boundary marker, same as
// Stop; Undo is only ever inserted by BibsModeRepository.undoMostRecent as a fixed history
// marker, never operator-selectable either.
val EVENT_PICKER_OPTIONS: List<HistoryAction> = listOf(
    HistoryAction.FINISH,
    HistoryAction.START,
    HistoryAction.RETIRE,
    HistoryAction.IGNORE,
    HistoryAction.SENIORS,
    HistoryAction.JUNIORS,
    HistoryAction.MALE,
    HistoryAction.FEMALE,
)

// CP Mode's own, much smaller picker — offered only when editing an existing entry (its main
// entry flow has PASS/RETIRE as fixed buttons, no picker at all — see CpModeScreen).
val CP_ACTION_OPTIONS: List<HistoryAction> = listOf(HistoryAction.PASS, HistoryAction.RETIRE)

// Bibs Mode's Event button offers this narrower list instead of the full EVENT_PICKER_OPTIONS
// whenever there's no just-auto-saved entry to retag (see BibsModeViewModel.canRetagFlow) —
// none of these carry a bib, so they're the only types that still make sense to log as a
// standalone entry with nothing typed in the keypad.
val BIBS_STANDALONE_OPTIONS: List<HistoryAction> = EVENT_PICKER_OPTIONS.filterNot { it in BIB_REQUIRED_ACTIONS }
