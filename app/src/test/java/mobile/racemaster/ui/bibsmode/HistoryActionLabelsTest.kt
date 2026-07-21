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
        assertEquals("Stop", HistoryAction.STOP.displayName())
        assertEquals("Reset", HistoryAction.RESET.displayName())
        assertEquals("Undo", HistoryAction.UNDO.displayName())
    }

    @Test
    fun eventPickerOptionsExcludeMarkerOnlyActionsForBothModes() {
        assertEquals(
            setOf(
                HistoryAction.FINISH, HistoryAction.START, HistoryAction.RETIRE, HistoryAction.IGNORE,
                HistoryAction.SENIORS, HistoryAction.JUNIORS, HistoryAction.MALE, HistoryAction.FEMALE,
            ),
            EVENT_PICKER_OPTIONS.toSet(),
        )
        // Bibs-mode markers.
        assertEquals(false, HistoryAction.CLOCK in EVENT_PICKER_OPTIONS)
        assertEquals(false, HistoryAction.STOP in EVENT_PICKER_OPTIONS)
        assertEquals(false, HistoryAction.RESET in EVENT_PICKER_OPTIONS)
        assertEquals(false, HistoryAction.UNDO in EVENT_PICKER_OPTIONS)
        // The Time-only action never belongs on this Bibs-only picker either.
        assertEquals(false, HistoryAction.SPLIT in EVENT_PICKER_OPTIONS)
    }
}
