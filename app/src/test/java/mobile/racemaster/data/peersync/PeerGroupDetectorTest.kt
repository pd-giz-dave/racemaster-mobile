package mobile.racemaster.data.peersync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import mobile.racemaster.data.db.dao.PeerSeenRecordDao
import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.PeerSeenRecordEntity
import mobile.racemaster.data.mule.MuleGattProfile
import mobile.racemaster.data.settings.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// groupKeyOf/sameGroupPeerKeys never touch the DAO — a fake that's never actually called is
// enough to construct a real PeerSyncRepository for these pure-logic tests (RaceRepository/
// SettingsRepository aren't exercised by either function either, so ownGroupKey's own
// flow-combining behavior is left to PeerSyncEngine's own field verification instead).
private class UncalledPeerSeenRecordDao : PeerSeenRecordDao {
    override suspend fun insertAll(rows: List<PeerSeenRecordEntity>) = error("not used by this test")
    override suspend fun getLastMergedLineNumber(originDeviceId: String) = error("not used by this test")
    override suspend fun getLastMergedCountStatusLineNumber(originDeviceId: String) = error("not used by this test")
    override fun observeSeenBeaconNumbers(raceGroupId: String, mode: HistoryMode): Flow<List<Int>> = flowOf(emptyList())
    override fun observeSeenBibNumbers(raceGroupId: String, mode: HistoryMode): Flow<List<Int>> = flowOf(emptyList())
    override fun observeForBeacon(raceGroupId: String, mode: HistoryMode, beaconNumber: Int): Flow<List<PeerSeenRecordEntity>> = flowOf(emptyList())
    override fun observeForBib(raceGroupId: String, mode: HistoryMode, bibNumber: Int): Flow<List<PeerSeenRecordEntity>> = flowOf(emptyList())
    override fun observeGroup(raceGroupId: String, mode: HistoryMode): Flow<List<PeerSeenRecordEntity>> = flowOf(emptyList())
}

class PeerGroupDetectorTest {
    private val detector = PeerGroupDetector(PeerSyncRepository(UncalledPeerSeenRecordDao()))

    private fun identity(raceLabel: String, mode: AppMode, location: String = "Finish") =
        MuleGattProfile.decodeAdvertisedIdentity(
            MuleGattProfile.encodeAdvertisedIdentity(
                lastLineNumber = 1L, deviceId = "device-a", deviceName = "", mode = mode,
                raceLabel = raceLabel, location = location,
            ),
        )!!

    @Test
    fun groupKeyOfIsNullForAnIdentityAdvertisingNoActiveRace() {
        val blank = MuleGattProfile.decodeAdvertisedIdentity(
            MuleGattProfile.encodeAdvertisedIdentity(lastLineNumber = 0L, deviceId = "device-a", deviceName = "", mode = AppMode.COUNT),
        )!!

        assertEquals(null, detector.groupKeyOf(blank))
    }

    @Test
    fun groupKeyOfIsNullForAnIdentityAdvertisingNoMode() {
        val noMode = MuleGattProfile.decodeAdvertisedIdentity(
            MuleGattProfile.encodeAdvertisedIdentity(lastLineNumber = 0L, deviceId = "device-a", deviceName = "", raceLabel = "race-a"),
        )!!

        assertEquals(null, detector.groupKeyOf(noMode))
    }

    @Test
    fun twoCountPhonesOnTheSameRaceShareAGroupKeyRegardlessOfLocation() {
        val one = identity("lmv-23-08-2026", AppMode.COUNT, location = "Start Pen")
        val two = identity("lmv-23-08-2026", AppMode.COUNT, location = "Somewhere Else")

        assertEquals(detector.groupKeyOf(one), detector.groupKeyOf(two))
    }

    @Test
    fun bibsPhonesAtDifferentStationsOfTheSameRaceGetDifferentGroupKeys() {
        val start = identity("lmv-23-08-2026", AppMode.BIBS, location = "Start")
        val finish = identity("lmv-23-08-2026", AppMode.BIBS, location = "Finish")

        assertTrue(detector.groupKeyOf(start) != detector.groupKeyOf(finish))
    }

    @Test
    fun bibsPhonesAtTheSameStationOfTheSameRaceShareAGroupKey() {
        val one = identity("lmv-23-08-2026", AppMode.BIBS, location = "Finish")
        val two = identity("lmv-23-08-2026", AppMode.BIBS, location = "Finish")

        assertEquals(detector.groupKeyOf(one), detector.groupKeyOf(two))
    }

    @Test
    fun differentRacesNeverShareAGroupKeyEvenWithTheSameModeAndLocation() {
        val raceOne = identity("race-one", AppMode.BIBS, location = "Finish")
        val raceTwo = identity("race-two", AppMode.BIBS, location = "Finish")

        assertTrue(detector.groupKeyOf(raceOne) != detector.groupKeyOf(raceTwo))
    }

    @Test
    fun countAndCloseOnTheSameRaceNeverShareAGroupKeyEvenThoughNeitherUsesLocation() {
        val count = identity("lmv-23-08-2026", AppMode.COUNT)
        val close = identity("lmv-23-08-2026", AppMode.CLOSE)

        assertTrue(detector.groupKeyOf(count) != detector.groupKeyOf(close))
    }

    @Test
    fun ownGroupKeyMatchesGroupKeyOfForTheSameRaceModeAndLocation() {
        val ownKey = detector.ownGroupKey("lmv-23-08-2026", "Finish", HistoryMode.BIBS)
        val decoded = identity("lmv-23-08-2026", AppMode.BIBS, location = "Finish")

        assertEquals(ownKey, detector.groupKeyOf(decoded))
    }

    @Test
    fun ownGroupKeyIgnoresLocationForNonBibsModes() {
        val countStartPen = detector.ownGroupKey("lmv-23-08-2026", "Start Pen", HistoryMode.COUNT)
        val countElsewhere = detector.ownGroupKey("lmv-23-08-2026", "Somewhere Else", HistoryMode.COUNT)

        assertEquals(countStartPen, countElsewhere)
    }

    @Test
    fun sameGroupPeerKeysIsEmptyWithNoOwnGroupKey() {
        val decoded = mapOf("peer-a" to identity("lmv-23-08-2026", AppMode.COUNT))

        assertEquals(emptySet<String>(), detector.sameGroupPeerKeys(null, decoded))
    }

    @Test
    fun sameGroupPeerKeysSkipsAMissingOrUnusableDecodedIdentity() {
        val ownKey = detector.groupKeyOf(identity("lmv-23-08-2026", AppMode.COUNT))
        val decoded = mapOf(
            "peer-missed-scan" to null,
            "peer-no-race" to MuleGattProfile.decodeAdvertisedIdentity(
                MuleGattProfile.encodeAdvertisedIdentity(0L, "device-b", "", AppMode.COUNT),
            ),
        )

        assertEquals(emptySet<String>(), detector.sameGroupPeerKeys(ownKey, decoded))
    }

    @Test
    fun sameGroupPeerKeysReturnsOnlyTheMatchingKeys() {
        val ownKey = detector.groupKeyOf(identity("lmv-23-08-2026", AppMode.COUNT))
        val decoded = mapOf(
            "same-race-count" to identity("lmv-23-08-2026", AppMode.COUNT),
            "same-race-bibs" to identity("lmv-23-08-2026", AppMode.BIBS),
            "different-race-count" to identity("other-race", AppMode.COUNT),
        )

        assertEquals(setOf("same-race-count"), detector.sameGroupPeerKeys(ownKey, decoded))
    }
}
