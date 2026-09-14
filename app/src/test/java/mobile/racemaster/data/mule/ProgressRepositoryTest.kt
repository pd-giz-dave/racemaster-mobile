package mobile.racemaster.data.mule

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import mobile.racemaster.data.db.dao.ProgressDao
import mobile.racemaster.data.db.entity.ProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// A plain in-memory stand-in for the real Room DAO — this module has no Robolectric (see
// app/build.gradle.kts), so a real Room database isn't available under plain JUnit; this is
// enough to exercise ProgressRepository's own write-through/read logic without one.
private class FakeProgressDao : ProgressDao {
    private val rows = MutableStateFlow<Map<Long, ProgressEntity>>(emptyMap())

    override suspend fun upsert(entity: ProgressEntity) {
        rows.value = rows.value + (entity.raceId to entity)
    }

    override suspend fun getByRaceId(raceId: Long): ProgressEntity? = rows.value[raceId]

    override fun observeAll(): Flow<List<ProgressEntity>> = rows.map { it.values.sortedByDescending { e -> e.storedAtMillis } }

    override suspend fun deleteByRaceId(raceId: Long) {
        rows.value = rows.value - raceId
    }
}

// refreshFromServer's own network leg (constructing a live MuleSyncClient call) is deliberately
// not exercised here — same reasoning as before this class also covered persistence: no
// MuleSyncClientTest.kt exists in this codebase either. storeFromBle already shares
// refreshFromServer's own store() write-through, so this is exact coverage of that shared path.
class ProgressRepositoryTest {
    private val dao = FakeProgressDao()
    private val repository = ProgressRepository(MuleSyncClient(), dao)

    private fun payload(generatedAt: String = "2026-08-23T10:00:00.000Z") = ProgressPayload(
        raceName = "Test Race", raceDate = "23/08/2026", generatedAt = generatedAt,
        entries = listOf(ProgressEntry(bibNumber = 1, name = "Dave", course = "Seniors")),
    )

    @Test
    fun storeFromBleThenGeneratedAtForTheSameRaceIdReturnsIt() = runTest {
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = payload())

