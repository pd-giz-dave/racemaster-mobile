package mobile.racemaster.ui.bibsmode

import mobile.racemaster.data.db.entity.BibEntryType
import org.junit.Assert.assertEquals
import org.junit.Test

class BibEntryTypeLabelsTest {

    @Test
    fun everyTypeHasADisplayName() {
        assertEquals("Start", BibEntryType.START.displayName())
        assertEquals("Finish", BibEntryType.FINISH.displayName())
        assertEquals("Retire", BibEntryType.RETIRE.displayName())
        assertEquals("Ignore", BibEntryType.IGNORE.displayName())
        assertEquals("Seniors", BibEntryType.SENIORS.displayName())
        assertEquals("Juniors", BibEntryType.JUNIORS.displayName())
        assertEquals("Male", BibEntryType.MALE.displayName())
        assertEquals("Female", BibEntryType.FEMALE.displayName())
        assertEquals("Clock", BibEntryType.CLOCK.displayName())
        assertEquals("Stop", BibEntryType.STOP.displayName())
        assertEquals("Reset", BibEntryType.RESET.displayName())
        assertEquals("Undo", BibEntryType.UNDO.displayName())
    }

    @Test
    fun eventPickerOptionsExcludeMarkerOnlyTypes() {
        assertEquals(
            setOf(
                BibEntryType.FINISH, BibEntryType.START, BibEntryType.RETIRE, BibEntryType.IGNORE,
                BibEntryType.SENIORS, BibEntryType.JUNIORS, BibEntryType.MALE, BibEntryType.FEMALE,
            ),
            EVENT_PICKER_OPTIONS.toSet(),
        )
        assertEquals(false, BibEntryType.CLOCK in EVENT_PICKER_OPTIONS)
        assertEquals(false, BibEntryType.STOP in EVENT_PICKER_OPTIONS)
        assertEquals(false, BibEntryType.RESET in EVENT_PICKER_OPTIONS)
        assertEquals(false, BibEntryType.UNDO in EVENT_PICKER_OPTIONS)
    }
}
