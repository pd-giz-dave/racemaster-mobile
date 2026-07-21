package mobile.racemaster.ui.bibsmode

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
    HistoryAction.STOP -> "Stop"
    HistoryAction.RESET -> "Reset"
    HistoryAction.UNDO -> "Undo"
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
