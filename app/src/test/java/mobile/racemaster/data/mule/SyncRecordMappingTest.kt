package mobile.racemaster.data.mule

import mobile.racemaster.data.db.entity.HistoryAction
import mobile.racemaster.data.db.entity.HistoryLineEntity
import mobile.racemaster.data.db.entity.HistoryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncRecordMappingTest {

    private fun line(
        mode: HistoryMode,
        action: HistoryAction,
        splitNumber: Int,
        timestampMillis: Long,
        bibNumber: Int? = null,
        note: String? = null,
        lineNumber: Long = 1L,
        refLineNumber: Long? = null,
    ) = HistoryLineEntity(
        id = 1L,
        raceId = 1L,
        mode = mode,
        action = action,
        bibNumber = bibNumber,
        splitNumber = splitNumber,
        lineNumber = lineNumber,
        refLineNumber = refLineNumber,
        note = note,
        timestampMillis = timestampMillis,
        recordUuid = "record-uuid",
    )

    private fun split(
        splitNumber: Int,
        timestampMillis: Long,
        note: String? = null,
        lineNumber: Long = 1L,
        refLineNumber: Long? = null,
    ) = line(HistoryMode.TIME, HistoryAction.SPLIT, splitNumber, timestampMillis, note = note, lineNumber = lineNumber, refLineNumber = refLineNumber)

    private fun bibEntry(
        bibNumber: Int?,
        action: HistoryAction,
        splitNumber: Int,
        timestampMillis: Long,
        note: String? = null,
        lineNumber: Long = 1L,
        refLineNumber: Long? = null,
    ) = line(HistoryMode.BIBS, action, splitNumber, timestampMillis, bibNumber = bibNumber, note = note, lineNumber = lineNumber, refLineNumber = refLineNumber)

    private fun cpEntry(
        bibNumber: Int?,
        action: HistoryAction,
        splitNumber: Int,
        timestampMillis: Long,
        note: String? = null,
        lineNumber: Long = 1L,
        refLineNumber: Long? = null,
    ) = line(HistoryMode.CP, action, splitNumber, timestampMillis, bibNumber = bibNumber, note = note, lineNumber = lineNumber, refLineNumber = refLineNumber)

    // Time-mode HistoryLineEntity.toSyncRecord

    @Test
    fun finishSplitMapsElapsedTimeRelativeToRaceStart() {
        val record = split(splitNumber = 3, timestampMillis = 90_000L).toSyncRecord(raceStartedAtMillis = 0L)
        assertEquals("00:01:30", record.splitTime)
        assertEquals(3, record.splitNumber)
        assertEquals(null, record.bibNumber)
        assertEquals("Split", record.action)
    }

    @Test
    fun finishSplitWithNullRaceStartFormatsAsZero() {
        val record = split(splitNumber = 1, timestampMillis = 12_345L).toSyncRecord(raceStartedAtMillis = null)
        assertEquals("00:00:00", record.splitTime)
    }

    @Test
    fun finishSplitRoundsDownBelowHalfASecond() {
        val record = split(splitNumber = 1, timestampMillis = 1_490L).toSyncRecord(raceStartedAtMillis = 0L)
        assertEquals("00:00:01", record.splitTime)
    }

    @Test
    fun finishSplitRoundsUpAtHalfASecondOrMore() {
        val record = split(splitNumber = 1, timestampMillis = 1_530L).toSyncRecord(raceStartedAtMillis = 0L)
        assertEquals("00:00:02", record.splitTime)
    }

    @Test
    fun finishSplitCarriesRecordUuidAndNoteThrough() {
        val record = split(splitNumber = 1, timestampMillis = 1_000L, note = "Checkpoint 1").toSyncRecord(0L)
        assertEquals("record-uuid", record.recordUuid)
        assertEquals("Checkpoint 1", record.note)
    }

    @Test
    fun finishSplitCarriesRawWallClockTimestampAlongsideElapsedTime() {
        // timestampMillis is the raw wall-clock time, independent of raceStartedAtMillis —
        // only `splitTime` is relative to race start.
        val record = split(splitNumber = 1, timestampMillis = 1_700_000_000_000L).toSyncRecord(raceStartedAtMillis = 999_000L)
        assertEquals(1_700_000_000_000L, record.timestampMillis)
    }

    @Test
    fun locationIsStampedOntoEveryRecordRegardlessOfMode() {
        // Constant for the whole race, repeated on every line — see SyncRecord's own doc for
        // why there's no separate once-per-race channel to send it through instead.
        val splitRecord = split(splitNumber = 1, timestampMillis = 0L).toSyncRecord(0L, location = "Checkpoint 2")
        assertEquals("Checkpoint 2", splitRecord.location)

        val bibRecord = bibEntry(101, HistoryAction.FINISH, splitNumber = 1, timestampMillis = 0L).toSyncRecord(null, location = "Start")
        assertEquals("Start", bibRecord.location)
    }

    @Test
    fun locationDefaultsToFinishWhenNotSpecified() {
        val record = split(splitNumber = 1, timestampMillis = 0L).toSyncRecord(0L)
        assertEquals("Finish", record.location)
    }

    @Test
    fun clockMarkersMapToTheirOwnHonestActionsNotHardcodedFinish() {
        // Previously every Time row hardcoded action = "Finish" regardless of whether it was
        // really a Start/Stop/Reset/Undo marker — the real type only ever reached `note`. Now
        // the wire action is honest for markers too.
        assertEquals("Start", line(HistoryMode.TIME, HistoryAction.START, 0, 0L).toSyncRecord(0L).action)
        assertEquals("Stop", line(HistoryMode.TIME, HistoryAction.STOP, 1, 0L).toSyncRecord(0L).action)
        assertEquals("Reset", line(HistoryMode.TIME, HistoryAction.RESET, 1, 0L).toSyncRecord(0L).action)
        assertEquals("Undo", line(HistoryMode.TIME, HistoryAction.UNDO, 1, 0L).toSyncRecord(0L).action)
    }

    // Bibs-mode HistoryLineEntity.toSyncRecord

    @Test
    fun finishTypeMapsToFinishAction() {
        val record = bibEntry(101, HistoryAction.FINISH, splitNumber = 1, timestampMillis = 60_000L).toSyncRecord(null)
        assertEquals("Finish", record.action)
        // Wire bibNumber is a string, not the raw Int — see SyncRecord's own doc.
        assertEquals("101", record.bibNumber)
    }

    @Test
    fun bibEntryTimeIsAlwaysNullBibsModeHasNoStopwatchOfItsOwn() {
        val record = bibEntry(101, HistoryAction.FINISH, splitNumber = 1, timestampMillis = 360_000L).toSyncRecord(0L)
        assertNull(record.splitTime)
    }

    @Test
    fun retireTypeMapsToDnfAction() {
        val record = bibEntry(101, HistoryAction.RETIRE, splitNumber = 1, timestampMillis = 0L).toSyncRecord(null)
        assertEquals("DNF", record.action)
    }

    @Test
    fun clockTypeMapsToClockActionWithNAOnTheWireNotNull() {
        // A Bibs record must never send bibNumber = null — that's reserved to mean "this is a
        // Time record" (see SyncRecord's own doc) — so an action with no bib of its own sends
        // the sentinel "n/a" instead, same as the history list already displays it.
        val record = bibEntry(null, HistoryAction.CLOCK, splitNumber = 0, timestampMillis = 0L, note = "5:30").toSyncRecord(null)
        assertEquals("Clock", record.action)
        assertEquals("n/a", record.bibNumber)
        assertEquals("5:30", record.note)
    }

    @Test
    fun everyNonBibBibsActionSendsNAOnTheWireNotNull() {
        // Not just Clock — every Bibs action outside BIB_REQUIRED_ACTIONS (Stop, Reset, Ignore,
        // Seniors, Juniors, Male, Female, Undo) has a null bibNumber locally and must equally
        // avoid a null wire bibNumber, for the same reason as clockTypeMapsToClockActionWithNAOnTheWireNotNull.
        assertEquals("n/a", bibEntry(null, HistoryAction.STOP, 1, 0L).toSyncRecord(null).bibNumber)
        assertEquals("n/a", bibEntry(null, HistoryAction.RESET, 1, 0L).toSyncRecord(null).bibNumber)
        assertEquals("n/a", bibEntry(null, HistoryAction.IGNORE, 1, 0L).toSyncRecord(null).bibNumber)
        assertEquals("n/a", bibEntry(null, HistoryAction.UNDO, 1, 0L).toSyncRecord(null).bibNumber)
    }

    @Test
    fun specialTypesMapToTitleCaseActions() {
        assertEquals("Seniors", bibEntry(null, HistoryAction.SENIORS, 1, 0L).toSyncRecord(null).action)
        assertEquals("Juniors", bibEntry(null, HistoryAction.JUNIORS, 1, 0L).toSyncRecord(null).action)
        assertEquals("Male", bibEntry(null, HistoryAction.MALE, 1, 0L).toSyncRecord(null).action)
        assertEquals("Female", bibEntry(null, HistoryAction.FEMALE, 1, 0L).toSyncRecord(null).action)
        assertEquals("Ignore", bibEntry(null, HistoryAction.IGNORE, 1, 0L).toSyncRecord(null).action)
        assertEquals("Stop", bibEntry(null, HistoryAction.STOP, 1, 0L).toSyncRecord(null).action)
        assertEquals("Undo", bibEntry(null, HistoryAction.UNDO, 1, 0L).toSyncRecord(null).action)
    }

    @Test
    fun bibEntryCarriesRawWallClockTimestampThrough() {
        val record = bibEntry(101, HistoryAction.FINISH, splitNumber = 1, timestampMillis = 1_700_000_000_000L).toSyncRecord(null)
        assertEquals(1_700_000_000_000L, record.timestampMillis)
    }

    @Test
    fun lineNumberCarriesThroughForBothRecordTypes() {
        val splitRecord = split(splitNumber = 1, timestampMillis = 0L, lineNumber = 42L).toSyncRecord(0L)
        assertEquals(42L, splitRecord.lineNumber)
        val bibRecord = bibEntry(101, HistoryAction.FINISH, splitNumber = 1, timestampMillis = 0L, lineNumber = 99L).toSyncRecord(null)
        assertEquals(99L, bibRecord.lineNumber)
    }

    @Test
    fun refLineNumberCarriesThroughForBothRecordTypesIncludingNull() {
        val splitEcho = split(splitNumber = 1, timestampMillis = 0L, lineNumber = 5L, refLineNumber = 2L).toSyncRecord(0L)
        assertEquals(2L, splitEcho.refLineNumber)
        val splitOriginal = split(splitNumber = 1, timestampMillis = 0L, lineNumber = 5L).toSyncRecord(0L)
        assertNull(splitOriginal.refLineNumber)

        val bibEcho = bibEntry(101, HistoryAction.FINISH, splitNumber = 1, timestampMillis = 0L, lineNumber = 5L, refLineNumber = 2L).toSyncRecord(null)
        assertEquals(2L, bibEcho.refLineNumber)
        val bibOriginal = bibEntry(101, HistoryAction.FINISH, splitNumber = 1, timestampMillis = 0L, lineNumber = 5L).toSyncRecord(null)
        assertNull(bibOriginal.refLineNumber)
    }

    // CP-mode HistoryLineEntity.toSyncRecord — CP shares Bibs' wire shape exactly (bibNumber as
    // a non-null string, splitTime always null) rather than getting its own — see
    // SyncRecordMapping's own wireBibNumber condition.

    @Test
    fun passTypeMapsToPassActionAndSendsBibNumberAsString() {
        val record = cpEntry(101, HistoryAction.PASS, splitNumber = 1, timestampMillis = 60_000L).toSyncRecord(null)
        assertEquals("Pass", record.action)
        assertEquals("101", record.bibNumber)
    }

    @Test
    fun cpRetireTypeMapsToDnfActionSameAsBibs() {
        val record = cpEntry(101, HistoryAction.RETIRE, splitNumber = 1, timestampMillis = 0L).toSyncRecord(null)
        assertEquals("DNF", record.action)
    }

    @Test
    fun cpEntryTimeIsAlwaysNullCpModeHasNoStopwatchOfItsOwn() {
        val record = cpEntry(101, HistoryAction.PASS, splitNumber = 1, timestampMillis = 360_000L).toSyncRecord(0L)
        assertNull(record.splitTime)
    }

    @Test
    fun cpMarkerActionsSendNAOnTheWireNotNull() {
        assertEquals("n/a", cpEntry(null, HistoryAction.STOP, 1, 0L).toSyncRecord(null).bibNumber)
        assertEquals("n/a", cpEntry(null, HistoryAction.RESET, 1, 0L).toSyncRecord(null).bibNumber)
        assertEquals("n/a", cpEntry(null, HistoryAction.UNDO, 1, 0L).toSyncRecord(null).bibNumber)
    }

    @Test
    fun roundTripsEveryCpModeActionThroughTheWireAndBack() {
        assertEquals(HistoryAction.PASS, cpEntry(101, HistoryAction.PASS, 1, 0L).toSyncRecord(null).toHistoryAction())
        assertEquals(HistoryAction.RETIRE, cpEntry(101, HistoryAction.RETIRE, 1, 0L).toSyncRecord(null).toHistoryAction())
        assertEquals(HistoryAction.STOP, cpEntry(null, HistoryAction.STOP, 1, 0L).toSyncRecord(null).toHistoryAction())
        assertEquals(HistoryAction.RESET, cpEntry(null, HistoryAction.RESET, 1, 0L).toSyncRecord(null).toHistoryAction())
        assertEquals(HistoryAction.UNDO, cpEntry(null, HistoryAction.UNDO, 1, 0L).toSyncRecord(null).toHistoryAction())
    }

    // SyncRecord.toHistoryAction — the exact inverse of toServerAction, exercised via a full
    // round trip through toSyncRecord for every case above rather than constructing SyncRecord
    // literals directly, so this fails the moment the two mappings drift apart from each other.

    @Test
    fun roundTripsEveryTimeModeActionThroughTheWireAndBack() {
        assertEquals(HistoryAction.SPLIT, split(splitNumber = 1, timestampMillis = 0L).toSyncRecord(0L).toHistoryAction())
        assertEquals(HistoryAction.START, line(HistoryMode.TIME, HistoryAction.START, 0, 0L).toSyncRecord(0L).toHistoryAction())
        assertEquals(HistoryAction.STOP, line(HistoryMode.TIME, HistoryAction.STOP, 1, 0L).toSyncRecord(0L).toHistoryAction())
        assertEquals(HistoryAction.RESET, line(HistoryMode.TIME, HistoryAction.RESET, 1, 0L).toSyncRecord(0L).toHistoryAction())
        assertEquals(HistoryAction.UNDO, line(HistoryMode.TIME, HistoryAction.UNDO, 1, 0L).toSyncRecord(0L).toHistoryAction())
    }

    @Test
    fun roundTripsEveryBibsModeActionThroughTheWireAndBack() {
        assertEquals(HistoryAction.FINISH, bibEntry(101, HistoryAction.FINISH, 1, 0L).toSyncRecord(null).toHistoryAction())
        assertEquals(HistoryAction.START, bibEntry(101, HistoryAction.START, 1, 0L).toSyncRecord(null).toHistoryAction())
        assertEquals(HistoryAction.RETIRE, bibEntry(101, HistoryAction.RETIRE, 1, 0L).toSyncRecord(null).toHistoryAction())
        assertEquals(HistoryAction.IGNORE, bibEntry(null, HistoryAction.IGNORE, 1, 0L).toSyncRecord(null).toHistoryAction())
        assertEquals(HistoryAction.SENIORS, bibEntry(null, HistoryAction.SENIORS, 1, 0L).toSyncRecord(null).toHistoryAction())
        assertEquals(HistoryAction.JUNIORS, bibEntry(null, HistoryAction.JUNIORS, 1, 0L).toSyncRecord(null).toHistoryAction())
        assertEquals(HistoryAction.MALE, bibEntry(null, HistoryAction.MALE, 1, 0L).toSyncRecord(null).toHistoryAction())
        assertEquals(HistoryAction.FEMALE, bibEntry(null, HistoryAction.FEMALE, 1, 0L).toSyncRecord(null).toHistoryAction())
        assertEquals(HistoryAction.CLOCK, bibEntry(null, HistoryAction.CLOCK, 0, 0L).toSyncRecord(null).toHistoryAction())
        assertEquals(HistoryAction.STOP, bibEntry(null, HistoryAction.STOP, 1, 0L).toSyncRecord(null).toHistoryAction())
        assertEquals(HistoryAction.RESET, bibEntry(null, HistoryAction.RESET, 1, 0L).toSyncRecord(null).toHistoryAction())
        assertEquals(HistoryAction.UNDO, bibEntry(null, HistoryAction.UNDO, 1, 0L).toSyncRecord(null).toHistoryAction())
    }

    @Test
    fun splitAndFinishAreDistinctUnambiguousWireValues() {
        // A Time split is sent as its own honest "Split" (see toServerAction's own doc), so
        // "Finish" on the wire now means exactly one thing — a genuine Bibs Finish — regardless
        // of whether `splitTime` happens to be set.
        assertEquals(HistoryAction.SPLIT, SyncRecord(recordUuid = "u", action = "Split", bibNumber = null, splitTime = "00:00:00", location = "Finish", splitNumber = 1, lineNumber = 1L, note = null, timestampMillis = 0L).toHistoryAction())
        assertEquals(HistoryAction.FINISH, SyncRecord(recordUuid = "u", action = "Finish", bibNumber = "101", splitTime = null, location = "Finish", splitNumber = 1, lineNumber = 1L, note = null, timestampMillis = 0L).toHistoryAction())
    }

    @Test
    fun unrecognizedWireActionFallsBackToIgnoreRatherThanThrowing() {
        assertEquals(
            HistoryAction.IGNORE,
            SyncRecord(recordUuid = "u", action = "SomeFutureAction", bibNumber = null, splitTime = null, location = "Finish", splitNumber = 1, lineNumber = 1L, note = null, timestampMillis = 0L).toHistoryAction(),
        )
    }
}
