package mobile.racemaster.data.mule

import mobile.racemaster.data.db.entity.BibEntryEntity
import mobile.racemaster.data.db.entity.BibEntryType
import mobile.racemaster.data.db.entity.FinishSplitEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncRecordMappingTest {

    private fun split(splitNumber: Int, timestampMillis: Long, note: String? = null, deviceName: String = "") = FinishSplitEntity(
        id = 1L,
        raceId = 1L,
        splitNumber = splitNumber,
        timestampMillis = timestampMillis,
        note = note,
        recordUuid = "split-uuid",
        deviceName = deviceName,
    )

    private fun bibEntry(
        bibNumber: Int?,
        type: BibEntryType,
        splitNumber: Int,
        timestampMillis: Long,
        note: String? = null,
        deviceName: String = "",
    ) = BibEntryEntity(
        id = 1L,
        raceId = 1L,
        bibNumber = bibNumber,
        type = type,
        splitNumber = splitNumber,
        note = note,
        timestampMillis = timestampMillis,
        recordUuid = "bib-uuid",
        deviceName = deviceName,
    )

    // FinishSplitEntity.toSyncRecord

    @Test
    fun finishSplitMapsElapsedTimeRelativeToRaceStart() {
        val record = split(splitNumber = 3, timestampMillis = 90_000L).toSyncRecord(raceStartedAtMillis = 0L)
        assertEquals("00:01:30", record.time)
        assertEquals(3, record.splitNumber)
        assertEquals(null, record.number)
        assertEquals("Finish", record.action)
    }

    @Test
    fun finishSplitWithNullRaceStartFormatsAsZero() {
        val record = split(splitNumber = 1, timestampMillis = 12_345L).toSyncRecord(raceStartedAtMillis = null)
        assertEquals("00:00:00", record.time)
    }

    @Test
    fun finishSplitCarriesRecordUuidAndNoteThrough() {
        val record = split(splitNumber = 1, timestampMillis = 1_000L, note = "Checkpoint 1").toSyncRecord(0L)
        assertEquals("split-uuid", record.recordUuid)
        assertEquals("Checkpoint 1", record.note)
    }

    @Test
    fun finishSplitCarriesRawWallClockTimestampAlongsideElapsedTime() {
        // timestampMillis is the raw wall-clock time, independent of raceStartedAtMillis —
        // only `time` is relative to race start.
        val record = split(splitNumber = 1, timestampMillis = 1_700_000_000_000L).toSyncRecord(raceStartedAtMillis = 999_000L)
        assertEquals(1_700_000_000_000L, record.timestampMillis)
    }

    // BibEntryEntity.toSyncRecord

    @Test
    fun finishTypeMapsToFinishAction() {
        val record = bibEntry(101, BibEntryType.FINISH, splitNumber = 1, timestampMillis = 60_000L).toSyncRecord()
        assertEquals("Finish", record.action)
        assertEquals(101, record.number)
    }

    @Test
    fun bibEntryTimeIsAlwaysNullBibsModeHasNoStopwatchOfItsOwn() {
        val record = bibEntry(101, BibEntryType.FINISH, splitNumber = 1, timestampMillis = 360_000L).toSyncRecord()
        assertNull(record.time)
    }

    @Test
    fun retireTypeMapsToDnfAction() {
        val record = bibEntry(101, BibEntryType.RETIRE, splitNumber = 1, timestampMillis = 0L).toSyncRecord()
        assertEquals("DNF", record.action)
    }

    @Test
    fun clockTypeMapsToClockActionWithNullNumber() {
        val record = bibEntry(null, BibEntryType.CLOCK, splitNumber = 0, timestampMillis = 0L, note = "5:30").toSyncRecord()
        assertEquals("Clock", record.action)
        assertNull(record.number)
        assertEquals("5:30", record.note)
    }

    @Test
    fun specialTypesMapToTitleCaseActions() {
        assertEquals("Seniors", bibEntry(null, BibEntryType.SENIORS, 1, 0L).toSyncRecord().action)
        assertEquals("Juniors", bibEntry(null, BibEntryType.JUNIORS, 1, 0L).toSyncRecord().action)
        assertEquals("Male", bibEntry(null, BibEntryType.MALE, 1, 0L).toSyncRecord().action)
        assertEquals("Female", bibEntry(null, BibEntryType.FEMALE, 1, 0L).toSyncRecord().action)
        assertEquals("Ignore", bibEntry(null, BibEntryType.IGNORE, 1, 0L).toSyncRecord().action)
        assertEquals("Stop", bibEntry(null, BibEntryType.STOP, 1, 0L).toSyncRecord().action)
    }

    @Test
    fun bibEntryCarriesRawWallClockTimestampThrough() {
        val record = bibEntry(101, BibEntryType.FINISH, splitNumber = 1, timestampMillis = 1_700_000_000_000L).toSyncRecord()
        assertEquals(1_700_000_000_000L, record.timestampMillis)
    }

    @Test
    fun deviceNameCarriesThroughForBothRecordTypes() {
        val splitRecord = split(splitNumber = 1, timestampMillis = 0L, deviceName = "clever-cricket").toSyncRecord(0L)
        assertEquals("clever-cricket", splitRecord.deviceName)
        val bibRecord = bibEntry(101, BibEntryType.FINISH, splitNumber = 1, timestampMillis = 0L, deviceName = "clever-cricket").toSyncRecord()
        assertEquals("clever-cricket", bibRecord.deviceName)
    }
}
