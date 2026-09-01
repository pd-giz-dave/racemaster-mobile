package mobile.racemaster.ui.racehistory

import kotlin.time.Duration.Companion.days
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceHistoryViewModelTest {

    // isSkippedAsStale — mirrors MuleRepository.pushToServer's own age cutoff, pulled out as a
    // pure function so it's directly testable without standing up the ViewModel's full
    // repository/Flow graph.

    @Test
    fun noActivitySignalAtAllIsNeverStale() {
        // null — a Mule-pulled label never seen, or a local race with no history yet — is a
        // distinct, unrelated state, not "too old".
        assertFalse(isSkippedAsStale(lastTouchedAtMillis = null, maxAgeDays = 2))
    }

    @Test
    fun touchedWithinTheWindowIsNotStale() {
        val now = System.currentTimeMillis()

        assertFalse(isSkippedAsStale(now - 1.days.inWholeMilliseconds, maxAgeDays = 2))
    }

    @Test
    fun untouchedPastTheWindowIsStale() {
        val now = System.currentTimeMillis()

        assertTrue(isSkippedAsStale(now - 3.days.inWholeMilliseconds, maxAgeDays = 2))
    }

    // staleDeletionSummary/isStaleAndDeletable — the "Delete stale" bulk-delete's own gating,
    // pulled out as pure functions for the same reason isSkippedAsStale is.

    private fun staleLocalRace(id: Long = 1, isActive: Boolean = false) = HistoryItemUi.LocalRace(
        id = id,
        label = "race-$id",
        createdByDeviceName = "self",
        isActive = isActive,
        activeModeLabels = if (isActive) listOf("Time") else emptyList(),
        serverSyncSkippedAsStale = true,
    )

    private fun freshLocalRace(id: Long = 1) = staleLocalRace(id).copy(serverSyncSkippedAsStale = false)

    private fun staleMuleSource(sourceDeviceId: String = "device-1") = HistoryItemUi.MuleSource(
        raceLabel = "race",
        sourceDeviceId = sourceDeviceId,
        deviceName = "peer",
        serverSyncSkippedAsStale = true,
    )

    private fun freshMuleSource(sourceDeviceId: String = "device-1") = staleMuleSource(sourceDeviceId).copy(serverSyncSkippedAsStale = false)

    @Test
    fun staleInactiveLocalRaceIsDeletable() {
        assertTrue(staleLocalRace().isStaleAndDeletable())
    }

    @Test
    fun staleActiveLocalRaceIsNotDeletable() {
        // Same backstop RaceRepository.deleteRace already enforces for single-item delete —
        // bulk delete must never sweep up a race someone is still recording.
        assertFalse(staleLocalRace(isActive = true).isStaleAndDeletable())
    }

    @Test
    fun freshLocalRaceIsNotDeletable() {
        assertFalse(freshLocalRace().isStaleAndDeletable())
    }

    @Test
    fun staleMuleSourceIsDeletable() {
        assertTrue(staleMuleSource().isStaleAndDeletable())
    }

    @Test
    fun freshMuleSourceIsNotDeletable() {
        assertFalse(freshMuleSource().isStaleAndDeletable())
    }

    @Test
    fun summaryCountsEachKindSeparately() {
        val items = listOf(
            staleLocalRace(id = 1),
            staleLocalRace(id = 2, isActive = true),
            freshLocalRace(id = 3),
            staleMuleSource("a"),
            staleMuleSource("b"),
            freshMuleSource("c"),
        )

        val summary = staleDeletionSummary(items)

        assertEquals(1, summary.localRaceCount)
        assertEquals(2, summary.muleSourceCount)
        assertEquals(3, summary.total)
    }

    @Test
    fun summaryIsAllZeroWhenNothingIsStale() {
        val summary = staleDeletionSummary(listOf(freshLocalRace(), freshMuleSource()))

        assertEquals(0, summary.total)
    }
}
