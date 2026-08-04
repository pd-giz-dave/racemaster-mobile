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
}
