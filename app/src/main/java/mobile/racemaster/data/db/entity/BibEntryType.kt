package mobile.racemaster.data.db.entity

enum class BibEntryType { START, FINISH, RETIRE, IGNORE, SENIORS, JUNIORS, MALE, FEMALE, CLOCK, STOP }

/** Types that carry a real bib number and participate in range/duplicate checks. */
val BIB_REQUIRED_TYPES = setOf(BibEntryType.START, BibEntryType.FINISH, BibEntryType.RETIRE)
