package mobile.racemaster.data.mule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MuleGattProfileChunkFramingTest {
    @Test
    fun encodeThenDecodeRoundTripsSeqTotalChunksAndPayload() {
        val payload = "hello peer".toByteArray(Charsets.UTF_8)
        val encoded = MuleGattProfile.encodePeerChunk(seq = 3, totalChunks = 7, payload = payload)

        val decoded = MuleGattProfile.decodePeerChunk(encoded)

        assertEquals(3, decoded?.seq)
        assertEquals(7, decoded?.totalChunks)
        assertEquals(payload.toList(), decoded?.payload?.toList())
    }

    @Test
    fun decodeOnTooShortBytesReturnsNull() {
        assertNull(MuleGattProfile.decodePeerChunk(byteArrayOf(0, 1, 2)))
    }

    @Test
    fun decodeOnEmptyPayloadStillRecoversTheHeader() {
        val encoded = MuleGattProfile.encodePeerChunk(seq = 0, totalChunks = 1, payload = ByteArray(0))

        val decoded = MuleGattProfile.decodePeerChunk(encoded)

        assertEquals(0, decoded?.seq)
        assertEquals(1, decoded?.totalChunks)
        assertEquals(0, decoded?.payload?.size)
    }

    @Test
    fun missingPeerChunkSeqsWithNoGapsIsEmpty() {
        val missing = MuleGattProfile.missingPeerChunkSeqs(totalChunks = 4, receivedSeqs = setOf(0, 1, 2, 3))
        assertEquals(emptyList<Int>(), missing)
    }

    @Test
    fun missingPeerChunkSeqsFindsEveryGapRegardlessOfArrivalOrder() {
        // Received out of order, with seq 2 missing entirely — the whole point of framing every
        // chunk with its own seq/totalChunks rather than relying on arrival order.
        val missing = MuleGattProfile.missingPeerChunkSeqs(totalChunks = 5, receivedSeqs = setOf(4, 0, 3, 1))
        assertEquals(listOf(2), missing)
    }

    @Test
    fun missingPeerChunkSeqsWithNothingReceivedListsEverySeq() {
        val missing = MuleGattProfile.missingPeerChunkSeqs(totalChunks = 3, receivedSeqs = emptySet())
        assertEquals(listOf(0, 1, 2), missing)
    }
}
