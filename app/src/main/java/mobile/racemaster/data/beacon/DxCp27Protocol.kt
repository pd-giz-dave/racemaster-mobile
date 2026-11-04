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

    /** The firmware's fixed password length — see [RACEMASTER_PASSWORD]'s own doc for how this
     *  was confirmed. Used to cap manual password entry in the UI, since anything else is
     *  guaranteed to be rejected. */
    const val PASSWORD_LENGTH = 6

    /** See [DeviceInfo.batteryPercent]'s own doc — a CR2032's nominal/fresh open-circuit voltage
     *  is ~3.0-3.2V; 3000mV is used as the "100%" end of the linear approximation. */
    const val CR2032_FULL_MILLIVOLTS = 3_000

    /** See [DeviceInfo.batteryPercent]'s own doc — most CR2032-powered electronics brown out
     *  well before the cell is chemically exhausted, so 2000mV ("0%") is a practical end-of-life
     *  cutoff, not the cell's true minimum voltage. */
    const val CR2032_EMPTY_MILLIVOLTS = 2_000

    // ============================================================================================
    // Every value this app writes when programming a beacon — target password, advertising
    // interval, TX power — lives in this one block, so a future tuning pass ("slow the interval
    // down to save battery", "turn the TX power up") is a one-line edit here, not a hunt through
    // BeaconRepository/DxCp27Client. MINOR and the measured-power calibration aren't here: both
    // are genuinely per-beacon, entered/measured by the operator on the screen itself, not fixed
    // constants — see [raceDeviceName].
    // ============================================================================================

    /** Still recognized when scanning (see [AdvertisedIBeacon.isProgrammedByThisApp]), for a
     *  beacon an older version of this app programmed with an iBeacon frame — but no longer
     *  written: see [RACE_SLOT]'s own doc for the current device-name-based scheme (TODO.md lines
     *  281-299), which encodes minor/measured-power into a slot-1 device name instead. */
    val RACEMASTER_IBEACON_UUID: ByteArray = uuidToBytes(UUID.fromString("B9A7CE7F-5D62-4F85-B34C-B8D5BCB23B99"))

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

    /** This app programs slot 1 as a device-name frame (see [raceDeviceName]) and clears every
     *  other slot — per TODO.md's own spec (lines 281-299), superseding an earlier design that
     *  preserved slot 1's factory name and wrote an iBeacon frame into slot 2 instead (still
     *  recognized when reading an older-programmed beacon back, just not written anymore). */
    const val RACE_SLOT = 1

    /** The device-name text this app programs into [RACE_SLOT] — encodes both operator-entered
     *  fields directly into the name itself (no separate iBeacon UUID/major/minor/measured-power
     *  frame anymore): `"RM."` + [minor] zero-padded to at least 3 digits, then a sign (`'-'` for
     *  negative, `'+'` otherwise — [measuredPowerDbm] is realistically always negative in
     *  practice, but TODO.md's own spec allows either) and its magnitude zero-padded to at least
     *  2 digits. E.g. minor 7, measured power -5dBm -> `"RM.007-05"`. Comfortably inside the
     *  device-name frame's 28-byte max. */
    fun raceDeviceName(minor: Int, measuredPowerDbm: Int): String {
        val sign = if (measuredPowerDbm < 0) '-' else '+'
        return "RM.%03d%c%02d".format(minor, sign, kotlin.math.abs(measuredPowerDbm))
    }

    private val RACE_DEVICE_NAME_REGEX = Regex("""^RM\.(\d{3,})([+-])(\d+)$""")

    /** The reverse of [raceDeviceName] — `null` for anything not shaped like that exact format
     *  (e.g. a beacon still on its factory name, or an older [RACEMASTER_IBEACON_UUID]-based
     *  beacon whose device-name slot was left untouched). Used to pre-fill the minor/measured-
     *  power inputs when reconnecting to an already race-programmed beacon. */
    fun parseRaceDeviceName(name: String): Pair<Int, Int>? {
        val match = RACE_DEVICE_NAME_REGEX.matchEntire(name) ?: return null
        val minor = match.groupValues[1].toIntOrNull() ?: return null
        val magnitude = match.groupValues[3].toIntOrNull() ?: return null
        val measuredPowerDbm = if (match.groupValues[2] == "-") -magnitude else magnitude
        return minor to measuredPowerDbm
    }

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

    /** Save, with the slot's trigger left off/cleared — the two forms of the edit sequence's save
     *  step (the other is [CMD_SAVE_WITH_TRIGGER]) are mutually exclusive, not a save plus a
     *  separate trigger toggle. This is the one this app always uses: a race beacon must
     *  advertise continuously, not just on a button click or being picked up, so every
     *  [DxCp27Client.setDeviceName] call ends here, never on [CMD_SAVE_WITH_TRIGGER]. Confirmed via a
     *  fresh capture stepping DX-SMART's own per-slot "Trigger" option through all four of its
     *  values (double-click, triple-click, device-move, then off) — see [CMD_SAVE_WITH_TRIGGER]'s
     *  own doc for the full writeup. */
    const val CMD_SAVE = 0xA0

    /** Save, with the iBeacon slot's trigger set to one of [TRIGGER_DOUBLE_CLICK]/
     *  [TRIGGER_TRIPLE_CLICK]/[TRIGGER_DEVICE_MOVE] — takes the place of the plain [CMD_SAVE] in
     *  the edit sequence (select slot, resend fields 0x74-0x79, then this instead of 0xA0, then
     *  [CMD_APPLY]) and carries 2 data bytes: the trigger mode, then how many seconds the beacon
     *  advertises once the trigger fires (1-100; [DEFAULT_TRIGGER_SECONDS] if never otherwise set
     *  by the operator). Must be followed by [CMD_READ_TRIGGER] before apply or the trigger
     *  doesn't actually commit — confirmed on real hardware; the reply to that read is itself
     *  unreliable junk and gets ignored, it's the call itself that's load-bearing. This app never
     *  calls this — a race beacon needs continuous advertising, not a one-shot burst on a click or
     *  motion — but the command is real and documented here for completeness/future use, not
     *  guessed. */
    const val CMD_SAVE_WITH_TRIGGER = 0xA2

    /** Reads the trigger config outside of an edit session (`4e4f a1 00`), reply 2 raw bytes
     *  (mode, seconds) — confirmed unreliable in testing (doesn't always reflect the
     *  just-written value after a reconnect), so [parseIBeaconFrame]'s own trailer fields are the
     *  one reliable way to read a slot's current trigger state back; this command exists only as
     *  the mandatory (if ignored) call [CMD_SAVE_WITH_TRIGGER] needs before apply. */
    const val CMD_READ_TRIGGER = 0xA1

    const val TRIGGER_OFF = 0x00
    const val TRIGGER_DOUBLE_CLICK = 0x01
    const val TRIGGER_TRIPLE_CLICK = 0x02
    const val TRIGGER_DEVICE_MOVE = 0x03

    /** How many seconds a triggered one-shot advertisement runs for, 1-100 — unused by this app
     *  (which never enables a trigger) but kept alongside the other trigger constants for
     *  completeness; matches the one value ever seen entered via the DX-SMART UI in the capture
     *  this was reverse-engineered from. */
    const val DEFAULT_TRIGGER_SECONDS = 0x1E

    fun triggerModeLabel(mode: Int): String = when (mode) {
        TRIGGER_OFF -> "off"
        TRIGGER_DOUBLE_CLICK -> "double-click"
        TRIGGER_TRIPLE_CLICK -> "triple-click"
        TRIGGER_DEVICE_MOVE -> "device-move"
        else -> "unknown (0x%02x)".format(mode)
    }

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

    /** Accelerometer frame — no fields of its own (reading one back returns just the common
     *  interval/TX-power/trigger trailer, like TLM). Confirmed on real hardware: a slot set to
     *  this makes the beacon only become connectable around a trigger event instead of
     *  continuously — worth knowing if a slot ever seems to "disappear" after editing. */
    const val FRAME_ACC = 0x66

    /** Arbitrary up-to-22-byte user data frame — written via [CMD_SET_UUID]-adjacent command
     *  `0x95` (not currently used by this app; see `~/dx-cp27-probing/README.md`'s "Two more
     *  frame types" section for the full write sequence, ported into `dxbeacon.py` but not yet
     *  into this client). */
    const val FRAME_USER = 0x67

    const val FRAME_EMPTY = 0xFF

    // ---- Device-name field writes (sent after selecting a slot and setting its type to
    // FRAME_DEVICE_NAME) — what DxCp27Client.setDeviceName actually writes now, per RACE_SLOT's
    // own doc. ----
    const val CMD_SET_DEVICE_NAME = 0x71
    const val CMD_SET_DEVICE_NAME_ADV_INTERVAL = 0x72
    const val CMD_SET_DEVICE_NAME_TX_POWER = 0x73

    // ---- iBeacon field writes (sent after selecting a slot and setting its type to FRAME_IBEACON)
    // — kept as a faithful, complete port of the confirmed protocol (ports dxbeacon.py's own
    // IBEACON_FIELD_* constants), but no longer written by this app itself; see RACE_SLOT's own
    // doc for the current device-name-based scheme. ----
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
        // Defaulted to "off" rather than required — every caller that actually cares (this app
        // only ever programs with the trigger off; see CMD_SAVE's own doc) just wants that
        // default, and parseIBeaconFrame below fills in the real read-back values regardless.
        val triggerEnabled: Boolean = false,
        val triggerMode: Int = TRIGGER_OFF,
        val triggerSeconds: Int = 0,
    ) {
        val uuidString: String get() = formatUuidBytes(uuid)

        /** "Off", or "double-click, 30s" etc. — for display; see [CMD_SAVE]/[CMD_SAVE_WITH_TRIGGER]'s
         *  own docs for why this app always programs "Off". */
        val triggerDescription: String
            get() = if (!triggerEnabled) "Off" else "${triggerModeLabel(triggerMode)}, ${triggerSeconds}s"

        // ByteArray breaks data-class-generated equals/hashCode (Kotlin's `==` on arrays is
        // referential) — overridden explicitly so two frames read back with identical bytes
        // actually compare equal, which BeaconRepository's post-program verification relies on.
        // Trigger fields are included deliberately: that verification must catch the trigger
        // silently staying on just as much as a wrong UUID/major/minor would be caught.
        override fun equals(other: Any?): Boolean = other is IBeaconFrame &&
            uuid.contentEquals(other.uuid) && major == other.major && minor == other.minor &&
            measuredPowerDbm == other.measuredPowerDbm && intervalRaw == other.intervalRaw &&
            txPowerRaw == other.txPowerRaw && triggerEnabled == other.triggerEnabled &&
            triggerMode == other.triggerMode && triggerSeconds == other.triggerSeconds

        override fun hashCode(): Int =
            ((uuid.contentHashCode() * 31 + major) * 31 + minor) * 31 + measuredPowerDbm
    }

    data class DeviceNameFrame(
        val name: String,
        val intervalRaw: Int,
        val txPowerRaw: Int,
        // Defaulted to "off" for the same reason as IBeaconFrame's own trigger fields — this app
        // only ever programs with the trigger off, and parseDeviceNameFrame fills in the real
        // read-back values regardless.
        val triggerEnabled: Boolean = false,
        val triggerMode: Int = TRIGGER_OFF,
        val triggerSeconds: Int = 0,
    ) {
        /** Same shape as [IBeaconFrame.triggerDescription] — see that property's own doc. */
        val triggerDescription: String
            get() = if (!triggerEnabled) "Off" else "${triggerModeLabel(triggerMode)}, ${triggerSeconds}s"
    }

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
     *  + signed measured-power + interval + TX power + trigger state (enabled flag, mode,
     *  seconds) — what used to be documented as "3 reserved bytes, always zero in practice" is
     *  actually this slot's trigger config, discovered from a capture that stepped DX-SMART's own
     *  "Trigger" option through all four of its values; see [CMD_SAVE_WITH_TRIGGER]'s own doc. */
    fun parseIBeaconFrame(data: ByteArray): IBeaconFrame {
        require(data.size >= 27) { "iBeacon frame reply too short: ${data.size} bytes" }
        return IBeaconFrame(
            uuid = data.copyOfRange(1, 17),
            major = beU16(data, 17),
            minor = beU16(data, 19),
            measuredPowerDbm = data[21].toInt(),
            intervalRaw = data[22].toInt() and 0xff,
            txPowerRaw = data[23].toInt() and 0xff,
            triggerEnabled = (data[24].toInt() and 0xff) != 0,
            triggerMode = data[25].toInt() and 0xff,
            triggerSeconds = data[26].toInt() and 0xff,
        )
    }

    /** [FRAME_DEVICE_NAME]'s 34-byte reply: echoed type byte + 28-byte NUL-padded name +
     *  interval + TX power + trigger state (enabled flag, mode, seconds) — the same trailer
     *  layout as [parseIBeaconFrame]'s, confirmed not iBeacon-specific by a capture
     *  (`snooped_data-device-info.txt`) that set a device-name slot's own trigger directly. */
    fun parseDeviceNameFrame(data: ByteArray): DeviceNameFrame {
        require(data.size >= 34) { "device-name frame reply too short: ${data.size} bytes" }
        val name = String(data.copyOfRange(1, 29), Charsets.US_ASCII).trimEnd('\u0000')
        return DeviceNameFrame(
            name = name,
            intervalRaw = data[29].toInt() and 0xff,
            txPowerRaw = data[30].toInt() and 0xff,
            triggerEnabled = (data[31].toInt() and 0xff) != 0,
            triggerMode = data[32].toInt() and 0xff,
            triggerSeconds = data[33].toInt() and 0xff,
        )
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

    /** Human label for a slot-table/frame-type byte, for display (not protocol behavior) —
     *  deliberately terse (e.g. "ACC", not "ACC (accelerometer)") so it reads cleanly both in the
     *  programming screen's slot summary and the scan list's per-advert breakdown. */
    fun frameTypeLabel(frameType: Int): String = when (frameType) {
        FRAME_DEVICE_NAME -> "Name"
        FRAME_IBEACON -> "iBeacon"
        FRAME_EDDYSTONE_UID -> "Eddystone-UID"
        FRAME_EDDYSTONE_URL -> "Eddystone-URL"
        FRAME_EDDYSTONE_TLM -> "Eddystone-TLM"
        FRAME_ACC -> "ACC"
        FRAME_USER -> "User"
        FRAME_EMPTY -> "Empty"
        else -> "Unknown (0x%02x)".format(frameType)
    }

    /** Apple's company identifier code, used to look up iBeacon manufacturer-specific data in a
     *  raw scan advertisement (see [parseIBeaconAdvertisement]) — this is a *scan-time* decode,
     *  entirely separate from [parseIBeaconFrame]'s GATT read-back, and works on any beacon in
     *  range without connecting to it. */
    const val APPLE_COMPANY_ID = 0x004C

    /** Not a registered Bluetooth SIG company ID — `0x44 0x58` is ASCII "DX". Confirmed against
     *  the real capture: this beacon's iBeacon-shaped manufacturer data (`02 15` + UUID/major/
     *  minor/measured-power, byte-for-byte identical either way) alternates between tagging
     *  itself with this and with the genuine [APPLE_COMPANY_ID], roughly 50/50 across the whole
     *  capture (101 vs 107 of 208 samples) — a firmware quirk, not something either app can rely
     *  on being consistent advert to advert. DX-SMART evidently checks both company IDs (or
     *  ignores the company ID and matches on the `02 15` shape directly); this app must check
     *  both too, or it'll silently miss every other advertisement. */
    const val VENDOR_COMPANY_ID = 0x4458

    /** Service-data UUID 0xFEAB, seen in every advertisement this beacon has sent so far
     *  regardless of which slot/frame type is configured — payload is a 1-byte status followed
     *  by the 6-byte MAC, for every slot type that otherwise advertises its own distinct AD
     *  structure ([FRAME_IBEACON]/[FRAME_EDDYSTONE_UID]/[FRAME_EDDYSTONE_URL]/[FRAME_EDDYSTONE_TLM],
     *  confirmed on real hardware the status byte tracks the active slot's own frame-type byte).
     *  [FRAME_USER] is the one confirmed exception: a slot with a real, variable-length data
     *  field of its own reuses this *same* service UUID to carry that raw data instead of a
     *  status+MAC pair — see [isUserFrameAdvertisement]. Used by [DxCp27Client.scanForDevices] as
     *  one of the things to filter on, since it's present on every advert from this beacon
     *  whether or not a name AD structure also is, and by [identityServiceFrameType]/
     *  [isUserFrameAdvertisement] as scan-time frame-type recognition. */
    val VENDOR_IDENTITY_SERVICE_UUID: UUID = UUID.fromString("0000feab-0000-1000-8000-00805f9b34fb")

    /** Decodes [VENDOR_IDENTITY_SERVICE_UUID] service data's status byte as a slot's frame-type
     *  byte (see that constant's own doc) — `null` for anything not shaped like status+MAC (7
     *  bytes total), which is what [isUserFrameAdvertisement] is for instead. In practice this
     *  path is defensive, not load-bearing: device name/iBeacon/Eddystone are all decoded
     *  directly from their own AD structures first and never reach this fallback, so it only
     *  matters if one of those ever misses. */
    fun identityServiceFrameType(serviceData: ByteArray): Int? {
        if (serviceData.size != 7) return null
        return serviceData[0].toInt() and 0xff
    }

    /** True once [VENDOR_IDENTITY_SERVICE_UUID]'s service data is present but isn't the plain
     *  7-byte status+MAC shape every other recognized frame type uses — confirmed on real
     *  hardware (against a beacon with every one of its 7 slots configured, cross-checked with
     *  the operator's own knowledge of which slot held which frame type) that this is
     *  [FRAME_USER]'s raw data field, truncated to fit the legacy advertisement's 31-byte payload
     *  limit: its GATT-side field is up to 22 bytes (see `~/dx-cp27-probing/dxbeacon.py`'s
     *  `parse_frame`), but only 20 survive here once the fixed AD overhead (flags + 16-bit UUID
     *  list + this service data's own 2-byte UUID) is subtracted. The data itself is arbitrary —
     *  nothing further to decode, just that it's present. */
    fun isUserFrameAdvertisement(serviceData: ByteArray): Boolean = serviceData.size != 7

    /** True for [FRAME_ACC]'s over-the-air shape: a manufacturer-specific-data AD structure with
     *  exactly one data byte — one short of the 2-byte company ID the Bluetooth spec requires,
     *  so no well-formed manufacturer data could ever look like this. Confirmed on real hardware
     *  the same way as [isUserFrameAdvertisement]: fits the GATT side's own "no fields of its
     *  own" (see [FRAME_ACC]'s own doc) — there's nothing to carry, so the firmware broadcasts
     *  almost nothing. Checked structurally (the malformed length), not by this byte's exact
     *  value, since nothing says a future firmware revision won't change it. Takes the whole raw
     *  advertisement (not just one AD entry's data) since this malformed shape can't be looked up
     *  by company ID the way well-formed manufacturer data normally can. */
    fun isAccFrameAdvertisement(rawAdvertisementBytes: ByteArray?): Boolean {
        if (rawAdvertisementBytes == null) return false
        var i = 0
        while (i < rawAdvertisementBytes.size) {
            val length = rawAdvertisementBytes[i].toInt() and 0xff
            if (length == 0) break
            if (i + 1 >= rawAdvertisementBytes.size) break
            val type = rawAdvertisementBytes[i + 1].toInt() and 0xff
            if (type == 0xFF && length == 2) return true
            i += 1 + length
        }
        return false
    }

    /** Google's standard Eddystone service UUID, 0xFEAA — confirmed on real hardware: a slot
     *  configured as Eddystone-UID/-URL/-TLM (see [FRAME_EDDYSTONE_UID]/[FRAME_EDDYSTONE_URL]/
     *  [FRAME_EDDYSTONE_TLM], the GATT-side frame-type bytes, unrelated to these) advertises under
     *  this real, open-standard UUID rather than any DX-specific one — the Eddystone spec's own
     *  first service-data byte ([EDDYSTONE_FRAME_UID]/[EDDYSTONE_FRAME_URL]/[EDDYSTONE_FRAME_TLM])
     *  says which. Used by [DxCp27Client.scanForDevices] to recognize these frames without having
     *  to fully decode them, and by [eddystoneFrameLabel] for display. */
    val EDDYSTONE_SERVICE_UUID: UUID = UUID.fromString("0000feaa-0000-1000-8000-00805f9b34fb")

    const val EDDYSTONE_FRAME_UID = 0x00
    const val EDDYSTONE_FRAME_URL = 0x10
    const val EDDYSTONE_FRAME_TLM = 0x20

    /** Human label for [EDDYSTONE_SERVICE_UUID] service data's own first byte — the open
     *  Eddystone spec's frame-type discriminator, not anything DX-CP27-specific. */
    fun eddystoneFrameLabel(serviceData: ByteArray): String = when (serviceData.firstOrNull()?.toInt()?.and(0xff)) {
        EDDYSTONE_FRAME_UID -> "Eddystone-UID"
        EDDYSTONE_FRAME_URL -> "Eddystone-URL"
        EDDYSTONE_FRAME_TLM -> "Eddystone-TLM"
        null -> "Eddystone (empty service data)"
        else -> "Eddystone (unknown frame type 0x%02x)".format(serviceData[0])
    }

    private const val IBEACON_AD_TYPE = 0x02
    private const val IBEACON_AD_LENGTH = 0x15

    /** Decoded straight off the advertisement packet, not read over GATT — see
     *  [parseIBeaconAdvertisement]. */
    data class AdvertisedIBeacon(
        val uuid: ByteArray,
        val major: Int,
        val minor: Int,
        val measuredPowerDbm: Int,
    ) {
        val uuidString: String get() = formatUuidBytes(uuid)

        override fun equals(other: Any?): Boolean = other is AdvertisedIBeacon &&
            uuid.contentEquals(other.uuid) && major == other.major && minor == other.minor &&
            measuredPowerDbm == other.measuredPowerDbm

        override fun hashCode(): Int = ((uuid.contentHashCode() * 31 + major) * 31 + minor) * 31 + measuredPowerDbm

        /** True once this frame's UUID matches [RACEMASTER_IBEACON_UUID] — recognizes a beacon an
         *  *older* version of this app programmed (back when it wrote an iBeacon frame rather
         *  than [RACE_SLOT]'s device name); distinguishes that from "still factory default, or
         *  programmed by DX-SMART/something else", since major/minor/power all vary freely
         *  either way. A beacon this app programs now has no iBeacon frame at all. */
        val isProgrammedByThisApp: Boolean get() = uuid.contentEquals(RACEMASTER_IBEACON_UUID)
    }

    /** Decodes Apple's standard iBeacon layout (type 0x02, length 0x15, 16-byte UUID, major/minor
     *  big-endian, 1-byte signed measured power) out of the [APPLE_COMPANY_ID] manufacturer data
     *  Kable hands back from a scan advertisement — `null` if this advert has no such data, or it
     *  doesn't look like an iBeacon frame (e.g. the beacon is currently advertising its
     *  device-name frame instead). */
    fun parseIBeaconAdvertisement(manufacturerData: ByteArray?): AdvertisedIBeacon? {
        if (manufacturerData == null || manufacturerData.size < 23) return null
        if ((manufacturerData[0].toInt() and 0xff) != IBEACON_AD_TYPE) return null
        if ((manufacturerData[1].toInt() and 0xff) != IBEACON_AD_LENGTH) return null
        return AdvertisedIBeacon(
            uuid = manufacturerData.copyOfRange(2, 18),
            major = beU16(manufacturerData, 18),
            minor = beU16(manufacturerData, 20),
            measuredPowerDbm = manufacturerData[22].toInt(),
        )
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
