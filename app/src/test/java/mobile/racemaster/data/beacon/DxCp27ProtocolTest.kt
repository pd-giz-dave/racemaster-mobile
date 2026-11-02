package mobile.racemaster.data.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every expected value here was produced by running `~/dx-cp27-probing/dxbeacon.py`'s own
 *  `build_frame`/`parse_frame`/`parse_device_info` against the actual bytes it captured from a
 *  real DX-CP27 — not invented examples. This is what makes this port's byte-for-byte fidelity to
 *  the validated reference implementation checkable without BLE hardware on hand. */
class DxCp27ProtocolTest {
    @Test
    fun buildFrameMatchesTheValidatedPythonReference() {
        assertEquals("4e4f620062", DxCp27Protocol.buildFrame(0x62).toHex())
        assertEquals("4e4f100010", DxCp27Protocol.buildFrame(DxCp27Protocol.CMD_READ_SLOTS).toHex())
        assertEquals("4e4fff00ff", DxCp27Protocol.buildFrame(0xFF).toHex())
        assertEquals("4e4fa000a0", DxCp27Protocol.buildFrame(DxCp27Protocol.CMD_SAVE).toHex())
        assertEquals("4e4f600060", DxCp27Protocol.buildFrame(DxCp27Protocol.CMD_APPLY).toHex())
        assertEquals("4e4f7502000176", DxCp27Protocol.buildFrame(0x75, byteArrayOf(0, 1)).toHex())
        assertEquals(
            "4e4f240a726163656d617374657227",
            DxCp27Protocol.buildFrame(DxCp27Protocol.CMD_CHANGE_PASSWORD, "racemaster".toByteArray(Charsets.US_ASCII)).toHex(),
        )
        assertEquals(
            "4e4f7410b9a7ce7f5d624f85b34cb8d5bcb23b9900",
            DxCp27Protocol.buildFrame(DxCp27Protocol.CMD_SET_UUID, DxCp27Protocol.RACEMASTER_IBEACON_UUID).toHex(),
        )
    }

    @Test
    fun isSuccessAckRecognizesTheRealCapturedAckShape() {
        // "4e4f25010125" was the real notification captured in reply to a 0x25 pre-auth check.
        assertTrue(DxCp27Protocol.isSuccessAck(0x25, "4e4f25010125".fromHex()))
        assertTrue(!DxCp27Protocol.isSuccessAck(0x25, "4e4f25000025".fromHex()))
    }

    @Test
    fun selectSlotCommandMapsSlotsOneToSevenOntoZeroXOneOneThroughZeroXOneSeven() {
        assertEquals(0x11, DxCp27Protocol.selectSlotCommand(1))
        assertEquals(0x17, DxCp27Protocol.selectSlotCommand(7))
    }

    @Test
    fun parseSlotTableReturnsEveryByteUnmodifiedWithNoEchoedCommand() {
        // The real capture's slot table: name, iBeacon, empty, Eddystone-URL, Eddystone-TLM, empty, empty.
        val slots = DxCp27Protocol.parseSlotTable("6162ff6465ffff".fromHex())
        assertEquals(listOf(0x61, 0x62, 0xff, 0x64, 0x65, 0xff, 0xff), slots)
    }

    @Test
    fun parseIBeaconFrameMatchesTheValidatedPythonReference() {
        val frame = DxCp27Protocol.parseIBeaconFrame("62e2c56db5dffb48d2b060d0f5a71096e000050006c70300000000".fromHex())
        assertEquals("E2C56DB5-DFFB-48D2-B060-D0F5A71096E0", frame.uuidString)
        assertEquals(5, frame.major)
        assertEquals(6, frame.minor)
        assertEquals(-57, frame.measuredPowerDbm)
        assertEquals(3, frame.intervalRaw)
        assertEquals(0, frame.txPowerRaw)
    }

    @Test
    fun parseDeviceNameFrameMatchesTheValidatedPythonReference() {
        val frame = DxCp27Protocol.parseDeviceNameFrame(
            "61435032372d34323336000000000000000000000000000000000000000300000000".fromHex(),
        )
        assertEquals("CP27-4236", frame.name)
        assertEquals(3, frame.intervalRaw)
        assertEquals(0, frame.txPowerRaw)
    }

    @Test
    fun parseDeviceInfoMatchesTheValidatedPythonReference() {
        val info = DxCp27Protocol.parseDeviceInfo(
            ("30b50b48872d9e423644582d534d41525400004469616c6f673134353331004350323700000000" +
                "000056322e342e303100000056322e342e3000000000").fromHex(),
        )
        assertEquals(2997, info.batteryMillivolts)
        assertEquals("48:87:2D:9E:42:36", info.mac)
        assertEquals("DX-SMART", info.manufacturer)
        assertEquals("Dialog14531", info.chip)
        assertEquals("CP27", info.model)
        assertEquals("V2.4.01", info.firmware)
        assertEquals("V2.4.0", info.software)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
