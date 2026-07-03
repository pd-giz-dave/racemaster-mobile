package mobile.racemaster.ui.bibsmode

import mobile.racemaster.data.db.entity.BibEntryType

fun BibEntryType.displayName(): String = when (this) {
    BibEntryType.START -> "Start"
    BibEntryType.FINISH -> "Finish"
    BibEntryType.RETIRE -> "Retire"
    BibEntryType.IGNORE -> "Ignore"
    BibEntryType.SENIORS -> "Seniors"
    BibEntryType.JUNIORS -> "Juniors"
    BibEntryType.MALE -> "Male"
    BibEntryType.FEMALE -> "Female"
    BibEntryType.CLOCK -> "Clock"
    BibEntryType.STOP -> "Stop"
}

// Deliberately excludes CLOCK and STOP: Clock only ever exists once, as the fixed
// auto-inserted split 0 (re-selecting it ad hoc doesn't fit that model, and none of its
// web-app time semantics are consumed anywhere in this codebase yet); Stop is only ever
// inserted by the dedicated Stop button, never operator-selectable here.
val EVENT_PICKER_OPTIONS: List<BibEntryType> = listOf(
    BibEntryType.FINISH,
    BibEntryType.START,
    BibEntryType.RETIRE,
    BibEntryType.IGNORE,
    BibEntryType.SENIORS,
    BibEntryType.JUNIORS,
    BibEntryType.MALE,
    BibEntryType.FEMALE,
)
