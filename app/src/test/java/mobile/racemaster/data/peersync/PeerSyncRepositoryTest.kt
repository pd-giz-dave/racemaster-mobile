package mobile.racemaster.data.peersync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mobile.racemaster.data.db.dao.PeerSeenRecordDao
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.PeerSeenRecordEntity
import mobile.racemaster.data.mule.SyncRecord
import org.junit.Assert.assertEquals
import org.junit.Test

// An in-memory fake rather than a mock — insertAll/getLastMergedLineNumber genuinely read back
// what was written (mergeRecords' own tests need that), while every observe* method just serves
// whatever rows() currently holds, same shape PeerGroupDetectorTest's own
// UncalledPeerSeenRecordDao uses for the methods it never actually exercises.
private class FakePeerSeenRecordDao : PeerSeenRecordDao {
    private val rows = mutableListOf<PeerSeenRecordEntity>()

    override suspend fun insertAll(rows: List<PeerSeenRecordEntity>) {
        this.rows += rows
    }

    override suspend fun getLastMergedLineNumber(originDeviceId: String): Long? =
        rows.filter { it.originDeviceId == originDeviceId }.maxOfOrNull { it.lineNumber }

    override suspend fun getLastMergedCountStatusLineNumber(originDeviceId: String): Long? =
        rows.filter { it.originDeviceId == originDeviceId && it.action in setOf("COUNT_SEEN", "COUNT_EJECTED", "COUNT_UNEJECTED") }
            .maxOfOrNull { it.lineNumber }

    override fun observeSeenBeaconNumbers(raceGroupId: String, mode: HistoryMode): Flow<List<Int>> =
        flowOf(rows.filter { it.raceGroupId == raceGroupId && it.mode == mode }.mapNotNull { it.beaconNumber }.distinct())

    override fun observeSeenBibNumbers(raceGroupId: String, mode: HistoryMode): Flow<List<Int>> =
        flowOf(rows.filter { it.raceGroupId == raceGroupId && it.mode == mode }.mapNotNull { it.bibNumber }.distinct())

    override fun observeForBeacon(raceGroupId: String, mode: HistoryMode, beaconNumber: Int): Flow<List<PeerSeenRecordEntity>> =
        flowOf(rows.filter { it.raceGroupId == raceGroupId && it.mode == mode && it.beaconNumber == beaconNumber })

    override fun observeForBib(raceGroupId: String, mode: HistoryMode, bibNumber: Int): Flow<List<PeerSeenRecordEntity>> =
        flowOf(rows.filter { it.raceGroupId == raceGroupId && it.mode == mode && it.bibNumber == bibNumber })

    override fun observeGroup(raceGroupId: String, mode: HistoryMode): Flow<List<PeerSeenRecordEntity>> =
        flowOf(rows.filter { it.raceGroupId == raceGroupId && it.mode == mode })
}

class PeerSyncRepositoryTest {
    private val dao = FakePeerSeenRecordDao()
    private val repository = PeerSyncRepository(dao)

    private fun syncRecord(
        action: String,
        bibNumber: Int?,
        lineNumber: Long,
        note: String?,
        timestampMillis: Long,
        splitNumber: Int? = null,
    ) = SyncRecord(action = action, bibNumber = bibNumber, splitTime = null, splitNumber = splitNumber, lineNumber = lineNumber, note = note, timestampMillis = timestampMillis)

    @Test
    fun groupKeyIgnoresLocationForNonBibsModes() {
        assertEquals(repository.groupKey(42, HistoryMode.COUNT, locationCode = 1), repository.groupKey(42, HistoryMode.COUNT, locationCode = 2))
    }

    @Test
    fun groupKeyDistinguishesLocationForBibs() {
        assertEquals(false, repository.groupKey(42, HistoryMode.BIBS, locationCode = 1) == repository.groupKey(42, HistoryMode.BIBS, locationCode = 2))
    }

    @Test
    fun mergeRecordsExtractsBeaconNumberFromNoteForCountRows() = runTest {
        repository.mergeRecords(
            raceGroupId = "group-1",
            mode = HistoryMode.COUNT,
            originDeviceId = "device-a",
            originDeviceName = "brave-reef",
            records = listOf(syncRecord("COUNT_SEEN", bibNumber = null, lineNumber = 1L, note = "beacon=7 seen=1 of=5", timestampMillis = 1_000L)),
        )
        val merged = repository.observeGroup("group-1", HistoryMode.COUNT)
        assertEquals(7, merged.first().single().beaconNumber)
    }

