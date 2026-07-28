package mobile.racemaster.data.mule

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MulePullClientTest {

    // ackBatches — keeping every single GATT ack write under Android's hard 512-byte cap (see
    // its own doc for the field bug this fixes: an unbatched, ever-growing sinkConfirmedRecordUuids
    // list eventually throws on every subsequent pull from that source).

    private val json = Json { ignoreUnknownKeys = true }
    private fun encode(payload: AckPayload): String = json.encodeToString(payload)

    @Test
    fun emptyListsProduceNoBatchesAtAll() {
        val batches = ackBatches("mule-a", "witty-warbler", emptyList(), emptyList(), encode = ::encode)

        assertEquals(emptyList<AckPayload>(), batches)
    }

    @Test
    fun smallListsEachFitInOneBatch() {
        val batches = ackBatches("mule-a", "witty-warbler", listOf("r1", "r2"), listOf("c1", "c2"), encode = ::encode)

        assertEquals(2, batches.size)
        assertEquals(listOf("r1", "r2"), batches[0].recordUuids)
        assertEquals(emptyList<String>(), batches[0].sinkConfirmedRecordUuids)
        assertEquals(emptyList<String>(), batches[1].recordUuids)
        assertEquals(listOf("c1", "c2"), batches[1].sinkConfirmedRecordUuids)
    }

    @Test
    fun oneOfEachSkippedWhenItsOwnListIsEmpty() {
        val batches = ackBatches("mule-a", "witty-warbler", listOf("r1"), emptyList(), encode = ::encode)

        assertEquals(1, batches.size)
        assertEquals(listOf("r1"), batches[0].recordUuids)
    }

    @Test
    fun aLargeConfirmedListSplitsAcrossMultipleBatchesEachUnderTheCap() {
        // Simulates the exact field failure: many already sink-confirmed uuids accumulated for
        // one source, recomputed fresh (and unpruned) on every tick.
        val manyUuids = (1..200).map { "550e8400-e29b-41d4-a716-4466554400%02d".format(it % 100) }
        val maxBytes = 200

        val batches = ackBatches("mule-a", "witty-warbler", emptyList(), manyUuids, maxEncodedBytes = maxBytes, encode = ::encode)

        assertTrue("expected more than one batch for $manyUuids uuids capped at $maxBytes bytes", batches.size > 1)
        for (batch in batches) {
            assertTrue("batch encoded over the cap: ${encode(batch).length}", encode(batch).toByteArray(Charsets.UTF_8).size <= maxBytes || batch.sinkConfirmedRecordUuids.size == 1)
            assertTrue(batch.recordUuids.isEmpty())
        }
        // Every uuid survives the split, in order, with none dropped or duplicated.
        assertEquals(manyUuids, batches.flatMap { it.sinkConfirmedRecordUuids })
    }

    @Test
    fun recordAndConfirmedBatchesNeverMixFieldsInTheSamePayload() {
        val batches = ackBatches("mule-a", "witty-warbler", listOf("r1", "r2", "r3"), listOf("c1", "c2", "c3"), maxEncodedBytes = 90, encode = ::encode)

        for (batch in batches) {
            assertTrue(batch.recordUuids.isEmpty() || batch.sinkConfirmedRecordUuids.isEmpty())
        }
        assertEquals(listOf("r1", "r2", "r3"), batches.flatMap { it.recordUuids })
        assertEquals(listOf("c1", "c2", "c3"), batches.flatMap { it.sinkConfirmedRecordUuids })
    }

    @Test
    fun everyBatchCarriesTheSameDeviceIdentity() {
        val batches = ackBatches("mule-a", "witty-warbler", listOf("r1"), listOf("c1"), encode = ::encode)

        for (batch in batches) {
            assertEquals("mule-a", batch.deviceId)
            assertEquals("witty-warbler", batch.deviceName)
            assertEquals(false, batch.isSink)
        }
    }
}
