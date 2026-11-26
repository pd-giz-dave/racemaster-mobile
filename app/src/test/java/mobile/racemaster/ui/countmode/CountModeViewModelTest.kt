package mobile.racemaster.ui.countmode

import mobile.racemaster.data.db.entity.HistoryMode
import mobile.racemaster.data.db.entity.PeerSeenRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Test

// peerActivityFrom — TODO.md "need to be able to see the peer lines pulled from nearby devices
// in the history (as a diagnostic aid)" — pulled out as a pure function so it's directly
// testable without standing up the ViewModel's full repository/Flow graph, same reasoning
// RaceHistoryViewModelTest's own isSkippedAsStale coverage gives.
class CountModeViewModelTest {

    private fun peerRow(action: String, beaconNumber: Int?, bibNumber: Int?, timestampMillis: Long, originDeviceName: String = "brave-reef") =
        PeerSeenRecordEntity(
            raceGroupId = "g",
            mode = HistoryMode.COUNT,
            originDeviceId = "peer-1",
            originDeviceName = originDeviceName,
            lineNumber = timestampMillis,
            action = action,
            beaconNumber = beaconNumber,
            bibNumber = bibNumber,
            timestampMillis = timestampMillis,
        )

    @Test
    fun ordersNewestFirst() {
        val rows = listOf(
            peerRow("COUNT_SEEN", beaconNumber = 1, bibNumber = null, timestampMillis = 100),
            peerRow("COUNT_SEEN", beaconNumber = 2, bibNumber = null, timestampMillis = 300),
            peerRow("COUNT_SEEN", beaconNumber = 3, bibNumber = null, timestampMillis = 200),
        )
        assertEquals(listOf(300L, 200L, 100L), peerActivityFrom(rows).map { it.timestampMillis })
    }

    @Test
    fun aRealHistoryActionIsShownWithItsDisplayLabel() {
        // "CountEjected" is the real wire string (HistoryAction.toServerAction()'s own spelling)
        // — not "COUNT_EJECTED", the enum's .name, which this row's action column never actually
        // holds (see toHistoryAction's own doc).
        val line = peerActivityFrom(listOf(peerRow("CountEjected", beaconNumber = 7, bibNumber = 801, timestampMillis = 100))).single()
        assertEquals("Ejected", line.actionLabel)
        assertEquals(7, line.beaconNumber)
        assertEquals(801, line.bibNumber)
        assertEquals("brave-reef", line.originDeviceName)
    }

    @Test
    fun anUnrecognizedWireActionStringFallsBackToIgnoreRatherThanThrowing() {
        // Matches wireActionToHistoryAction's own documented fallback (SyncRecordMapping.kt) —
        // row.action is always a wire string ("CountSeen", not "COUNT_SEEN"), never the enum's
        // own .name, so this exercises toHistoryAction()'s unrecognized-value branch, not a
        // HistoryAction.valueOf failure.
        val line = peerActivityFrom(listOf(peerRow("NOT_A_REAL_ACTION", beaconNumber = null, bibNumber = null, timestampMillis = 100))).single()
        assertEquals("Ignore", line.actionLabel)
    }

    @Test
    fun emptyInputProducesEmptyOutput() {
        assertEquals(emptyList<PeerActivityLineUi>(), peerActivityFrom(emptyList()))
    }
}
