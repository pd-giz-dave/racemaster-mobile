package mobile.racemaster.data.mule

import kotlinx.serialization.json.Json
import mobile.racemaster.data.settings.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MuleGattProfileTest {
    private val json = Json { ignoreUnknownKeys = true }

    // encodeAdvertisedIdentity/decodeAdvertisedIdentity — the scan-response payload
    // MuleSyncEngine's shouldConnect gate relies on to decide whether a real GATT connect is
    // even worth attempting. Must round-trip exactly for a normal name, must never throw (only
    // ever return null) for anything malformed, and must always stay inside the legacy 31-byte
    // scan-response budget even for a pathologically long name.

    @Test
    fun roundTripsAnOrdinaryNameAndCounter() {
        // "hedgehog" is 8 bytes — within ADVERTISED_NAME_MAX_BYTES (10), so this round-trips
        // untruncated; truncation itself is covered by the tests below.
        val encoded = MuleGattProfile.encodeAdvertisedIdentity(
            lastLineNumber = 42L, deviceId = "device-a", deviceName = "hedgehog",
        )

        val decoded = MuleGattProfile.decodeAdvertisedIdentity(encoded)

        assertEquals(
            MuleGattProfile.AdvertisedIdentity(42L, MuleGattProfile.shortDeviceId("device-a"), "hedgehog"),
            decoded,
        )
    }

    @Test
    fun neverExceedsTheManufacturerDataByteBudgetEvenForAPathologicallyLongName() {
        val encoded = MuleGattProfile.encodeAdvertisedIdentity(
            lastLineNumber = 999L,
            deviceId = "device-a",
            deviceName = "a".repeat(500),
        )

        // 4 bytes of manufacturer-data overhead (2-byte AD length+type header + 2-byte company
        // ID) are added by the platform, not by us — our own bytes must leave room for those
        // within the legacy 31-byte scan-response payload cap.
        assertTrue("encoded payload was ${encoded.size} bytes", encoded.size <= 31 - 4)
    }

    @Test
    fun truncatesOnACodepointBoundaryRatherThanSplittingAMultiByteCharacter() {
        // Each "e" here is a 3-byte UTF-8 codepoint (U+1F98D-adjacent BMP stand-in avoided —
        // use a real multi-byte char: "é" is 2 bytes in UTF-8).
        val longMultiByteName = "é".repeat(30)

        val encoded = MuleGattProfile.encodeAdvertisedIdentity(
            lastLineNumber = 1L, deviceId = "device-a", deviceName = longMultiByteName,
        )
        val decoded = MuleGattProfile.decodeAdvertisedIdentity(encoded)

        assertTrue(decoded != null)
        // Must decode as valid UTF-8 (would throw/produce replacement chars if a multi-byte
        // sequence had been split) and never exceed the byte budget.
        assertTrue(decoded!!.deviceName.encodeToByteArray().size <= MuleGattProfile.ADVERTISED_NAME_MAX_BYTES)
        assertTrue(decoded.deviceName.all { it == 'é' })
    }

    @Test
    fun decodeReturnsNullForNullBytes() {
        assertNull(MuleGattProfile.decodeAdvertisedIdentity(null))
    }

    @Test
    fun decodeReturnsNullForWrongMagic() {
        val encoded = MuleGattProfile.encodeAdvertisedIdentity(1L, "device-a", "name")
        encoded[0] = 0x00

        assertNull(MuleGattProfile.decodeAdvertisedIdentity(encoded))
    }

    @Test
    fun decodeReturnsNullForAnUnrecognizedFutureFormatVersion() {
        // Simulates an old-build requester talking to a peer running a future wire format —
        // must degrade to "unknown," not throw or misinterpret the bytes.
        val encoded = MuleGattProfile.encodeAdvertisedIdentity(1L, "device-a", "name")
        encoded[MuleGattProfile.ADVERTISING_MAGIC.size] = (MuleGattProfile.ADVERTISING_FORMAT_VERSION + 1).toByte()

        assertNull(MuleGattProfile.decodeAdvertisedIdentity(encoded))
    }

    @Test
    fun decodeReturnsNullForATruncatedArray() {
        val encoded = MuleGattProfile.encodeAdvertisedIdentity(1L, "device-a", "name")

        assertNull(MuleGattProfile.decodeAdvertisedIdentity(encoded.copyOfRange(0, 3)))
        assertNull(MuleGattProfile.decodeAdvertisedIdentity(ByteArray(0)))
    }

    @Test
    fun decodeReturnsNullWhenDeclaredNameLengthExceedsWhatsActuallyThere() {
        val encoded = MuleGattProfile.encodeAdvertisedIdentity(1L, "device-a", "name")
        // Corrupt the nameLen byte (right after magic+version+mode+counter+shortDeviceId) to
        // claim more bytes than actually follow.
        val nameLenIndex = MuleGattProfile.ADVERTISING_MAGIC.size + 1 + 1 + 4 + MuleGattProfile.SHORT_DEVICE_ID_BYTES
        encoded[nameLenIndex] = 100

        assertNull(MuleGattProfile.decodeAdvertisedIdentity(encoded))
    }

    @Test
    fun encodesAnEmptyNameFine() {
        val encoded = MuleGattProfile.encodeAdvertisedIdentity(0L, "device-a", "")

        assertEquals(
            MuleGattProfile.AdvertisedIdentity(0L, MuleGattProfile.shortDeviceId("device-a"), ""),
            MuleGattProfile.decodeAdvertisedIdentity(encoded),
        )
    }

    // shortDeviceId — the fingerprint that lets MuleSyncEngine recognize the same phone across
    // a BLE address rotation without connecting first (see its own doc on the phone side).

    @Test
    fun shortDeviceIdIsDeterministicForTheSameDeviceId() {
        assertEquals(MuleGattProfile.shortDeviceId("device-a"), MuleGattProfile.shortDeviceId("device-a"))
    }

    @Test
    fun shortDeviceIdDiffersForDifferentDeviceIds() {
        assertTrue(MuleGattProfile.shortDeviceId("device-a") != MuleGattProfile.shortDeviceId("device-b"))
    }

    @Test
    fun roundTripsTheShortDeviceIdAlongsideEverythingElse() {
        val encoded = MuleGattProfile.encodeAdvertisedIdentity(1L, "some-real-uuid-looking-id", "name")

        val decoded = MuleGattProfile.decodeAdvertisedIdentity(encoded)

        assertEquals(MuleGattProfile.shortDeviceId("some-real-uuid-looking-id"), decoded!!.shortDeviceId)
    }

    // mode — this device's own recording mode at advertise time, legacy/informational only (see
    // AdvertisedIdentity's own doc for why no picker filter relies on it anymore) — still must
    // round-trip exactly, since it sits at a fixed offset in the payload other fields are read
    // relative to.

    @Test
    fun roundTripsEachModeAlongsideNameAndCounter() {
        for (mode in AppMode.entries) {
            val encoded = MuleGattProfile.encodeAdvertisedIdentity(7L, "device-a", "Phone One", mode)
            val decoded = MuleGattProfile.decodeAdvertisedIdentity(encoded)

            assertEquals(
                MuleGattProfile.AdvertisedIdentity(7L, MuleGattProfile.shortDeviceId("device-a"), "Phone One", mode),
                decoded,
            )
        }
    }

    @Test
    fun omittingModeEncodesAndDecodesAsNull() {
        val encoded = MuleGattProfile.encodeAdvertisedIdentity(1L, "device-a", "name")

        assertEquals(
            MuleGattProfile.AdvertisedIdentity(1L, MuleGattProfile.shortDeviceId("device-a"), "name", null),
            MuleGattProfile.decodeAdvertisedIdentity(encoded),
        )
    }

    @Test
    fun decodeTreatsAnUnrecognizedModeByteAsNullRatherThanFailingTheWholePayload() {
        val encoded = MuleGattProfile.encodeAdvertisedIdentity(1L, "device-a", "name", AppMode.TIME)
        // Corrupt the mode byte (right after magic+version) to a value no build has ever used —
        // simulates a newer build's mode reaching an older decoder. Must still decode
        // everything else fine, not reject the whole payload the way an unrecognized
        // ADVERTISING_FORMAT_VERSION does.
        val modeIndex = MuleGattProfile.ADVERTISING_MAGIC.size + 1
        encoded[modeIndex] = 99

        assertEquals(
            MuleGattProfile.AdvertisedIdentity(1L, MuleGattProfile.shortDeviceId("device-a"), "name", null),
            MuleGattProfile.decodeAdvertisedIdentity(encoded),
        )
    }

    // ProgressPayload/ProgressEntry — the racemaster web app's progress.json wire shape, carried
    // over both BLE (PROGRESS_CHARACTERISTIC_UUID) and HTTP (MuleSyncClient.getProgress).

    @Test
    fun progressPayloadRoundTripsExactly() {
        val payload = ProgressPayload(
            raceName = "Test Race",
            raceDate = "23/08/2026",
            generatedAt = "2026-08-23T10:00:00.000Z",
            entries = listOf(
                ProgressEntry(
                    bibNumber = 1, name = "Dave", category = "MSEN", course = "Seniors",
                    startTime = "00:00:00", finishTime = "00:45:00", cpTimes = mapOf("1" to "00:10:00"),
                ),
            ),
        )

        val decoded = json.decodeFromString<ProgressPayload>(json.encodeToString(payload))

        assertEquals(payload, decoded)
    }

    // The adoption fields a relay-forwarded payload from the web app carries so a logged-in mule
    // can write the server's adoption marker on its behalf (see
    // PeripheralSyncService.handleProgressPayload) — absent on every other payload.
    @Test
    fun progressPayloadCarriesAdoptionFields() {
        val decoded = json.decodeFromString<ProgressPayload>(
            """{"entries":[],"targetDeviceId":"d1","targetRaceLabel":"lmv-seniors","fromRaceLabel":"unknown-26-09-23","targetDeviceName":"brave-reef"}""",
        )

        assertEquals("unknown-26-09-23", decoded.fromRaceLabel)
        assertEquals("brave-reef", decoded.targetDeviceName)
        assertEquals("lmv-seniors", decoded.targetRaceLabel)
    }

    @Test
    fun progressPayloadDecodesMissingFieldsAsDefaults() {
        val decoded = json.decodeFromString<ProgressPayload>("{}")

        assertEquals(ProgressPayload(), decoded)
        assertEquals("", decoded.generatedAt)
        assertTrue(decoded.entries.isEmpty())
    }

    // DeviceInfo.progressGeneratedAt — must default to null so an old-build requester (or an
    // old-build responder being read by a new-build requester) still decodes fine either way.

    @Test
    fun deviceInfoProgressGeneratedAtDefaultsToNullWhenMissingFromTheWire() {
        val decoded = json.decodeFromString<DeviceInfo>(
            """{"deviceId":"dev1","raceLabel":"race-a","lastLineNumber":0}""",
        )

        assertNull(decoded.progressGeneratedAt)
    }

    @Test
    fun deviceInfoProgressGeneratedAtRoundTripsWhenPresent() {
        val info = DeviceInfo(
            deviceId = "dev1", raceLabel = "race-a", lastLineNumber = 0,
            progressGeneratedAt = "2026-08-23T10:00:00.000Z",
        )

        val decoded = json.decodeFromString<DeviceInfo>(json.encodeToString(info))

        assertEquals("2026-08-23T10:00:00.000Z", decoded.progressGeneratedAt)
    }
}
