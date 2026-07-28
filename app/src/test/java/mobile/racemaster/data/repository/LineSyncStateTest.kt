package mobile.racemaster.data.repository

import mobile.racemaster.data.db.entity.LineSyncEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LineSyncStateTest {

    // lineSyncState — the red/orange/green decision itself.

    @Test
    fun syncedAtMillisSetIsAlwaysSyncedRegardlessOfHasAnySync() {
        assertEquals(LineSyncState.SYNCED, lineSyncState(syncedAtMillis = 1_000L, hasAnySync = false))
        assertEquals(LineSyncState.SYNCED, lineSyncState(syncedAtMillis = 1_000L, hasAnySync = true))
    }

    @Test
    fun noSyncedAtMillisButHasAnySyncIsRelayed() {
        assertEquals(LineSyncState.RELAYED, lineSyncState(syncedAtMillis = null, hasAnySync = true))
    }

    @Test
    fun neitherIsNotSynced() {
        assertEquals(LineSyncState.NOT_SYNCED, lineSyncState(syncedAtMillis = null, hasAnySync = false))
    }

    // linesWithAnySync — the "relayed to somebody" set, regardless of isSink.

    @Test
    fun linesWithAnySyncCollectsLineNumbersRegardlessOfIsSink() {
        val rows = listOf(
            LineSyncEntity(raceId = 1L, lineNumber = 1L, targetId = "mule-a", syncedAtMillis = 1_000L, isSink = false),
            LineSyncEntity(raceId = 1L, lineNumber = 2L, targetId = "SERVER", syncedAtMillis = 2_000L, isSink = true),
        )

        assertEquals(setOf(1L, 2L), linesWithAnySync(rows))
    }

    @Test
    fun linesWithAnySyncIsEmptyForNoRows() {
        assertEquals(emptySet<Long>(), linesWithAnySync(emptyList()))
    }
}