    @Test
    fun mergeRecordsCarriesBibNumberStraightThroughForBibsRows() = runTest {
        repository.mergeRecords(
            raceGroupId = "group-1",
            mode = HistoryMode.BIBS,
            originDeviceId = "device-a",
            originDeviceName = "brave-reef",
            records = listOf(syncRecord("FINISH", bibNumber = 42, lineNumber = 1L, note = null, timestampMillis = 1_000L)),
        )
        val merged = repository.observeGroup("group-1", HistoryMode.BIBS)
        assertEquals(42, merged.first().single().bibNumber)
    }

    @Test
    fun mergeRecordsIsANoOpForAnEmptyBatch() = runTest {
        repository.mergeRecords("group-1", HistoryMode.COUNT, "device-a", "brave-reef", emptyList())
        assertEquals(emptyList<PeerSeenRecordEntity>(), repository.observeGroup("group-1", HistoryMode.COUNT).first())
    }

    @Test
    fun observeLatestOriginByBeaconCollapsesToTheMostRecentReport() = runTest {
        repository.mergeRecords(
            "group-1", HistoryMode.COUNT, "device-a", "brave-reef",
            listOf(syncRecord("COUNT_SEEN", null, 1L, "beacon=7", timestampMillis = 1_000L)),
        )
        repository.mergeRecords(
            "group-1", HistoryMode.COUNT, "device-b", "calm-dune",
            listOf(syncRecord("COUNT_SEEN", null, 1L, "beacon=7", timestampMillis = 2_000L)),
        )
        val byBeacon = repository.observeLatestOriginByBeacon("group-1", HistoryMode.COUNT).first()
        assertEquals(mapOf(7 to "calm-dune"), byBeacon)
    }

    @Test
    fun observeLatestOriginByBeaconIgnoresRowsWithNoBeaconNumber() = runTest {
        repository.mergeRecords(
            "group-1", HistoryMode.COUNT, "device-a", "brave-reef",
            listOf(syncRecord("COUNT_COMPLETE", null, 1L, note = "of=5", timestampMillis = 1_000L)),
        )
        assertEquals(emptyMap<Int, String>(), repository.observeLatestOriginByBeacon("group-1", HistoryMode.COUNT).first())
    }

    @Test
    fun observeLatestOriginByBibCollapsesToTheMostRecentReport() = runTest {
        repository.mergeRecords(
            "group-1", HistoryMode.BIBS, "device-a", "brave-reef",
            listOf(syncRecord("FINISH", 42, 1L, null, timestampMillis = 2_000L)),
        )
        repository.mergeRecords(
            "group-1", HistoryMode.BIBS, "device-b", "calm-dune",
            listOf(syncRecord("FINISH", 42, 1L, null, timestampMillis = 1_000L)),
        )
        val byBib = repository.observeLatestOriginByBib("group-1", HistoryMode.BIBS).first()
        assertEquals(mapOf(42 to "brave-reef"), byBib)
    }

    @Test
    fun mergeRecordsCarriesSplitNumberStraightThroughForBibsRows() = runTest {
        repository.mergeRecords(
            raceGroupId = "group-1",
            mode = HistoryMode.BIBS,
            originDeviceId = "device-a",
            originDeviceName = "brave-reef",
            records = listOf(syncRecord("FINISH", bibNumber = 42, lineNumber = 1L, note = null, timestampMillis = 1_000L, splitNumber = 3)),
        )
        val merged = repository.observeGroup("group-1", HistoryMode.BIBS)
        assertEquals(3, merged.first().single().splitNumber)
    }

    @Test
    fun observeLatestOriginByBibIsScopedToItsOwnGroupAndMode() = runTest {
        repository.mergeRecords(
            "group-1", HistoryMode.BIBS, "device-a", "brave-reef",
            listOf(syncRecord("FINISH", 42, 1L, null, timestampMillis = 1_000L)),
        )
        repository.mergeRecords(
            "group-2", HistoryMode.BIBS, "device-b", "calm-dune",
            listOf(syncRecord("FINISH", 42, 1L, null, timestampMillis = 9_000L)),
        )
        assertEquals(mapOf(42 to "brave-reef"), repository.observeLatestOriginByBib("group-1", HistoryMode.BIBS).first())
    }

}