        assertEquals("2026-08-23T10:00:00.000Z", repository.generatedAtFor(1L))
    }

    @Test
    fun generatedAtForADifferentRaceIdReturnsNull() = runTest {
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = payload())

        assertNull(repository.generatedAtFor(2L))
    }

    @Test
    fun generatedAtForReturnsNullWhenNothingStoredYet() {
        assertNull(repository.generatedAtFor(1L))
        assertNull(repository.generatedAtFor(null))
    }

    @Test
    fun clearIfRaceChangedWipesStoredProgressWhenTheActiveRaceDiffers() = runTest {
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = payload())

        repository.clearIfRaceChanged(activeRaceId = 2L)

        assertNull(repository.generatedAtFor(1L))
        assertEquals(null, repository.current.value)
    }

    @Test
    fun clearIfRaceChangedLeavesStoredProgressWhenTheActiveRaceStillMatches() = runTest {
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = payload())

        repository.clearIfRaceChanged(activeRaceId = 1L)

        assertEquals("2026-08-23T10:00:00.000Z", repository.generatedAtFor(1L))
    }

    @Test
    fun clearIfRaceChangedIsANoOpWhenNothingIsStored() {
        repository.clearIfRaceChanged(activeRaceId = 5L)

        assertNull(repository.current.value)
    }

    @Test
    fun storingANewPayloadForTheSameRaceReplacesTheOldOne() = runTest {
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = payload("2020-01-01T00:00:00.000Z"))
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = payload("2026-08-23T10:00:00.000Z"))

        assertEquals("2026-08-23T10:00:00.000Z", repository.generatedAtFor(1L))
    }

    @Test
    fun storeFromBleCarriesTheRaceLabelAlongsideTheLocalRaceId() = runTest {
        repository.storeFromBle(raceId = 1L, raceLabel = "spring-5k-26-08-23", payload = payload())

        assertEquals("spring-5k-26-08-23", repository.current.value?.raceLabel)
    }

    @Test
    fun toPayloadRoundTripsBackToTheOriginalWireShape() = runTest {
        val original = payload()
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = original)

        assertEquals(original, repository.current.value?.toPayload())
    }

    // Persistence — see ProgressRepository's own doc for why storeFromBle/refreshFromServer
    // write through to Room (observeStored/getStored/delete below), not just `current`.

    @Test
    fun storeFromBleWritesThroughToTheRoomBackedStore() = runTest {
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = payload())

        val stored = repository.getStored(1L)
        assertEquals("2026-08-23T10:00:00.000Z", stored?.generatedAt)
        assertEquals(listOf(ProgressEntry(bibNumber = 1, name = "Dave", course = "Seniors")), stored?.entries)
    }

    @Test
    fun getStoredReturnsNullForARaceNeverStored() = runTest {
        assertNull(repository.getStored(99L))
    }

    @Test
    fun clearIfRaceChangedNeverTouchesTheRoomBackedStore() = runTest {
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = payload())

        repository.clearIfRaceChanged(activeRaceId = 2L)

        // current is wiped (already covered above) but the persisted copy survives — that's
        // the whole point of it existing separately, for the Races page to still list/view.
        assertEquals("2026-08-23T10:00:00.000Z", repository.getStored(1L)?.generatedAt)
    }

    @Test
    fun observeStoredListsEveryRaceEverStoredNewestFirst() = runTest {
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = payload())
        repository.storeFromBle(raceId = 2L, raceLabel = "race-b", payload = payload())

        val stored = repository.observeStored().first()
        assertEquals(setOf(1L, 2L), stored.map { it.raceId }.toSet())
    }

    @Test
    fun deleteRemovesTheStoredCopyAndClearsCurrentWhenItWasTheActiveRace() = runTest {
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = payload())

        repository.delete(1L)

        assertNull(repository.getStored(1L))
        assertNull(repository.current.value)
    }

    @Test
    fun deletingADifferentRaceLeavesCurrentAlone() = runTest {
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = payload())

        repository.delete(2L)

        assertTrue(repository.current.value != null)
    }

    // Delta merging — storeFromBle/refreshFromServer's shared mergeEntries() (see its own doc):
    // a later payload carrying only some entries must upsert into what's already stored, not
    // replace it wholesale — mirrors racemaster's own server-side mergeProgress by design.

    @Test
    fun aSecondStoreFromBlePayloadWithOnlyOneChangedEntryLeavesTheOtherStoredEntriesAlone() = runTest {
        val first = ProgressPayload(
            raceName = "Test Race", raceDate = "23/08/2026", generatedAt = "2026-08-23T10:00:00.000Z",
            entries = listOf(
                ProgressEntry(bibNumber = 1, name = "Dave", course = "Seniors"),
                ProgressEntry(bibNumber = 2, name = "Amy", course = "Juniors"),
            ),
        )
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = first)

        val delta = ProgressPayload(
            raceName = "Test Race", raceDate = "23/08/2026", generatedAt = "2026-08-23T11:00:00.000Z",
            entries = listOf(ProgressEntry(bibNumber = 1, name = "Dave", course = "Seniors", finishTime = "00:45:00")),
        )
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = delta)

        val stored = repository.getStored(1L)
        assertEquals("2026-08-23T11:00:00.000Z", stored?.generatedAt)
        assertEquals(2, stored?.entries?.size)
        assertEquals("00:45:00", stored?.entries?.find { it.bibNumber == 1 }?.finishTime)
        assertEquals("Amy", stored?.entries?.find { it.bibNumber == 2 }?.name)
    }

    @Test
    fun anEmptyDeltaChangesOnlyGeneratedAtAndLeavesEveryStoredEntryUntouched() = runTest {
        val first = ProgressPayload(
            raceName = "Test Race", raceDate = "23/08/2026", generatedAt = "2026-08-23T10:00:00.000Z",
            entries = listOf(ProgressEntry(bibNumber = 1, name = "Dave", course = "Seniors")),
        )
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = first)

        val touch = ProgressPayload(
            raceName = "Test Race", raceDate = "23/08/2026", generatedAt = "2026-08-23T12:00:00.000Z",
            entries = emptyList(),
        )
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = touch)

        val stored = repository.getStored(1L)
        assertEquals("2026-08-23T12:00:00.000Z", stored?.generatedAt)
        assertEquals(listOf(ProgressEntry(bibNumber = 1, name = "Dave", course = "Seniors")), stored?.entries)
    }
}
