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
        assertEquals(90, record.splitTime)
        assertEquals(3, record.splitNumber)
        assertEquals(null, record.bibNumber)
        assertEquals("Split", record.action)
    }

    @Test
    fun finishSplitWithNullRaceStartFormatsAsZero() {
        val record = split(splitNumber = 1, timestampMillis = 12_345L).toSyncRecord(raceStartedAtMillis = null)
        assertEquals(0, record.splitTime)
    }

    @Test
    fun finishSplitRoundsDownBelowHalfASecond() {
        val record = split(splitNumber = 1, timestampMillis = 1_490L).toSyncRecord(raceStartedAtMillis = 0L)
        assertEquals(1, record.splitTime)
    }

    @Test
    fun finishSplitRoundsUpAtHalfASecondOrMore() {
        val record = split(splitNumber = 1, timestampMillis = 1_530L).toSyncRecord(raceStartedAtMillis = 0L)
        assertEquals(2, record.splitTime)
    }

    @Test
    fun finishSplitCarriesNoteThrough() {
        val record = split(splitNumber = 1, timestampMillis = 1_000L, note = "Checkpoint 1").toSyncRecord(0L)
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
    fun clockMarkersMapToTheirOwnHonestActionsNotHardcodedFinish() {
        // Previously every Time row hardcoded action = "Finish" regardless of whether it was
        // really a Start/Stop/Reset/Undo marker — the real type only ever reached `note`. Now
        // the wire action is honest for markers too.
        assertEquals("Start", line(HistoryMode.TIME, HistoryAction.START, 0, 0L).toSyncRecord(0L).action)
        assertEquals("Stop", line(HistoryMode.TIME, HistoryAction.STOP, 1, 0L).toSyncRecord(0L).action)
        assertEquals("Reset", line(HistoryMode.TIME, HistoryAction.RESET, 1, 0L).toSyncRecord(0L).action)
        assertEquals("Undo", line(HistoryMode.TIME, HistoryAction.UNDO, 1, 0L).toSyncRecord(0L).action)
    }

    @Test
    fun timeModeModeStartSendsNullSplitTimeNotZeroElapsed() {
        // MODE_START is written at the exact same instant as the real Start marker right after
        // it, so a naive elapsed calculation would also read 0 — indistinguishable on the wire
        // from a genuine Start. It sends null instead — its own explicit mode declaration lives
        // in `note` (see AppMode.wireName()), so there's nothing for splitTime to signal here.
        val modeStart = line(HistoryMode.TIME, HistoryAction.MODE_START, splitNumber = 0, timestampMillis = 5_000L).toSyncRecord(raceStartedAtMillis = 5_000L)
        assertNull(modeStart.splitTime)
        // The real Start marker immediately after it, at the same timestamp, still reports a
        // genuine elapsed 0 — only MODE_START gets null.
        val start = line(HistoryMode.TIME, HistoryAction.START, splitNumber = 0, timestampMillis = 5_000L).toSyncRecord(raceStartedAtMillis = 5_000L)
        assertEquals(0, start.splitTime)
    }

    // Bibs-mode HistoryLineEntity.toSyncRecord

    @Test
    fun finishTypeMapsToFinishAction() {
        val record = bibEntry(101, HistoryAction.FINISH, splitNumber = 1, timestampMillis = 60_000L).toSyncRecord(null)
        assertEquals("Finish", record.action)
        // Wire bibNumber is a straight passthrough of the local Int? column — see SyncRecord's own doc.
        assertEquals(101, record.bibNumber)
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
    fun clockTypeMapsToClockActionWithNullBibNumber() {
        // No discriminator role left for bibNumber (see SyncRecord's own doc) — an action with
        // no bib of its own now just sends a genuine null, same as the local column already is.
        val record = bibEntry(null, HistoryAction.CLOCK, splitNumber = 0, timestampMillis = 0L, note = "5:30").toSyncRecord(null)
        assertEquals("Clock", record.action)
        assertNull(record.bibNumber)
        assertEquals("5:30", record.note)
    }

    @Test
    fun everyNonBibBibsActionSendsNullBibNumber() {
        // Not just Clock — every Bibs action outside BIB_REQUIRED_ACTIONS (Stop, Reset, Ignore,
        // Seniors, Juniors, Male, Female, Undo) has a null bibNumber locally and passes straight
        // through as null on the wire too now.
        assertNull(bibEntry(null, HistoryAction.STOP, 1, 0L).toSyncRecord(null).bibNumber)
        assertNull(bibEntry(null, HistoryAction.RESET, 1, 0L).toSyncRecord(null).bibNumber)
        assertNull(bibEntry(null, HistoryAction.IGNORE, 1, 0L).toSyncRecord(null).bibNumber)
        assertNull(bibEntry(null, HistoryAction.UNDO, 1, 0L).toSyncRecord(null).bibNumber)
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

    // CP-mode HistoryLineEntity.toSyncRecord — CP shares Bibs' wire shape exactly (bibNumber
    // passed straight through, splitTime always null) rather than getting its own.

    @Test
    fun passTypeMapsToPassAction() {
        val record = cpEntry(101, HistoryAction.PASS, splitNumber = 1, timestampMillis = 60_000L).toSyncRecord(null)
        assertEquals("Pass", record.action)
        assertEquals(101, record.bibNumber)
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
    fun cpMarkerActionsSendNullBibNumber() {
        assertNull(cpEntry(null, HistoryAction.STOP, 1, 0L).toSyncRecord(null).bibNumber)
        assertNull(cpEntry(null, HistoryAction.RESET, 1, 0L).toSyncRecord(null).bibNumber)
        assertNull(cpEntry(null, HistoryAction.UNDO, 1, 0L).toSyncRecord(null).bibNumber)
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
    fun modeStartRoundTripsThroughTheWireOnItsOwnDistinctValue() {
        // Deliberately not "Start" on the wire — see toServerAction's own doc for why the web
        // app needs to tell this boundary marker apart from a mode's own real Start/Clock row.
        val record = line(HistoryMode.BIBS, HistoryAction.MODE_START, 0, 0L).toSyncRecord(null)
        assertEquals("ModeStart", record.action)
        assertEquals(HistoryAction.MODE_START, record.toHistoryAction())
    }

    @Test
    fun locationMarkerRoundTripsThroughTheWireCarryingTheNewLocationInNote() {
        // HistoryAction.LOCATION — Setup Race/Relocate's own boundary marker (see
        // RaceRepository.recordModeStart). The destination location travels in `note`.
        val record = bibEntry(null, HistoryAction.LOCATION, splitNumber = 0, timestampMillis = 0L, note = "CP2")
            .toSyncRecord(null)
        assertEquals("Location", record.action)
        assertNull(record.bibNumber) // no discriminator sentinel any more — see SyncRecord's own doc
        assertEquals("CP2", record.note)
        assertEquals(HistoryAction.LOCATION, record.toHistoryAction())
    }

    @Test
    fun modeStartCarriesNoBibNumberOrSplitTimeRegardlessOfMode() {
        // Its own explicit mode declaration lives in `note` instead (see AppMode.wireName()) —
        // bibNumber/splitNumber are always null, and splitTime is always null too now (even for
        // a Time-mode MODE_START, which used to send the "n/a" sentinel there).
        val timeModeStart = line(HistoryMode.TIME, HistoryAction.MODE_START, splitNumber = 0, timestampMillis = 5_000L, note = "Time")
            .toSyncRecord(raceStartedAtMillis = 5_000L)
        assertNull(timeModeStart.bibNumber)
        assertNull(timeModeStart.splitTime)
        assertEquals("Time", timeModeStart.note)

        val bibsModeStart = bibEntry(null, HistoryAction.MODE_START, splitNumber = 0, timestampMillis = 0L, note = "Bibs")
            .toSyncRecord(null)
        assertNull(bibsModeStart.bibNumber)
        assertNull(bibsModeStart.splitTime)
        assertEquals("Bibs", bibsModeStart.note)
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
        assertEquals(HistoryAction.SPLIT, SyncRecord(action = "Split", bibNumber = null, splitTime = 0, splitNumber = 1, lineNumber = 1L, note = null, timestampMillis = 0L).toHistoryAction())
        assertEquals(HistoryAction.FINISH, SyncRecord(action = "Finish", bibNumber = 101, splitTime = null, splitNumber = 1, lineNumber = 1L, note = null, timestampMillis = 0L).toHistoryAction())
    }

    @Test
    fun unrecognizedWireActionFallsBackToIgnoreRatherThanThrowing() {
        assertEquals(
            HistoryAction.IGNORE,
            SyncRecord(action = "SomeFutureAction", bibNumber = null, splitTime = null, splitNumber = 1, lineNumber = 1L, note = null, timestampMillis = 0L).toHistoryAction(),
        )
    }
}
