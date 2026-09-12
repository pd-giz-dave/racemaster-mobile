package mobile.racemaster.data.mule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// refreshFromServer (the HTTP-fetch path) is deliberately not exercised here — constructing a
// live MuleSyncClient for network calls under plain JUnit (no Robolectric in this module, see
// app/build.gradle.kts) isn't something any existing test in this codebase does either (no
// MuleSyncClientTest.kt exists) — this repository's own pure state-management logic (storing,
// looking up, and invalidating by raceId) is what's covered here instead.
class ProgressRepositoryTest {
    private val repository = ProgressRepository(MuleSyncClient())

    private fun payload(generatedAt: String = "2026-08-23T10:00:00.000Z") = ProgressPayload(
        raceName = "Test Race", raceDate = "23/08/2026", generatedAt = generatedAt,
        entries = listOf(ProgressEntry(bibNumber = 1, name = "Dave", course = "Seniors")),
    )

    @Test
    fun storeFromBleThenGeneratedAtForTheSameRaceIdReturnsIt() {
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = payload())

        assertEquals("2026-08-23T10:00:00.000Z", repository.generatedAtFor(1L))
    }

    @Test
    fun generatedAtForADifferentRaceIdReturnsNull() {
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = payload())

        assertNull(repository.generatedAtFor(2L))
    }

    @Test
    fun generatedAtForReturnsNullWhenNothingStoredYet() {
        assertNull(repository.generatedAtFor(1L))
        assertNull(repository.generatedAtFor(null))
    }

    @Test
    fun clearIfRaceChangedWipesStoredProgressWhenTheActiveRaceDiffers() {
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = payload())

        repository.clearIfRaceChanged(activeRaceId = 2L)

        assertNull(repository.generatedAtFor(1L))
        assertEquals(null, repository.current.value)
    }

    @Test
    fun clearIfRaceChangedLeavesStoredProgressWhenTheActiveRaceStillMatches() {
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
    fun storingANewPayloadForTheSameRaceReplacesTheOldOne() {
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = payload("2020-01-01T00:00:00.000Z"))
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = payload("2026-08-23T10:00:00.000Z"))

        assertEquals("2026-08-23T10:00:00.000Z", repository.generatedAtFor(1L))
    }

    @Test
    fun storeFromBleCarriesTheRaceLabelAlongsideTheLocalRaceId() {
        repository.storeFromBle(raceId = 1L, raceLabel = "spring-5k-26-08-23", payload = payload())

        assertEquals("spring-5k-26-08-23", repository.current.value?.raceLabel)
    }

    @Test
    fun toPayloadRoundTripsBackToTheOriginalWireShape() {
        val original = payload()
        repository.storeFromBle(raceId = 1L, raceLabel = "race-a", payload = original)

        assertEquals(original, repository.current.value?.toPayload())
    }
}
