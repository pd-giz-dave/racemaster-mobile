package mobile.racemaster.data.mule

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MulePullClientTest {

    // ackBatches — keeping every single GATT ack write under Android's hard 512-byte cap (see
    // its own doc for the field bug this fixes: an unbatched, ever-growing sinkConfirmedOrigins
    // list eventually throws on every subsequent pull from that source).

    private val json = Json { ignoreUnknownKeys = true }
    private fun encode(payload: AckPayload): String = json.encodeToString(payload)

    private fun origin(vararg lineNumbers: Long, deviceId: String? = null, raceLabel: String? = null) =
        AckedOrigin(originDeviceId = deviceId, originRaceLabel = raceLabel, lineNumbers = lineNumbers.toList())

    @Test
    fun emptyListsProduceNoBatchesAtAll() {
        val batches = ackBatches("mule-a", "witty-warbler", emptyList(), emptyList(), encode = ::encode)

        assertEquals(emptyList<AckPayload>(), batches)
    }

    @Test
    fun smallListsEachFitInOneBatch() {
        val batches = ackBatches("mule-a", "witty-warbler", listOf(origin(1L, 2L)), listOf(origin(10L, 20L)), encode = ::encode)

        assertEquals(2, batches.size)
        assertEquals(listOf(origin(1L, 2L)), batches[0].ackedOrigins)
        assertEquals(emptyList<AckedOrigin>(), batches[0].sinkConfirmedOrigins)
        assertEquals(emptyList<AckedOrigin>(), batches[1].ackedOrigins)
        assertEquals(listOf(origin(10L, 20L)), batches[1].sinkConfirmedOrigins)
    }

    @Test
    fun oneOfEachSkippedWhenItsOwnListIsEmpty() {
        val batches = ackBatches("mule-a", "witty-warbler", listOf(origin(1L)), emptyList(), encode = ::encode)

        assertEquals(1, batches.size)
        assertEquals(listOf(origin(1L)), batches[0].ackedOrigins)
    }

    @Test
    fun aLargeConfirmedListSplitsAcrossMultipleBatchesEachUnderTheCap() {
        // Simulates the exact field failure: many already sink-confirmed lines accumulated for
        // one source, recomputed fresh (and unpruned) on every tick.
        val manyLineNumbers = (1L..1000L).toList()
        val maxBytes = 150

        val batches = ackBatches("mule-a", "witty-warbler", emptyList(), listOf(AckedOrigin(lineNumbers = manyLineNumbers)), maxEncodedBytes = maxBytes, encode = ::encode)

        assertTrue("expected more than one batch for ${manyLineNumbers.size} line numbers capped at $maxBytes bytes", batches.size > 1)
        for (batch in batches) {
            val singleEntry = batch.sinkConfirmedOrigins.singleOrNull()?.lineNumbers?.size == 1
            assertTrue("batch encoded over the cap: ${encode(batch).length}", encode(batch).toByteArray(Charsets.UTF_8).size <= maxBytes || singleEntry)
            assertTrue(batch.ackedOrigins.isEmpty())
        }
        // Every line number survives the split, in order, with none dropped or duplicated —
        // regrouped back into a single logical origin once every batch is flattened.
        assertEquals(manyLineNumbers, batches.flatMap { it.sinkConfirmedOrigins }.flatMap { it.lineNumbers })
    }

    @Test
    fun recordAndConfirmedBatchesNeverMixFieldsInTheSamePayload() {
        val batches = ackBatches("mule-a", "witty-warbler", listOf(origin(1L, 2L, 3L)), listOf(origin(10L, 20L, 30L)), maxEncodedBytes = 90, encode = ::encode)

        for (batch in batches) {
            assertTrue(batch.ackedOrigins.isEmpty() || batch.sinkConfirmedOrigins.isEmpty())
        }
        assertEquals(listOf(1L, 2L, 3L), batches.flatMap { it.ackedOrigins }.flatMap { it.lineNumbers })
        assertEquals(listOf(10L, 20L, 30L), batches.flatMap { it.sinkConfirmedOrigins }.flatMap { it.lineNumbers })
    }

    @Test
    fun everyBatchCarriesTheSameDeviceIdentity() {
        val batches = ackBatches("mule-a", "witty-warbler", listOf(origin(1L)), listOf(origin(10L)), encode = ::encode)

        for (batch in batches) {
            assertEquals("mule-a", batch.deviceId)
            assertEquals("witty-warbler", batch.deviceName)
            assertEquals(false, batch.isSink)
        }
    }

    // A single ack batch can legitimately span more than one origin at once (this device's own
    // race plus a relayed leg, pulled in the same round trip) — ackBatches must keep them as
    // separate AckedOrigin groups, not silently merge two different origins' own line numbers
    // together the way a flat list used to risk.
    @Test
    fun keepsDistinctOriginsSeparateAcrossOneBatch() {
        val batches = ackBatches(
            "mule-a", "witty-warbler",
            listOf(origin(5L), origin(5L, deviceId = "cp2-phone", raceLabel = "race-a")),
            emptyList(),
            encode = ::encode,
        )

        assertEquals(1, batches.size)
        val groups = batches[0].ackedOrigins
        assertEquals(2, groups.size)
        assertEquals(listOf(5L), groups.first { it.originDeviceId == null }.lineNumbers)
        assertEquals(listOf(5L), groups.first { it.originDeviceId == "cp2-phone" }.lineNumbers)
    }

    // computeRequestKey — deterministic, not random, so a repeated ask (same puller, same
    // origin, same since) collapses to the same key a responder can recognize and dedup
    // against. See its own doc for why a random-per-call key would defeat the whole point.

    @Test
    fun sameInputsAlwaysProduceTheSameKey() {
        val key1 = computeRequestKey("mule-a", "leaf-b", "spring-5k", 42L)
        val key2 = computeRequestKey("mule-a", "leaf-b", "spring-5k", 42L)

        assertEquals(key1, key2)
    }

    @Test
    fun differentPullerOriginOrSinceProduceDifferentKeys() {
        val base = computeRequestKey("mule-a", "leaf-b", "spring-5k", 42L)

        assertTrue(base != computeRequestKey("mule-c", "leaf-b", "spring-5k", 42L))
        assertTrue(base != computeRequestKey("mule-a", "leaf-z", "spring-5k", 42L))
        assertTrue(base != computeRequestKey("mule-a", "leaf-b", "autumn-10k", 42L))
        assertTrue(base != computeRequestKey("mule-a", "leaf-b", "spring-5k", 43L))
    }

    @Test
    fun nullOriginIsDistinctFromAnyRealOriginDeviceId() {
        val directPull = computeRequestKey("mule-a", null, null, 42L)
        val relayPullNamedSelf = computeRequestKey("mule-a", "self", "spring-5k", 42L)

        assertTrue(directPull != relayPullNamedSelf)
    }

    @Test
    fun pullRequestEncodesRequestKeyOnTheWire() {
        val request = PullRequest(sinceLineNumber = 5L, requestKey = computeRequestKey("mule-a", null, null, 5L))
        val encoded = json.encodeToString(request)

        val decoded = json.decodeFromString<PullRequest>(encoded)
        assertEquals(request.requestKey, decoded.requestKey)
    }

    // shouldDeliverProgress — the bandwidth-saving gate behind readDeviceInfo's own
    // progressToDeliver/progressRaceLabel piggybacking (mirrors deliverProgress's own decision
    // in the racemaster web app's js/mule-ble.js).

    @Test
    fun deliversWhenRaceMatchesAndGeneratedAtDiffers() {
        assertTrue(shouldDeliverProgress("race-a", "2020-01-01T00:00:00.000Z", "race-a", "2026-08-23T10:00:00.000Z"))
    }

    @Test
    fun deliversWhenRaceMatchesAndPeerHasNeverHeldAnyProgressYet() {
        assertTrue(shouldDeliverProgress("race-a", null, "race-a", "2026-08-23T10:00:00.000Z"))
    }

    @Test
    fun doesNotDeliverWhenThePeerAlreadyHasThisExactGeneratedAt() {
        assertEquals(false, shouldDeliverProgress("race-a", "2026-08-23T10:00:00.000Z", "race-a", "2026-08-23T10:00:00.000Z"))
    }

    @Test
    fun doesNotDeliverWhenTheRaceLabelsDifferRegardlessOfGeneratedAt() {
        assertEquals(false, shouldDeliverProgress("race-b", "2020-01-01T00:00:00.000Z", "race-a", "2026-08-23T10:00:00.000Z"))
        assertEquals(false, shouldDeliverProgress("race-b", null, "race-a", "2026-08-23T10:00:00.000Z"))
    }

    // DeviceInfo must arrive in ONE read response (see MuleGattProfile.REQUESTED_MTU): a value
    // read in pieces was being reassembled into garbage in the field. The largest DeviceInfo this
    // app can produce — server-sanitised names/labels cap at 64 chars — must fit, encoded exactly
    // as PeripheralSyncService encodes it, so a field added (or a label format grown) later fails
    // here instead of on a phone.
    @Test
    fun theLargestPossibleDeviceInfoFitsInOneReadAtTheRequestedMtu() {
        val peripheralJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val worstCase = DeviceInfo(
            deviceId = "305792c1-85bc-4199-9d40-984493f78671",
            raceLabel = "r".repeat(64),
            lastLineNumber = Long.MAX_VALUE,
            deviceName = "d".repeat(64),
            relayCount = Int.MAX_VALUE,
            relayManifestVersion = Int.MAX_VALUE,
            pollIntervalMs = Long.MAX_VALUE,
            progressGeneratedAt = "2026-09-25T14:04:51.115Z",
        )
        val size = peripheralJson.encodeToString(worstCase).toByteArray(Charsets.UTF_8).size

        assertTrue("worst-case DeviceInfo is $size bytes", size <= singleReadLimit(MuleGattProfile.REQUESTED_MTU))
    }

    // decodeDeviceInfo — a read that had to come in pieces and decoded as garbage is named as
    // such, rather than surfacing as a bare JSON error.

    @Test
    fun anOversizedUndecodableReadIsReportedAsSuch() {
        val garbage = ByteArray(251) { 'x'.code.toByte() }

        val e = runCatching { decodeDeviceInfo(garbage, negotiatedMtu = 247, json = json) }.exceptionOrNull()

        assertTrue(e is OversizedReadException)
        assertEquals(251, (e as OversizedReadException).size)
        assertEquals(246, e.singleReadLimit)
    }

    @Test
    fun aSmallUndecodableReadStaysAnOrdinaryDecodeError() {
        val e = runCatching { decodeDeviceInfo("{not json".toByteArray(), negotiatedMtu = 517, json = json) }.exceptionOrNull()

        assertTrue(e !is OversizedReadException && e is kotlinx.serialization.SerializationException)
    }

    @Test
    fun aValidReadDecodesNormally() {
        val bytes = """{"deviceId":"d1","raceLabel":"r","lastLineNumber":3}""".toByteArray()

        assertEquals("d1", decodeDeviceInfo(bytes, negotiatedMtu = null, json = json).deviceId)
    }
}
