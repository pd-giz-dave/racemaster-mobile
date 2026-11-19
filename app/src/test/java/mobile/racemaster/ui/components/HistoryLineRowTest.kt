package mobile.racemaster.ui.components

import mobile.racemaster.data.repository.LineSyncState
import org.junit.Assert.assertEquals
import org.junit.Test

private fun line(
    lineNumber: Long,
    timestampMillis: Long,
    syncState: LineSyncState = LineSyncState.SYNCED,
    isPing: Boolean = true,
) = HistoryLineDisplay(
    lineNumber = lineNumber,
    splitNumber = null,
    actionLabel = if (isPing) "Ping" else "Split",
    bibNumber = null,
    elapsedMillis = null,
    timestampMillis = timestampMillis,
    note = null,
    syncState = syncState,
    isPing = isPing,
)

class HistoryLineRowTest {

    @Test
    fun singlePingRowIsLeftAlone() {
        val lines = listOf(line(1L, 1_000L))

        val result = collapseConsecutivePings(lines)

        assertEquals(lines, result)
    }

    @Test
    fun runOfSameStatePingsCollapsesIntoOneSpanningFirstToLast() {
        val lines = listOf(
            line(1L, 1_000L, LineSyncState.SYNCED),
            line(2L, 2_000L, LineSyncState.SYNCED),
            line(3L, 3_000L, LineSyncState.SYNCED),
        )

        val result = collapseConsecutivePings(lines)

        assertEquals(1, result.size)
        assertEquals(1L, result[0].lineNumber)
        assertEquals(1_000L, result[0].timestampMillis)
        assertEquals(3L, result[0].collapsedThroughLineNumber)
        assertEquals(3_000L, result[0].collapsedThroughTimestampMillis)
    }

    @Test
    fun differingSyncStateBreaksTheRunIntoSeparateGroups() {
        val lines = listOf(
            line(1L, 1_000L, LineSyncState.NOT_SYNCED),
            line(2L, 2_000L, LineSyncState.SYNCED),
            line(3L, 3_000L, LineSyncState.SYNCED),
        )

        val result = collapseConsecutivePings(lines)

        // Line 1 alone (its own state never recurs adjacently), lines 2-3 collapsed together.
        assertEquals(2, result.size)
        assertEquals(1L, result[0].lineNumber)
        assertEquals(null, result[0].collapsedThroughLineNumber)
        assertEquals(2L, result[1].lineNumber)
        assertEquals(3L, result[1].collapsedThroughLineNumber)
    }

    @Test
    fun nonPingRowInTheMiddleBreaksTheRunEvenWithMatchingState() {
        val lines = listOf(
            line(1L, 1_000L, LineSyncState.SYNCED),
            line(2L, 2_000L, LineSyncState.SYNCED, isPing = false),
            line(3L, 3_000L, LineSyncState.SYNCED),
        )

        val result = collapseConsecutivePings(lines)

        assertEquals(listOf(1L, 2L, 3L), result.map { it.lineNumber })
        assertEquals(null, result[0].collapsedThroughLineNumber)
        assertEquals(null, result[2].collapsedThroughLineNumber)
    }

    @Test
    fun emptyListStaysEmpty() {
        assertEquals(emptyList<HistoryLineDisplay>(), collapseConsecutivePings(emptyList()))
    }
}
