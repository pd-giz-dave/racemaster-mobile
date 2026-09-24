package mobile.racemaster.data.mule

import kotlinx.serialization.json.Json
import mobile.racemaster.data.db.entity.PulledRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuleRepositoryTest {

    private fun timeRecord(lineNumber: Long) = SyncRecord(
        action = "Finish",
        bibNumber = null,
        splitTime = 90,
        splitNumber = 1,
        lineNumber = lineNumber,
        note = null,
        timestampMillis = 0L,
    )

    private fun bibRecord(lineNumber: Long) = SyncRecord(
        action = "Finish",
        bibNumber = 101,
        splitTime = null,
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

    private fun newRaceRecord(lineNumber: Long) = SyncRecord(
        action = "NewRace",
        bibNumber = null,
        splitTime = null,
        splitNumber = null,
        lineNumber = lineNumber,
        note = null,
        timestampMillis = 0L,
    )

    @Test
    fun aDeviceNamedInStartingFreshIgnoresTheStaleServerStatusAndSendsEverything() {
        // The server's own status reports this device far ahead (line 8) — stale, from a
        // different, since-superseded race that happened to reuse the same label. Without the
        // NewRace exception, every one of these low-numbered fresh records would be filtered
        // out as "already sent" and the new race would never reach the server at all.
        val byDevice = mapOf("quiet-thicket" to listOf(newRaceRecord(1), timeRecord(2), timeRecord(3)))
        val status = mapOf("quiet-thicket" to 8L)

        val due = recordsDueForDevices(byDevice, status, startingFreshDevices = setOf("quiet-thicket"))

        assertEquals(setOf(1L, 2L, 3L), due.getValue("quiet-thicket").map { it.lineNumber }.toSet())
    }

    @Test
    fun aNewRaceMarkerNoLongerNamedInStartingFreshUsesTheOrdinaryDeltaFilterEvenIfStillPresentInTheFullHistory() {
        // Regression test for a real, confirmed bug: pushToServer's own `selfRecords` is always
        // this device's COMPLETE local history (fetched from line 0 on every single call), so a
        // NewRace marker written once stays present in it for the entire lifetime of the race —
        // not just its first push. Bypassing on the marker's mere presence (rather than whether
        // pushToServer's own caller still considers it "starting fresh", i.e. genuinely
        // unconfirmed) meant every subsequent push kept sending, and therefore kept "just
        // sending" without ever confirming, this device's ENTIRE history forever — so it could
        // never be marked synced locally even though the server genuinely already had all of it.
        // Once the caller stops naming this device (the real signal, computed from local
        // syncedAtMillis — see pushToServer's own doc), the ordinary per-line delta filter must
        // apply, exactly as if no NewRace marker were involved at all.
        val byDevice = mapOf("quiet-thicket" to listOf(newRaceRecord(1), timeRecord(2), timeRecord(3)))
        val status = mapOf("quiet-thicket" to 2L)

        val due = recordsDueForDevices(byDevice, status, startingFreshDevices = emptySet())

        assertEquals(listOf(3L), due.getValue("quiet-thicket").map { it.lineNumber })
    }

    // decodeSyncRecord — a row whose payloadJson no longer matches SyncRecord's current shape
    // must be dropped, not thrown, so one bad row can't take down a whole push/relay-serve
    // attempt (see its own doc). Shared by pushToServer's per-device decode and
    // relayedRecordsSince.

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun aRowWithValidPayloadDecodesToTheMatchingRecord() {
        val row = PulledRecordEntity(
            sourceDeviceId = "device-a",
            sourceRaceLabel = "race-a",
            lineNumber = 1,
            payloadJson = json.encodeToString(timeRecord(1)),
            pulledAtMillis = 0L,
        )

        assertEquals(timeRecord(1), decodeSyncRecord(row, json))
    }

    // The unparseable-payload branch also logs via android.util.Log, which isn't available in
    // a plain JVM unit test (no Robolectric/Log mocking set up in this project) — that drop-not-
    // throw behavior is a one-line runCatching/getOrNull, visible directly in the implementation.

    // relabeledSourceLabels — the mule-side cleanup after a peer is adopted into a new race label
    // (see MuleRepository.storePulledRecords): only the old-label copy of the SAME NewRace marker
    // (same lineNumber and timestamp) is treated as relabeled, never a genuinely different race.

    private fun newRace(timestampMillis: Long) = SyncRecord(
        action = "NewRace", bibNumber = null, splitTime = null, splitNumber = null,
        lineNumber = 1, note = null, timestampMillis = timestampMillis,
    )

    private fun heldRow(label: String, record: SyncRecord) = PulledRecordEntity(
        sourceDeviceId = "device-a", sourceRaceLabel = label, lineNumber = record.lineNumber,
        payloadJson = json.encodeToString(record), pulledAtMillis = 0L,
    )

    @Test
    fun theSameNewRaceMarkerUnderAnOldLabelIsReportedAsRelabeled() {
        val candidates = listOf(heldRow("unknown-26-09-23", newRace(1_000L)))

        assertEquals(listOf("unknown-26-09-23"), relabeledSourceLabels(candidates, newRace(1_000L), json))
    }

    @Test
    fun aDifferentRaceStartingAtTheSameLineIsNotRelabeled() {
        val candidates = listOf(
            heldRow("last-year", newRace(5L)),
            heldRow("not-a-marker", timeRecord(1)),
        )

        assertTrue(relabeledSourceLabels(candidates, newRace(1_000L), json).isEmpty())
    }
}
