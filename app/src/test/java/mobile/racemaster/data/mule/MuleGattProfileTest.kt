package mobile.racemaster.data.mule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MuleGattProfileTest {

    // encodeAdvertisedIdentity/decodeAdvertisedIdentity — the scan-response payload
    // MuleSyncEngine's shouldConnect gate relies on to decide whether a real GATT connect is
    // even worth attempting. Must round-trip exactly for a normal name, must never throw (only
    // ever return null) for anything malformed, and must always stay inside the legacy 31-byte
    // scan-response budget even for a pathologically long name.

    @Test
    fun roundTripsAnOrdinaryNameAndCounter() {
        val encoded = MuleGattProfile.encodeAdvertisedIdentity(lastLineNumber = 42L, deviceName = "spirited-hedgehog")

        val decoded = MuleGattProfile.decodeAdvertisedIdentity(encoded)

        assertEquals(MuleGattProfile.AdvertisedIdentity(42L, "spirited-hedgehog"), decoded)
    }

    @Test
    fun neverExceedsTheManufacturerDataByteBudgetEvenForAPathologicallyLongName() {
        val encoded = MuleGattProfile.encodeAdvertisedIdentity(
            lastLineNumber = 999L,
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

        val encoded = MuleGattProfile.encodeAdvertisedIdentity(lastLineNumber = 1L, deviceName = longMultiByteName)
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
        val encoded = MuleGattProfile.encodeAdvertisedIdentity(1L, "name")
        encoded[0] = 0x00

        assertNull(MuleGattProfile.decodeAdvertisedIdentity(encoded))
    }

    @Test
    fun decodeReturnsNullForAnUnrecognizedFutureFormatVersion() {
        // Simulates an old-build requester talking to a peer running a future wire format —
        // must degrade to "unknown," not throw or misinterpret the bytes.
        val encoded = MuleGattProfile.encodeAdvertisedIdentity(1L, "name")
        encoded[MuleGattProfile.ADVERTISING_MAGIC.size] = (MuleGattProfile.ADVERTISING_FORMAT_VERSION + 1).toByte()

        assertNull(MuleGattProfile.decodeAdvertisedIdentity(encoded))
    }

    @Test
    fun decodeReturnsNullForATruncatedArray() {
        val encoded = MuleGattProfile.encodeAdvertisedIdentity(1L, "name")

        assertNull(MuleGattProfile.decodeAdvertisedIdentity(encoded.copyOfRange(0, 3)))
        assertNull(MuleGattProfile.decodeAdvertisedIdentity(ByteArray(0)))
    }

    @Test
    fun decodeReturnsNullWhenDeclaredNameLengthExceedsWhatsActuallyThere() {
        val encoded = MuleGattProfile.encodeAdvertisedIdentity(1L, "name")
        // Corrupt the nameLen byte (right after magic+version+counter) to claim more bytes
        // than actually follow.
        val nameLenIndex = MuleGattProfile.ADVERTISING_MAGIC.size + 1 + 4
        encoded[nameLenIndex] = 100

        assertNull(MuleGattProfile.decodeAdvertisedIdentity(encoded))
    }

    @Test
    fun encodesAnEmptyNameFine() {
        val encoded = MuleGattProfile.encodeAdvertisedIdentity(0L, "")

        assertEquals(MuleGattProfile.AdvertisedIdentity(0L, ""), MuleGattProfile.decodeAdvertisedIdentity(encoded))
    }
}
