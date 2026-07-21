package mobile.racemaster.data.mule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuleRepositoryTest {

    private fun timeRecord(lineNumber: Long) = SyncRecord(
        recordUuid = "time-$lineNumber",
        action = "Finish",
        number = null,
        time = "00:01:30.00",
        splitNumber = 1,
        lineNumber = lineNumber,
        note = null,
        timestampMillis = 0L,
    )

    private fun bibRecord(lineNumber: Long) = SyncRecord(
        recordUuid = "bib-$lineNumber",
        action = "Finish",
        number = 101,
        time = null,
        splitNumber = 1,
        lineNumber = lineNumber,
        note = null,
        timestampMillis = 0L,
    )

    // recordsDueForDevices — SyncRecord itself carries no deviceName (each device's records
    // arrive already grouped by the caller), and no more Bibs/Time split: a device's Time and
    // Bibs lines are delta-filtered together by lineNumber alone, same as the server's own flat
    // per-device file now does.

    @Test
    fun withNoStoredStatusEverythingIsSentForThatDevice() {
        val byDevice = mapOf("quiet-thicket" to listOf(timeRecord(1), bibRecord(2)))

        val due = recordsDueForDevices(byDevice, status = emptyMap())

        assertEquals(setOf(1L, 2L), due.getValue("quiet-thicket").map { it.lineNumber }.toSet())
    }

    @Test
    fun onlyTheDeltaPastTheStoredLineNumberIsSent() {
        val byDevice = mapOf("quiet-thicket" to listOf(timeRecord(1), bibRecord(2), timeRecord(3)))
        val status = mapOf("quiet-thicket" to 1L)

        val due = recordsDueForDevices(byDevice, status)

        assertEquals(setOf(2L, 3L), due.getValue("quiet-thicket").map { it.lineNumber }.toSet())
    }

    @Test
    fun timeAndBibsLinesForTheSameDeviceShareOneCutoff() {
        // No separate category cursor anymore — a device's Time and Bibs lines are one
        // chronological sequence, delta-filtered by lineNumber alone.
        val byDevice = mapOf("quiet-thicket" to listOf(timeRecord(5), bibRecord(6)))
        val status = mapOf("quiet-thicket" to 5L)

        val due = recordsDueForDevices(byDevice, status)

        assertEquals(listOf(6L), due.getValue("quiet-thicket").map { it.lineNumber })
    }

    @Test
    fun cutoffsAreIndependentPerDevice() {
        val byDevice = mapOf(
            "device-a" to listOf(timeRecord(1)),
            "device-b" to listOf(timeRecord(1)),
        )
        val status = mapOf("device-a" to 1L)

        val due = recordsDueForDevices(byDevice, status)

        // device-a is already past line 1 (nothing new, dropped entirely); device-b has no
        // stored status, so its line 1 is still new.
        assertEquals(setOf("device-b"), due.keys)
    }

    @Test
    fun aDeviceWithNothingDueIsAbsentFromTheResult() {
        val byDevice = mapOf("quiet-thicket" to listOf(timeRecord(1)))
        val status = mapOf("quiet-thicket" to 1L)

        val due = recordsDueForDevices(byDevice, status)

        assertTrue(due.isEmpty())
    }
}
