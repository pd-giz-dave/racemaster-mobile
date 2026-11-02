package mobile.racemaster.data.beacon

import java.util.UUID

/**
 * The DX-CP27 beacon's real GATT configuration protocol — reverse-engineered by sniffing the
 * official DX-SMART app's own BLE traffic against real hardware, then confirmed by a working
 * Python reference implementation (`~/dx-cp27-probing/dxbeacon.py`, see that repo's README for
 * the full capture analysis). This object ports that script's framing/parsing call-for-call;
 * [DxCp27ProtocolTest] asserts every function here against the actual bytes that script decoded
 * from a real beacon, not invented examples. This replaces an earlier, unrelated guess at this
 * protocol (see TODO.md's own history) that turned out to be cross-contaminated from a different
 * module family entirely.
 */
object DxCp27Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

    /** Notify (also write-capable, but only ever used for notify) — the response channel. */
    val NOTIFY_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    /** Write — the framed command channel (see [buildFrame]). */
    val COMMAND_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")

    /** Write — authenticate by writing the current password here as plain ASCII, unframed (see
     *  [DxCp27Client.authenticate]). Changing the password is a separate, framed operation
     *  ([CMD_CHANGE_PASSWORD] sent to [COMMAND_CHARACTERISTIC_UUID]) — the two aren't the same
     *  write. */
    val PASSWORD_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe3-0000-1000-8000-00805f9b34fb")

    const val DEFAULT_PASSWORD = "dx1234"

    /** See [DeviceInfo.batteryPercent]'s own doc — a CR2032's nominal/fresh open-circuit voltage
     *  is ~3.0-3.2V; 3000mV is used as the "100%" end of the linear approximation. */
    const val CR2032_FULL_MILLIVOLTS = 3_000

    /** See [DeviceInfo.batteryPercent]'s own doc — most CR2032-powered electronics brown out
     *  well before the cell is chemically exhausted, so 2000mV ("0%") is a practical end-of-life
     *  cutoff, not the cell's true minimum voltage. */
    const val CR2032_EMPTY_MILLIVOLTS = 2_000

    // ============================================================================================
    // Every value this app writes into a beacon's iBeacon slot — UUID, major, target password,
    // advertising interval, TX power — lives in this one block, so a future tuning pass ("slow
    // the interval down to save battery", "turn the TX power up") is a one-line edit here, not a
    // hunt through BeaconRepository/DxCp27Client. MINOR isn't here: it's the one field that's
    // genuinely per-beacon, entered by the operator on the screen itself, not a fixed constant.
    // ============================================================================================

    /** Fixed across every beacon this app programs — only MINOR distinguishes individual units
     *  (MAJOR is always [RACEMASTER_MAJOR]). Generated once as a random v4 UUID; must never
     *  change once beacons are deployed with it, or previously-programmed units stop matching. */
    val RACEMASTER_IBEACON_UUID: ByteArray = uuidToBytes(UUID.fromString("B9A7CE7F-5D62-4F85-B34C-B8D5BCB23B99"))

    const val RACEMASTER_MAJOR = 1

    // Must be exactly 6 characters, matching DEFAULT_PASSWORD's own length — confirmed on real
    // hardware: a 10-character "racemaster" was rejected outright (change-password ack came back
    // 4e4f24010025, i.e. a 0x00 failure byte, not the 0x01 success dxbeacon.py expects), and
    // dxbeacon.py's own set-password command warns "the app [DX-SMART] only ever used
    // 6-character passwords" — this is a firmware-level fixed-length field, not a convention.
    const val RACEMASTER_PASSWORD = "mercia"

    /** DX-SMART's own advertising-interval field: a plain 100ms-per-raw-unit step, 100-1500ms —
     *  confirmed against the DX-SMART app's own UI. */
    fun msToIntervalRaw(ms: Int): Int {
        require(ms in 100..1500 && ms % 100 == 0) { "interval must be 100-1500ms in 100ms steps, was $ms" }
        return ms / 100
    }

    const val RACEMASTER_ADV_INTERVAL_MS = 300
    val RACEMASTER_ADV_INTERVAL_RAW: Int = msToIntervalRaw(RACEMASTER_ADV_INTERVAL_MS)

    /** DX-SMART's own TX-power raw index 0-7, in dBm — not evenly spaced, so this is a lookup
     *  table rather than a formula; confirmed against the DX-SMART app's own UI. Supersedes an
     *  earlier, rougher "raw 6 is ~0dBm" note that was only ever the operator's own field
     *  measurement, not this table — see git history. */
    val TX_POWER_DBM_BY_RAW: List<Double> = listOf(-19.5, -13.5, -7.0, -3.5, -1.0, 1.0, 1.5, 2.5)

    fun dbmToTxPowerRaw(dbm: Double): Int {
        val raw = TX_POWER_DBM_BY_RAW.indexOf(dbm)
        require(raw >= 0) { "no TX power raw index for ${dbm}dBm — valid values: $TX_POWER_DBM_BY_RAW" }
        return raw
    }

    const val RACEMASTER_TX_POWER_DBM = -7.0
    val RACEMASTER_TX_POWER_RAW: Int = dbmToTxPowerRaw(RACEMASTER_TX_POWER_DBM)

    /** Slot 1 holds this beacon's device-name frame, which this app deliberately leaves alone
     *  (per the operator's own instruction — it's used to identify the physical unit, e.g. during
     *  a scan) rather than clearing it like every other non-iBeacon slot. */
    const val PRESERVED_DEVICE_NAME_SLOT = 1

    /** This app always programs the iBeacon frame into slot 2 (slot 1 is [PRESERVED_DEVICE_NAME_SLOT])
     *  and clears every slot after it. */
    const val IBEACON_SLOT = 2

    // ---- Commands (phone -> beacon, framed via buildFrame, written to COMMAND_CHARACTERISTIC_UUID) ----
    const val CMD_READ_SLOTS = 0x10
    private const val CMD_SELECT_SLOT_BASE = 0x10 // + slot(1..7) -> 0x11..0x17
    const val CMD_FACTORY_RESET = 0x21
    const val CMD_STATUS = 0x22
    const val CMD_POWER_OFF = 0x23
    const val CMD_CHANGE_PASSWORD = 0x24
    const val CMD_PRE_AUTH_CHECK = 0x25
    const val CMD_DEVICE_INFO = 0x30
    const val CMD_APPLY = 0x60
    const val CMD_SAVE = 0xA0

    /** After a slot-select, writing this as the frame-type byte deletes that slot (no ack
     *  expected) — same byte value doubles as "pre-edit query" when sent with no preceding
     *  select. */
    const val CMD_SET_EMPTY = 0xFF

    // ---- Frame types (the byte a slot holds, and the command used to read that slot's frame) ----
    const val FRAME_DEVICE_NAME = 0x61
    const val FRAME_IBEACON = 0x62
    const val FRAME_EDDYSTONE_UID = 0x63
    const val FRAME_EDDYSTONE_URL = 0x64
    const val FRAME_EDDYSTONE_TLM = 0x65
    const val FRAME_EMPTY = 0xFF

    // ---- iBeacon field writes (sent after selecting a slot and setting its type to FRAME_IBEACON) ----
    const val CMD_SET_UUID = 0x74
    const val CMD_SET_MAJOR = 0x75
    const val CMD_SET_MINOR = 0x76
    const val CMD_SET_MEASURED_POWER = 0x77
    const val CMD_SET_ADV_INTERVAL = 0x78
    const val CMD_SET_TX_POWER = 0x79

    fun selectSlotCommand(slot: Int): Int {
        require(slot in 1..7) { "slot must be 1..7, was $slot" }
        return CMD_SELECT_SLOT_BASE + slot
    }

    /** `"NO"` + cmd + len + data + XOR(cmd, len, data...) — ports `build_frame` from
     *  `dxbeacon.py` byte for byte. Every command written to [COMMAND_CHARACTERISTIC_UUID] uses
     *  this framing; the password write to [PASSWORD_CHARACTERISTIC_UUID] deliberately does not
     *  (see that constant's own doc). */
    fun buildFrame(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val body = byteArrayOf(cmd.toByte(), data.size.toByte()) + data
        var checksum = 0
        for (b in body) checksum = checksum xor (b.toInt() and 0xff)
        return "NO".toByteArray(Charsets.US_ASCII) + body + checksum.toByte()
    }

    /** A write-ack notification is exactly `buildFrame(cmd, [0x01])` — a bare success marker. */
    fun isSuccessAck(cmd: Int, reply: ByteArray): Boolean = reply.contentEquals(buildFrame(cmd, byteArrayOf(1)))

    data class IBeaconFrame(
        val uuid: ByteArray,
        val major: Int,
        val minor: Int,
        val measuredPowerDbm: Int,
        val intervalRaw: Int,
        val txPowerRaw: Int,
    ) {
        val uuidString: String get() = formatUuidBytes(uuid)

        // ByteArray breaks data-class-generated equals/hashCode (Kotlin's `==` on arrays is
        // referential) — overridden explicitly so two frames read back with identical bytes
        // actually compare equal, which BeaconRepository's post-program verification relies on.
        override fun equals(other: Any?): Boolean = other is IBeaconFrame &&
            uuid.contentEquals(other.uuid) && major == other.major && minor == other.minor &&
            measuredPowerDbm == other.measuredPowerDbm && intervalRaw == other.intervalRaw &&
            txPowerRaw == other.txPowerRaw

        override fun hashCode(): Int =
            ((uuid.contentHashCode() * 31 + major) * 31 + minor) * 31 + measuredPowerDbm
    }

    data class DeviceNameFrame(val name: String, val intervalRaw: Int, val txPowerRaw: Int)

    data class DeviceInfo(
        val batteryMillivolts: Int,
        val mac: String,
        val manufacturer: String?,
        val chip: String?,
        val model: String?,
        val firmware: String?,
        val software: String?,
    ) {
        /** A rough percentage, not a real fuel-gauge reading — there's no coulomb counter here,
         *  just this one voltage sample. Linearly maps [batteryMillivolts] between
         *  [CR2032_EMPTY_MILLIVOLTS] and [CR2032_FULL_MILLIVOLTS], clamped to 0-100. A CR2032's
         *  actual discharge curve is flat for most of its life then falls off a cliff near the
         *  end, so this will under-read while fresh and over-read right before it's actually
         *  dead — it's meant as a rough "still fine / getting low / replace it" indicator, not a
         *  precise one, since no CR2032 discharge curve for this specific beacon's own quiescent
         *  current draw was available to calibrate against. */
        val batteryPercent: Int
            get() = (100 * (batteryMillivolts - CR2032_EMPTY_MILLIVOLTS) / (CR2032_FULL_MILLIVOLTS - CR2032_EMPTY_MILLIVOLTS))
                .coerceIn(0, 100)
    }

    /** [CMD_READ_SLOTS]'s 7-byte reply — one entry per slot, [FRAME_EMPTY] for an unused one.
     *  Unlike every frame-type read below, this reply does NOT echo a command byte — it's the 7
     *  raw slot-type bytes directly (confirmed against the real capture: cmd 0x10 out, a bare
     *  7-byte reply in, no leading 0x10). */
    fun parseSlotTable(data: ByteArray): List<Int> = data.map { it.toInt() and 0xff }

    /** [FRAME_IBEACON]'s 27-byte reply: echoed type byte + 16-byte UUID + major (BE) + minor (BE)
     *  + signed measured-power + interval + TX power + 3 reserved bytes (always zero in
     *  practice — there's no separate command to set them). */
    fun parseIBeaconFrame(data: ByteArray): IBeaconFrame {
        require(data.size >= 27) { "iBeacon frame reply too short: ${data.size} bytes" }
        return IBeaconFrame(
            uuid = data.copyOfRange(1, 17),
            major = beU16(data, 17),
            minor = beU16(data, 19),
            measuredPowerDbm = data[21].toInt(),
            intervalRaw = data[22].toInt() and 0xff,
            txPowerRaw = data[23].toInt() and 0xff,
        )
    }

    /** [FRAME_DEVICE_NAME]'s 34-byte reply: echoed type byte + 28-byte NUL-padded name +
     *  interval + TX power + 3 reserved bytes. */
    fun parseDeviceNameFrame(data: ByteArray): DeviceNameFrame {
        require(data.size >= 34) { "device-name frame reply too short: ${data.size} bytes" }
        val name = String(data.copyOfRange(1, 29), Charsets.US_ASCII).trimEnd('\u0000')
        return DeviceNameFrame(name, data[29].toInt() and 0xff, data[30].toInt() and 0xff)
    }

    /** [CMD_DEVICE_INFO]'s reply: echoed type byte + 2-byte battery mV (LE) + 6-byte MAC +
     *  NUL-separated ASCII strings (manufacturer, chip, model, firmware, software — any that are
     *  missing just come back null rather than failing the whole parse). */
    fun parseDeviceInfo(data: ByteArray): DeviceInfo {
        require(data.size >= 9) { "device info reply too short: ${data.size} bytes" }
        val batteryMillivolts = (data[1].toInt() and 0xff) or ((data[2].toInt() and 0xff) shl 8)
        val mac = data.copyOfRange(3, 9).joinToString(":") { "%02X".format(it) }
        val strings = String(data.copyOfRange(9, data.size), Charsets.US_ASCII)
            .split('\u0000').filter { it.isNotEmpty() }
        return DeviceInfo(
            batteryMillivolts = batteryMillivolts,
            mac = mac,
            manufacturer = strings.getOrNull(0),
            chip = strings.getOrNull(1),
            model = strings.getOrNull(2),
            firmware = strings.getOrNull(3),
            software = strings.getOrNull(4),
        )
    }

    /** Human label for a slot-table/frame-type byte, for display (not protocol behavior). */
    fun frameTypeLabel(frameType: Int): String = when (frameType) {
        FRAME_DEVICE_NAME -> "Device name"
        FRAME_IBEACON -> "iBeacon"
        FRAME_EDDYSTONE_UID -> "Eddystone-UID"
        FRAME_EDDYSTONE_URL -> "Eddystone-URL"
        FRAME_EDDYSTONE_TLM -> "Eddystone-TLM"
        FRAME_EMPTY -> "Empty"
        else -> "Unknown (0x%02x)".format(frameType)
    }

    fun formatUuidBytes(bytes: ByteArray): String {
        require(bytes.size == 16) { "UUID must be 16 bytes, was ${bytes.size}" }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        val dashed = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
        return dashed.uppercase()
    }

    private fun beU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun uuidToBytes(uuid: UUID): ByteArray {
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        val out = ByteArray(16)
        for (i in 0..7) out[i] = ((msb shr ((7 - i) * 8)) and 0xff).toByte()
        for (i in 0..7) out[8 + i] = ((lsb shr ((7 - i) * 8)) and 0xff).toByte()
        return out
    }
}
