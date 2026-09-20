package mobile.racemaster.data.mule

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeripheralSyncServiceTest {

    // sinkConfirmedOrigins — the decision behind PeripheralSyncService.markSynced's own green
    // threshold: which origin-grouped line numbers in an ack actually count as reaching a
    // genuine sink. Grouped by origin (not a flat set) so a mixed-origin ack — this device's own
    // race plus zero or more relayed legs, all pulled in one round trip — can still be routed to
    // the correct table/source afterward; see AckedOrigin's own doc.

    @Test
    fun aNonSinkAckConfirmsNothingFromItsOwnAckedOrigins() {
        val ack = AckPayload(deviceId = "mule-a", ackedOrigins = listOf(AckedOrigin(lineNumbers = listOf(1L, 2L))), isSink = false)

        assertEquals(emptyList<AckedOrigin>(), sinkConfirmedOrigins(ack))
    }

    @Test
    fun aSinkAckConfirmsEveryLineItJustPulled() {
        val ack = AckPayload(deviceId = "racemaster-web", ackedOrigins = listOf(AckedOrigin(lineNumbers = listOf(1L, 2L))), isSink = true)

        assertEquals(listOf(AckedOrigin(lineNumbers = listOf(1L, 2L))), sinkConfirmedOrigins(ack))
    }

    @Test
    fun sinkConfirmedOriginsAlwaysCountRegardlessOfIsSink() {
        // These are already confirmed by definition — relayed here from further along an N-hop
        // mule chain — so they count whether or not the immediate acker is itself a sink.
        val nonSinkAck = AckPayload(deviceId = "mule-a", ackedOrigins = emptyList(), isSink = false, sinkConfirmedOrigins = listOf(AckedOrigin(lineNumbers = listOf(99L))))
        assertEquals(listOf(AckedOrigin(lineNumbers = listOf(99L))), sinkConfirmedOrigins(nonSinkAck))

        val sinkAck = AckPayload(deviceId = "racemaster-web", ackedOrigins = emptyList(), isSink = true, sinkConfirmedOrigins = listOf(AckedOrigin(lineNumbers = listOf(99L))))
        assertEquals(listOf(AckedOrigin(lineNumbers = listOf(99L))), sinkConfirmedOrigins(sinkAck))
    }

    @Test
    fun combinesBothSourcesIntoOneDedupedGroupForTheSameOrigin() {
        val ack = AckPayload(
            deviceId = "racemaster-web",
            ackedOrigins = listOf(AckedOrigin(lineNumbers = listOf(1L, 5L))),
            isSink = true,
            sinkConfirmedOrigins = listOf(AckedOrigin(lineNumbers = listOf(99L, 5L))),
        )

        assertEquals(listOf(AckedOrigin(lineNumbers = listOf(1L, 5L, 99L))), sinkConfirmedOrigins(ack))
    }

    @Test
    fun emptyAckConfirmsNothing() {
        val ack = AckPayload(deviceId = "mule-a", ackedOrigins = emptyList())

        assertEquals(emptyList<AckedOrigin>(), sinkConfirmedOrigins(ack))
    }

    // The correctness property AckedOrigin grouping exists for: a single ack spanning this
    // device's own race (null origin) AND a genuinely different relayed origin at once must
    // route each to the right place afterward, not get flattened together the way a bare
    // line-number list would (CP1's own line 5 and CP2's own line 5 are unrelated records).
    @Test
    fun keepsDifferentOriginsAsSeparateGroupsRatherThanMergingThem() {
        val ack = AckPayload(
            deviceId = "mule-a",
            ackedOrigins = listOf(
                AckedOrigin(originDeviceId = null, originRaceLabel = null, lineNumbers = listOf(5L)),
                AckedOrigin(originDeviceId = "cp2-phone", originRaceLabel = "race-a", lineNumbers = listOf(5L)),
            ),
            isSink = true,
        )

        val confirmed = sinkConfirmedOrigins(ack)

        assertEquals(2, confirmed.size)
        assertEquals(listOf(5L), confirmed.first { it.originDeviceId == null }.lineNumbers)
        assertEquals(listOf(5L), confirmed.first { it.originDeviceId == "cp2-phone" }.lineNumbers)
    }

    // cacheAfterAnswering — the two purely defensive backstops (an absolute age ceiling and a
    // hard size cap) behind recentResponses' real correctness mechanism, which is
    // invalidate-on-change (observeServingState/observeRelayManifest clearing the whole cache
    // the moment the underlying data changes, not exercised here since it needs no dedicated
    // test of its own beyond "the cache is empty afterward").

    @Test
    fun aFreshKeyIsAddedToAnEmptyCache() {
        val result = cacheAfterAnswering(emptyMap(), "key-1", "payload", nowMillis = 1_000L, maxEntries = 64, maxAgeMillis = 60_000L)

        assertEquals(mapOf("key-1" to CachedResponse("payload", 1_000L)), result)
    }

    @Test
    fun entriesOlderThanTheAgeCeilingAreDroppedOnTheNextWrite() {
        val cache = mapOf("stale" to CachedResponse("old-payload", computedAtMillis = 0L))

        val result = cacheAfterAnswering(cache, "key-2", "payload", nowMillis = 60_001L, maxEntries = 64, maxAgeMillis = 60_000L)

        assertEquals(setOf("key-2"), result.keys)
    }

    @Test
    fun entriesWithinTheAgeCeilingSurvive() {
        val cache = mapOf("recent" to CachedResponse("payload", computedAtMillis = 1_000L))

        val result = cacheAfterAnswering(cache, "key-2", "payload-2", nowMillis = 1_500L, maxEntries = 64, maxAgeMillis = 60_000L)

        assertEquals(setOf("recent", "key-2"), result.keys)
    }

    @Test
    fun exceedingTheSizeCapEvictsTheOldestEntriesFirst() {
        val cache = mapOf(
            "oldest" to CachedResponse("p1", computedAtMillis = 1_000L),
            "middle" to CachedResponse("p2", computedAtMillis = 2_000L),
        )

        val result = cacheAfterAnswering(cache, "newest", "p3", nowMillis = 3_000L, maxEntries = 2, maxAgeMillis = 60_000L)

        assertEquals(setOf("middle", "newest"), result.keys)
    }

    // isProgressTerminatorChunk / reassembleProgressChunks — the receive-side framing for a
    // browser-delivered progress payload (the mirror image of MuleGattProfile's own DATA notify
    // stream framing, just applied to inbound writes to PROGRESS_CHARACTERISTIC_UUID instead).

    @Test
    fun aSingleZeroByteIsTheTerminator() {
        assertTrue(isProgressTerminatorChunk(byteArrayOf(0)))
    }

    @Test
    fun anyOtherSingleByteIsNotTheTerminator() {
        assertFalse(isProgressTerminatorChunk(byteArrayOf(1)))
        assertFalse(isProgressTerminatorChunk(byteArrayOf(0x41)))
    }

    @Test
    fun aMultiByteChunkIsNeverTheTerminatorEvenIfItStartsWithZero() {
        assertFalse(isProgressTerminatorChunk(byteArrayOf(0, 1)))
    }

    @Test
    fun anEmptyChunkIsNotTheTerminator() {
        assertFalse(isProgressTerminatorChunk(ByteArray(0)))
    }

    @Test
    fun reassembleProgressChunksConcatenatesInOrder() {
        val result = reassembleProgressChunks(listOf(byteArrayOf(1, 2), byteArrayOf(3), byteArrayOf(4, 5, 6)))

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), result)
    }

    @Test
    fun reassembleProgressChunksOfAnEmptyListIsAnEmptyArray() {
        assertArrayEquals(ByteArray(0), reassembleProgressChunks(emptyList()))
    }
}
