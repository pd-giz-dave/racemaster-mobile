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
        assertEquals(false, frame.triggerEnabled)
        assertEquals("Off", frame.triggerDescription)
    }

    @Test
    fun parseIBeaconFrameDecodesTriggerTrailer() {
        // README's own documented examples from the capture that stepped DX-SMART's "Trigger"
        // option through all four values: "...03 02 00 00 00" is off, "...03 02 01 01 1e" is
        // on/double-click/30s.
        val off = DxCp27Protocol.parseIBeaconFrame("62e2c56db5dffb48d2b060d0f5a71096e000050006c70300000000".fromHex())
        assertEquals(false, off.triggerEnabled)

        val doubleClick = DxCp27Protocol.parseIBeaconFrame(
            "62e2c56db5dffb48d2b060d0f5a71096e000050006c7030201011e".fromHex(),
        )
        assertEquals(true, doubleClick.triggerEnabled)
        assertEquals(DxCp27Protocol.TRIGGER_DOUBLE_CLICK, doubleClick.triggerMode)
        assertEquals(30, doubleClick.triggerSeconds)
        assertEquals("double-click, 30s", doubleClick.triggerDescription)
    }

    @Test
    fun parseDeviceNameFrameMatchesTheValidatedPythonReference() {
        val frame = DxCp27Protocol.parseDeviceNameFrame(
            "61435032372d34323336000000000000000000000000000000000000000300000000".fromHex(),
        )
        assertEquals("CP27-4236", frame.name)
        assertEquals(3, frame.intervalRaw)
        assertEquals(0, frame.txPowerRaw)
        assertEquals(false, frame.triggerEnabled)
        assertEquals("Off", frame.triggerDescription)
    }

    @Test
    fun parseDeviceNameFrameDecodesTriggerTrailer() {
        // Confirmed not iBeacon-specific by a second, independent capture
        // (snooped_data-device-info.txt) that set a device-name slot's own trigger to
        // double-click/53s using the identical save/apply sequence.
        val doubleClick = DxCp27Protocol.parseDeviceNameFrame(
            "61435032372d34323336000000000000000000000000000000000000000300010135".fromHex(),
        )
        assertEquals(true, doubleClick.triggerEnabled)
        assertEquals(DxCp27Protocol.TRIGGER_DOUBLE_CLICK, doubleClick.triggerMode)
        assertEquals(53, doubleClick.triggerSeconds)
        assertEquals("double-click, 53s", doubleClick.triggerDescription)
    }

    @Test
    fun raceDeviceNameMatchesTodoMdsOwnSpec() {
        // TODO.md lines 281-299: "RM.nnn[+-]pp" where nnn is the minor id zero-padded to 3
        // digits and pp is the measured-power magnitude zero-padded to 2 digits, with a sign.
        assertEquals("RM.007-71", DxCp27Protocol.raceDeviceName(minor = 7, measuredPowerDbm = -71))
        assertEquals("RM.123-05", DxCp27Protocol.raceDeviceName(minor = 123, measuredPowerDbm = -5))
        assertEquals("RM.000+00", DxCp27Protocol.raceDeviceName(minor = 0, measuredPowerDbm = 0))
        // Not clamped to exactly 3/2 digits — a minor beyond 999 or magnitude beyond 99 still
        // round-trips, just longer.
        assertEquals("RM.1200-71", DxCp27Protocol.raceDeviceName(minor = 1200, measuredPowerDbm = -71))
    }

    @Test
    fun parseRaceDeviceNameIsTheExactReverseOfRaceDeviceName() {
        assertEquals(7 to -71, DxCp27Protocol.parseRaceDeviceName("RM.007-71"))
        assertEquals(123 to -5, DxCp27Protocol.parseRaceDeviceName("RM.123-05"))
        assertEquals(0 to 0, DxCp27Protocol.parseRaceDeviceName("RM.000+00"))
        assertEquals(1200 to -71, DxCp27Protocol.parseRaceDeviceName("RM.1200-71"))
        // Tolerates a non-padded magnitude too — parsing is lenient even though raceDeviceName
        // itself always emits exactly 2 digits now, in case an older-written name is read back.
        assertEquals(123 to -5, DxCp27Protocol.parseRaceDeviceName("RM.123-5"))
        assertEquals(null, DxCp27Protocol.parseRaceDeviceName("CP27-4236")) // a factory/unrelated name
        assertEquals(null, DxCp27Protocol.parseRaceDeviceName("RM.07-71")) // too few minor digits
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
    @Test
    fun parseIBeaconAdvertisementDecodesAppleManufacturerDataLayout() {
        // Same UUID/major/minor/measured-power as parseIBeaconFrameMatchesTheValidatedPythonReference's
        // GATT-read example, re-encoded as the standard Apple iBeacon advertisement layout (type
        // 0x02, length 0x15) — this is a scan-time decode of a different wire format entirely,
        // not a re-test of the GATT parser.
        val beacon = DxCp27Protocol.parseIBeaconAdvertisement(
            "0215e2c56db5dffb48d2b060d0f5a71096e000050006c7".fromHex(),
        )
        assertEquals("E2C56DB5-DFFB-48D2-B060-D0F5A71096E0", beacon?.uuidString)
        assertEquals(5, beacon?.major)
        assertEquals(6, beacon?.minor)
        assertEquals(-57, beacon?.measuredPowerDbm)
    }

    @Test
    fun advertisedIBeaconDistinguishesThisAppsOwnUuidFromAnyOther() {
        val own = DxCp27Protocol.parseIBeaconAdvertisement(
            ("0215" + DxCp27Protocol.RACEMASTER_IBEACON_UUID.joinToString("") { "%02x".format(it) } + "00010002c7").fromHex(),
        )
        assertEquals(true, own?.isProgrammedByThisApp)

        val other = DxCp27Protocol.parseIBeaconAdvertisement(
            "0215e2c56db5dffb48d2b060d0f5a71096e000050006c7".fromHex(),
        )
        assertEquals(false, other?.isProgrammedByThisApp)
    }

    @Test
    fun parseIBeaconAdvertisementWorksRegardlessOfWhichCompanyIdCarriedIt() {
        // Captured live from a real beacon this app had just programmed: the exact same 0215-shaped
        // payload the device actually broadcasts, over and over, tagged with VENDOR_COMPANY_ID
        // (0x4458 = ASCII "DX") rather than Apple's real APPLE_COMPANY_ID — a firmware quirk
        // confirmed against ~/dx-cp27-probing/btsnoop_hci.log (roughly half of every iBeacon-shaped
        // advert in that capture carries 0x4458 instead of 0x004c). parseIBeaconAdvertisement
        // itself is company-id-agnostic — the caller is what's responsible for trying both — so
        // this just confirms the payload decodes the same either way.
        val beacon = DxCp27Protocol.parseIBeaconAdvertisement(
            "0215b9a7ce7f5d624f85b34cb8d5bcb23b9900010001b9".fromHex(),
        )
        assertEquals(DxCp27Protocol.RACEMASTER_IBEACON_UUID.toList(), beacon?.uuid?.toList())
        assertEquals(true, beacon?.isProgrammedByThisApp)
        assertEquals(1, beacon?.major)
        assertEquals(1, beacon?.minor)
        assertEquals(-71, beacon?.measuredPowerDbm)
    }

    @Test
    fun eddystoneFrameLabelDecodesTheOpenSpecsOwnFrameTypeByte() {
        assertEquals("Eddystone-UID", DxCp27Protocol.eddystoneFrameLabel(byteArrayOf(0x00, 0x01, 0x02)))
        assertEquals("Eddystone-URL", DxCp27Protocol.eddystoneFrameLabel(byteArrayOf(0x10)))
        assertEquals("Eddystone-TLM", DxCp27Protocol.eddystoneFrameLabel(byteArrayOf(0x20)))
        assertEquals("Eddystone (empty service data)", DxCp27Protocol.eddystoneFrameLabel(byteArrayOf()))
        assertEquals("Eddystone (unknown frame type 0x30)", DxCp27Protocol.eddystoneFrameLabel(byteArrayOf(0x30)))
    }

    @Test
    fun frameTypeLabelCoversEveryKnownSlotType() {
        assertEquals("Name", DxCp27Protocol.frameTypeLabel(DxCp27Protocol.FRAME_DEVICE_NAME))
        assertEquals("iBeacon", DxCp27Protocol.frameTypeLabel(DxCp27Protocol.FRAME_IBEACON))
        assertEquals("Eddystone-UID", DxCp27Protocol.frameTypeLabel(DxCp27Protocol.FRAME_EDDYSTONE_UID))
        assertEquals("Eddystone-URL", DxCp27Protocol.frameTypeLabel(DxCp27Protocol.FRAME_EDDYSTONE_URL))
        assertEquals("Eddystone-TLM", DxCp27Protocol.frameTypeLabel(DxCp27Protocol.FRAME_EDDYSTONE_TLM))
        assertEquals("ACC", DxCp27Protocol.frameTypeLabel(DxCp27Protocol.FRAME_ACC))
        assertEquals("User", DxCp27Protocol.frameTypeLabel(DxCp27Protocol.FRAME_USER))
        assertEquals("Empty", DxCp27Protocol.frameTypeLabel(DxCp27Protocol.FRAME_EMPTY))
        assertEquals("Unknown (0x99)", DxCp27Protocol.frameTypeLabel(0x99))
    }

    @Test
    fun identityServiceFrameTypeDecodesTheRealCapturedStatusPlusMacShape() {
        // "ab fe 62 48 87 2d 9e 42 36" (service UUID 0xfeab + status + MAC) was the real service
        // data captured from a beacon with its iBeacon slot active, and "ab fe 63 ..."/"ab fe 64
        // ..." likewise for its Eddystone-UID/-URL slots — see ~/dx-cp27-probing's
        // btsnoop_hci-more-frames.log / btsnoop_hci-with-user.log. The leading "ab fe" (the
        // service UUID itself) isn't part of what's passed here — Kable's own serviceData(uuid)
        // already strips that, same as every other serviceData()/manufacturerData() call in this
        // codebase.
        assertEquals(DxCp27Protocol.FRAME_IBEACON, DxCp27Protocol.identityServiceFrameType("6248872d9e4236".fromHex()))
        assertEquals(DxCp27Protocol.FRAME_EDDYSTONE_UID, DxCp27Protocol.identityServiceFrameType("6348872d9e4236".fromHex()))
        assertEquals(DxCp27Protocol.FRAME_EDDYSTONE_URL, DxCp27Protocol.identityServiceFrameType("6448872d9e4236".fromHex()))
        // The User frame's own shape (see isUserFrameAdvertisementRecognizesTheRealCapturedUserDataShape)
        // isn't a status+MAC pair at all, so this deliberately returns null for it rather than
        // misreading its first byte as a frame-type status.
        assertEquals(null, DxCp27Protocol.identityServiceFrameType("40a0000040a00000c14600000000727400000000".fromHex()))
    }

    @Test
    fun isUserFrameAdvertisementRecognizesTheRealCapturedUserDataShape() {
        // Real service data (service UUID 0xfeab, already stripped by the caller same as every
        // other serviceData() call) captured from a beacon with its User slot active, cross-
        // checked against the operator's own knowledge of which slot held which frame type — see
        // ~/dx-cp27-probing/btsnoop_hci-more-frames.log. 20 bytes, not the GATT side's full
        // 22-byte field, since the legacy advertisement's 31-byte payload limit truncates it.
        assertTrue(
            DxCp27Protocol.isUserFrameAdvertisement(
                "40a0000040a00000c14600000000727400000000".fromHex(),
            ),
        )
        assertTrue(!DxCp27Protocol.isUserFrameAdvertisement("6248872d9e4236".fromHex())) // status+MAC, not User
    }

    @Test
    fun isAccFrameAdvertisementRecognizesTheRealCapturedAccShape() {
        // Real raw advertisement bytes captured from the same beacon with its ACC slot active:
        // flags, a 16-bit UUID list, then a malformed one-data-byte "manufacturer specific data"
        // AD entry (02 ff 12) — one byte short of a real 2-byte company ID, which is why this is
        // checked structurally rather than by that byte's exact value.
        assertTrue(DxCp27Protocol.isAccFrameAdvertisement("0201060303a5fd02ff12".fromHex()))
        assertTrue(!DxCp27Protocol.isAccFrameAdvertisement("0201060303a5fd".fromHex())) // no 0xff AD at all
        assertTrue(!DxCp27Protocol.isAccFrameAdvertisement(null))
    }

    @Test
    fun parseIBeaconAdvertisementRejectsNonIBeaconOrMissingData() {
        assertEquals(null, DxCp27Protocol.parseIBeaconAdvertisement(null))
        assertEquals(null, DxCp27Protocol.parseIBeaconAdvertisement("0215".fromHex())) // too short
        assertEquals(null, DxCp27Protocol.parseIBeaconAdvertisement("0315e2c56db5dffb48d2b060d0f5a71096e000050006c7".fromHex())) // wrong type
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
