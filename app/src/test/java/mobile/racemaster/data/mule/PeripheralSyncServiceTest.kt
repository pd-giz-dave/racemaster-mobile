package mobile.racemaster.data.mule

import org.junit.Assert.assertEquals
import org.junit.Test

class PeripheralSyncServiceTest {

    // sinkConfirmedUuids — the decision behind PeripheralSyncService.markSynced's own green
    // threshold: which uuids in an ack actually count as reaching a genuine sink.

    @Test
    fun aNonSinkAckConfirmsNothingFromItsOwnRecordUuids() {
        val ack = AckPayload(deviceId = "mule-a", recordUuids = listOf("u1", "u2"), isSink = false)

        assertEquals(emptySet<String>(), sinkConfirmedUuids(ack))
    }

    @Test
    fun aSinkAckConfirmsEveryRecordUuidItJustPulled() {
        val ack = AckPayload(deviceId = "racemaster-web", recordUuids = listOf("u1", "u2"), isSink = true)

        assertEquals(setOf("u1", "u2"), sinkConfirmedUuids(ack))
    }

    @Test
    fun sinkConfirmedRecordUuidsAlwaysCountRegardlessOfIsSink() {
        // These are already confirmed by definition — relayed here from further along an N-hop
        // mule chain — so they count whether or not the immediate acker is itself a sink.
        val nonSinkAck = AckPayload(deviceId = "mule-a", recordUuids = emptyList(), isSink = false, sinkConfirmedRecordUuids = listOf("older-1"))
        assertEquals(setOf("older-1"), sinkConfirmedUuids(nonSinkAck))

        val sinkAck = AckPayload(deviceId = "racemaster-web", recordUuids = emptyList(), isSink = true, sinkConfirmedRecordUuids = listOf("older-1"))
        assertEquals(setOf("older-1"), sinkConfirmedUuids(sinkAck))
    }

    @Test
    fun combinesBothSourcesIntoOneDedupedSet() {
        val ack = AckPayload(
            deviceId = "racemaster-web",
            recordUuids = listOf("u1", "shared"),
            isSink = true,
            sinkConfirmedRecordUuids = listOf("older-1", "shared"),
        )

        assertEquals(setOf("u1", "shared", "older-1"), sinkConfirmedUuids(ack))
    }

    @Test
    fun emptyAckConfirmsNothing() {
        val ack = AckPayload(deviceId = "mule-a", recordUuids = emptyList())

        assertEquals(emptySet<String>(), sinkConfirmedUuids(ack))
    }
}
