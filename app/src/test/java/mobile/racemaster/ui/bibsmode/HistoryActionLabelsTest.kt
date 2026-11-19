package mobile.racemaster.ui.bibsmode

import mobile.racemaster.data.db.entity.HistoryAction
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryActionLabelsTest {

    @Test
    fun everyActionHasADisplayName() {
        assertEquals("Split", HistoryAction.SPLIT.displayName())
        assertEquals("Start", HistoryAction.START.displayName())
        assertEquals("Finish", HistoryAction.FINISH.displayName())
        assertEquals("Retire", HistoryAction.RETIRE.displayName())
        assertEquals("Ignore", HistoryAction.IGNORE.displayName())
        assertEquals("Seniors", HistoryAction.SENIORS.displayName())
        assertEquals("Juniors", HistoryAction.JUNIORS.displayName())
        assertEquals("Male", HistoryAction.MALE.displayName())
        assertEquals("Female", HistoryAction.FEMALE.displayName())
        assertEquals("Clock", HistoryAction.CLOCK.displayName())
        assertEquals("Pass", HistoryAction.PASS.displayName())
        assertEquals("Reset", HistoryAction.RESET.displayName())
        assertEquals("Undo", HistoryAction.UNDO.displayName())
        assertEquals("Start", HistoryAction.MODE_START.displayName())
        assertEquals("Location", HistoryAction.LOCATION.displayName())
        assertEquals("New Race", HistoryAction.NEW_RACE.displayName())
        assertEquals("Ping", HistoryAction.PING.displayName())
        assertEquals("Close", HistoryAction.CLOSE.displayName())
        assertEquals("Reopened", HistoryAction.CLOSE_REOPENED.displayName())
        // "Seen"/"Seen all", not "Count Seen"/"Count Complete" (direct feedback) — the row's own
        // note line already carries the "seen=<n> of=<m>"/"of=<m>" tally, so repeating it in the
        // label was redundant.
        assertEquals("Seen", HistoryAction.COUNT_SEEN.displayName())
        assertEquals("Seen all", HistoryAction.COUNT_COMPLETE.displayName())
        assertEquals("Count Stopped", HistoryAction.COUNT_STOPPED.displayName())
    }

    @Test
    fun countCompleteDisplayNoteIsHumanReadable() {
        assertEquals("All 10 beacons seen", HistoryAction.COUNT_COMPLETE.displayNote("of=10"))
        // Falls back to the raw note if it doesn't parse.
        assertEquals("garbage", HistoryAction.COUNT_COMPLETE.displayNote("garbage"))
    }

    @Test
    fun countStoppedDisplayNoteIsHumanReadableAndPluralizes() {
        assertEquals("1 beacon outstanding", HistoryAction.COUNT_STOPPED.displayNote("outstanding=1"))
        assertEquals("3 beacons outstanding", HistoryAction.COUNT_STOPPED.displayNote("outstanding=3"))
        assertEquals("garbage", HistoryAction.COUNT_STOPPED.displayNote("garbage"))
    }

    @Test
    fun otherActionsDisplayNoteIsJustTheRawNote() {
        assertEquals("a note", HistoryAction.FINISH.displayNote("a note"))
        assertEquals(null, HistoryAction.FINISH.displayNote(null))
    }

    @Test
    fun eventPickerOptionsExcludeMarkerOnlyActions() {
        assertEquals(
            setOf(
                HistoryAction.FINISH, HistoryAction.START, HistoryAction.PASS, HistoryAction.RETIRE, HistoryAction.IGNORE,
                HistoryAction.SENIORS, HistoryAction.JUNIORS, HistoryAction.MALE, HistoryAction.FEMALE,
            ),
            EVENT_PICKER_OPTIONS.toSet(),
        )
        // Bibs-mode markers.
        assertEquals(false, HistoryAction.CLOCK in EVENT_PICKER_OPTIONS)
        assertEquals(false, HistoryAction.RESET in EVENT_PICKER_OPTIONS)
        assertEquals(false, HistoryAction.UNDO in EVENT_PICKER_OPTIONS)
        assertEquals(false, HistoryAction.PING in EVENT_PICKER_OPTIONS)
        // The Time-only action never belongs on this Bibs-only picker either.
        assertEquals(false, HistoryAction.SPLIT in EVENT_PICKER_OPTIONS)
    }

    @Test
    fun standaloneOptionsAreEventPickerOptionsMinusBibRequiredActions() {
        assertEquals(
            setOf(HistoryAction.IGNORE, HistoryAction.SENIORS, HistoryAction.JUNIORS, HistoryAction.MALE, HistoryAction.FEMALE),
            BIBS_STANDALONE_OPTIONS.toSet(),
        )
        // Finish/Start/Pass/Retire always carry a bib, so they're never offered when there's
        // nothing typed to log a standalone entry against.
        assertEquals(false, HistoryAction.FINISH in BIBS_STANDALONE_OPTIONS)
        assertEquals(false, HistoryAction.START in BIBS_STANDALONE_OPTIONS)
        assertEquals(false, HistoryAction.PASS in BIBS_STANDALONE_OPTIONS)
        assertEquals(false, HistoryAction.RETIRE in BIBS_STANDALONE_OPTIONS)
    }

    @Test
    fun defaultActionOptionsAreStartPassThenFinish() {
        assertEquals(
            listOf(HistoryAction.START, HistoryAction.PASS, HistoryAction.FINISH),
            BIBS_DEFAULT_ACTION_OPTIONS,
        )
    }
}
