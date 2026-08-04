package mobile.racemaster.data.mule

import java.util.UUID
import kotlinx.serialization.Serializable
import mobile.racemaster.data.db.entity.PulledRecordEntity

/**
 * GATT profile shared by both sides of a Mule pull: every device (Time, Bibs, and Mule
 * itself) runs a [PeripheralSyncService] advertising this service, and Mule's
 * [MulePullClient] scans/connects for it.
 *
 * Wire shape for the actual record payload is a UTF-8 JSON array of [SyncRecord],
 * transferred over [DATA_CHARACTERISTIC_UUID] as a sequence of notifications terminated by
 * a single [END_OF_STREAM_MARKER] byte, since a BLE notification's payload is capped by the
 * negotiated MTU and this keeps reassembly on the central side trivial (concatenate until
 * the marker, then decode). The central requests [REQUESTED_MTU] after connecting; the
 * peripheral tracks whatever MTU actually gets negotiated (Android never guarantees the
 * requested value) and sizes each device's chunks to fit it — see
 * [MulePullClient.pull] and [PeripheralSyncService]'s `onMtuChanged`. [FALLBACK_CHUNK_SIZE_BYTES]
 * is what a device gets streamed at if no MTU negotiation happened at all: the guaranteed-safe
 * value for the un-negotiated default ATT MTU of 23 bytes (23 − 3 bytes of ATT header).
 */
object MuleGattProfile {
    val SERVICE_UUID: UUID = UUID.fromString("6d6f6269-6c65-2e72-6163-656d61737465")

    /** Read-only: JSON-encoded [DeviceInfo] describing this device and what it's holding. */
    val DEVICE_INFO_CHARACTERISTIC_UUID: UUID = UUID.fromString("6d6f6269-6c65-2e72-6163-000000000001")

    /** Write: the central writes a JSON-encoded [PullRequest] here to request a record stream. */
    val CONTROL_CHARACTERISTIC_UUID: UUID = UUID.fromString("6d6f6269-6c65-2e72-6163-000000000002")

    /** Notify: chunked JSON array of [SyncRecord], see class doc above. */
    val DATA_CHARACTERISTIC_UUID: UUID = UUID.fromString("6d6f6269-6c65-2e72-6163-000000000003")

    /** Write: the central writes a JSON-encoded [AckPayload] back here once the stream is
     *  fully reassembled, so the peripheral knows it's safe to mark those records synced. */
    val ACK_CHARACTERISTIC_UUID: UUID = UUID.fromString("6d6f6269-6c65-2e72-6163-000000000004")

    const val REQUESTED_MTU = 247
    const val FALLBACK_CHUNK_SIZE_BYTES = 20

    // Android's BluetoothGattServer enforces a hard max GATT attribute value length of 512
    // bytes (GATT_MAX_ATTR_LEN) — notifyCharacteristicChanged throws IllegalArgumentException
    // for anything longer, uncaught-crashing the whole app in the field (confirmed via a real
    // device crash log). The requested/negotiated MTU is not a reliable upper bound on its
    // own: Android doesn't guarantee it matches what was requested, and some stacks negotiate
    // larger than REQUESTED_MTU. Chunk size must always be capped to this regardless of what
    // onMtuChanged reports.
    const val MAX_SAFE_CHUNK_SIZE_BYTES = 509
    const val END_OF_STREAM_MARKER: Byte = 0

    // Single source of truth for how often anything pulling from a device on this protocol
    // should re-poll it — this device's own [MuleSyncEngine] auto-sync loop uses it directly,
    // and it's also reported to every puller via [DeviceInfo.pollIntervalMs] so a puller (e.g.
    // the racemaster web app acting as a Mule) never has to hardcode/guess a cadence of its
    // own that could drift out of step with this one.
    //
    // Deliberately NOT the basis for MulePullClient's own CONNECT_TIMEOUT/PULL_TIMEOUT (they
    // used to scale off this 1:1) — a low poll interval is safe to tune on its own for latency
    // (N-hop sink-confirmation propagation is roughly hops × this value), but a real BLE
    // connect/pull handshake has its own, unrelated, hardware-bound timing floor. Coupling them
    // meant lowering this to shave latency also shrank how long a single pull is allowed to
    // take, which risked spurious failures on exactly the budget/older chipsets this codebase
    // already has extensive field notes about struggling with connection setup — see those
    // constants' own doc.
    const val RECOMMENDED_POLL_INTERVAL_MS = 5_000L

    // Manufacturer-data company ID used to carry AdvertisedIdentity in the scan-response packet
    // (see PeripheralSyncService.startAdvertising / MulePullClient.decodeAdvertisedIdentity).
    // 0xFFFF is the Bluetooth SIG's own reserved "for testing" ID — this app has no registered
    // Company ID of its own (a real one costs real money and months of lead time for what's
    // purely an optimization hint, never a value anything depends on for correctness).
    // ADVERTISING_MAGIC is a defense-in-depth disambiguator in case some other nearby BLE
    // peripheral also happens to reuse 0xFFFF for its own unrelated purpose.
    const val ADVERTISING_MANUFACTURER_ID = 0xFFFF
    val ADVERTISING_MAGIC = byteArrayOf(0x52, 0x4d) // "RM"
    const val ADVERTISING_FORMAT_VERSION: Byte = 1

    // Legacy BLE scan-response payload is capped at 31 bytes; addManufacturerData costs 4 of
    // those (2-byte AD length+type header + 2-byte company ID) before our own bytes even start.
    // Our own header (2-byte magic + 1-byte version + 4-byte counter + 1-byte nameLen) is 8
    // bytes, leaving 31 - 4 - 8 = 19 for the name — see encodeAdvertisedIdentity.
    const val ADVERTISED_NAME_MAX_BYTES = 19

    /** Decoded contents of the scan-response payload advertised alongside this device's GATT
     *  service — a cheap, non-authoritative hint a scanner can read without ever connecting, so
     *  [MuleSyncEngine] can skip a real GATT connect+[DeviceInfo] read for a device it already
     *  knows is unchanged. [lastLineNumber] mirrors [DeviceInfo.lastLineNumber] for this
     *  device's own race only — never a relayed origin's — since relay-manifest freshness still
     *  rides on a periodic real connect (see MuleSyncEngine's VERIFY_INTERVAL). Every value here
     *  must be re-confirmed by a real [DeviceInfo] read before being relied on for anything
     *  correctness-sensitive; this is only ever used to decide *whether* to bother connecting. */
    data class AdvertisedIdentity(val lastLineNumber: Long, val deviceName: String)

    /** Builds the scan-response manufacturer-data payload for [deviceName]/[lastLineNumber] —
     *  see [ADVERTISED_NAME_MAX_BYTES] for the byte budget this stays within. Truncates on a
     *  UTF-8 codepoint boundary (never mid-character) if [deviceName] doesn't fit. */
    fun encodeAdvertisedIdentity(lastLineNumber: Long, deviceName: String): ByteArray {
        var truncated = deviceName
        while (truncated.encodeToByteArray().size > ADVERTISED_NAME_MAX_BYTES) {
            truncated = truncated.substring(0, truncated.length - 1)
        }
        val nameBytes = truncated.encodeToByteArray()
        val buffer = java.nio.ByteBuffer.allocate(ADVERTISING_MAGIC.size + 1 + 4 + 1 + nameBytes.size)
        buffer.put(ADVERTISING_MAGIC)
        buffer.put(ADVERTISING_FORMAT_VERSION)
        buffer.putInt(lastLineNumber.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        buffer.put(nameBytes.size.toByte())
        buffer.put(nameBytes)
        return buffer.array()
    }

    /** Inverse of [encodeAdvertisedIdentity] — always returns null rather than throwing for
     *  anything malformed: missing scan response (`bytes` null — common on a scan window that
     *  missed it, or an older peer build that predates this payload entirely), wrong magic, an
     *  unrecognized [ADVERTISING_FORMAT_VERSION] (a future build's wire format), or a
     *  too-short/truncated array. Every caller treats null as "unknown" and falls back to
     *  behaving exactly as if this payload didn't exist at all. */
    fun decodeAdvertisedIdentity(bytes: ByteArray?): AdvertisedIdentity? {
        if (bytes == null || bytes.size < ADVERTISING_MAGIC.size + 1 + 4 + 1) return null
        if (!bytes.copyOfRange(0, ADVERTISING_MAGIC.size).contentEquals(ADVERTISING_MAGIC)) return null
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        buffer.position(ADVERTISING_MAGIC.size)
        val version = buffer.get()
        if (version != ADVERTISING_FORMAT_VERSION) return null
        val lastLineNumber = buffer.int.toLong()
        val nameLen = buffer.get().toInt() and 0xff
        if (buffer.remaining() < nameLen) return null
        val nameBytes = ByteArray(nameLen)
        buffer.get(nameBytes)
        val deviceName = runCatching { nameBytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
            ?: return null
        return AdvertisedIdentity(lastLineNumber, deviceName)
    }
}

/** One other, genuinely different device this one is holding relayable data for — everything
 *  currently in its own `pulled_records` inbox, grouped by true origin (never this device's own
 *  race; a mule's own race is already covered by [DeviceInfo.raceLabel]/[DeviceInfo.lastLineNumber]
 *  directly). Lets a mule-to-mule chain form without a separate sync mechanism: a puller reading
 *  this list treats each entry exactly like a direct leaf pull's own [DeviceInfo] — same
 *  [MuleRepository.lastPulledLineNumber] delta comparison, same resume cursor, just requested via
 *  [PullRequest.originDeviceId] instead of implicitly. [originDeviceId]/[originDeviceName] are the
 *  data's true creator, never the device being connected to right now — that's what keeps origin
 *  identity intact across arbitrary hop depth (see [PeripheralSyncService]'s relay manifest doc).
 *
 *  Fetched as its own separate, chunked pull (see [PullRequest.requestRelayManifest] and
 *  [MulePullClient.pullRelayManifest]) rather than embedded in [DeviceInfo] — a Mule relaying
 *  data for more than a handful of devices could otherwise grow that single-read characteristic
 *  straight past the ATT protocol's own 512-byte attribute value cap, with no way to serve a
 *  larger value at all (unlike this list's own dedicated pull, which streams over the same
 *  chunked-notify DATA characteristic a records pull already uses, so it isn't bounded by that
 *  cap regardless of how many devices are being relayed). */
@Serializable
data class RelayManifestEntry(
    val originDeviceId: String,
    val originDeviceName: String,
    val originRaceLabel: String,
    val lastLineNumber: Long,
)

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val raceLabel: String,
    // The highest permanent history line number (RaceEntity.nextLineNumber - 1) this device
    // currently holds for its active race — 0 if it holds none. A puller compares this
    // against whatever it's already pulled from this specific device (see
    // MuleRepository.lastPulledLineNumber) to decide whether there's a delta worth pulling,
    // and if so, requests only lines after that point (see PullRequest) — replaces the old
    // unsyncedCount-based "pull everything currently unsynced" model.
    val lastLineNumber: Long,
    val deviceName: String = "",
    // How many other, genuinely different origin devices this device is currently holding
    // relayable data for (its pulled-from-others inbox) — 0 for a device that's never pulled
    // from anyone (an ordinary Time/Bibs/CP phone, or a mule that hasn't relayed anything yet).
    // Just a count, not the manifest itself: see RelayManifestEntry's own doc for why the actual
    // list is fetched via its own separate pull instead of riding along in this single-read
    // characteristic. A puller only bothers with that extra round trip when this is > 0.
    val relayCount: Int = 0,
    // How often (in ms) a puller should re-poll this device — see
    // MuleGattProfile.RECOMMENDED_POLL_INTERVAL_MS's own doc for why this is reported rather
    // than left for every puller to hardcode independently. Defaulted (rather than required) so
    // a puller running newer code than an old, already-installed peripheral still deserializes
    // its DeviceInfo fine and just falls back to this same default.
    val pollIntervalMs: Long = MuleGattProfile.RECOMMENDED_POLL_INTERVAL_MS,
)

/** Written to [MuleGattProfile.CONTROL_CHARACTERISTIC_UUID] to request either a delta stream of
 *  every line after [sinceLineNumber] (0 to request the device's entire history) — of the
 *  responding device's own race by default ([originDeviceId] null), or of a specific
 *  [RelayManifestEntry] it's relaying on behalf of another device ([originDeviceId] +
 *  [originRaceLabel] set, paired the same way [PulledRecordEntity]'s own sourceDeviceId +
 *  sourceRaceLabel always are — a deviceId alone isn't guaranteed unique to one race label
 *  over a device's lifetime) — or, when [requestRelayManifest] is true, the responding device's
 *  own current `List<RelayManifestEntry>` instead of any records at all ([sinceLineNumber]/
 *  [originDeviceId]/[originRaceLabel] are ignored for that request; see
 *  [MulePullClient.pullRelayManifest]/[PeripheralSyncService]'s own handling). Either way the
 *  response streams back over [MuleGattProfile.DATA_CHARACTERISTIC_UUID] the same
 *  chunked-notify way.
 *
 *  [requestKey] identifies this exact ask so a responder that's already answered it once (e.g.
 *  the same requester retrying after a dropped connection, or the same auto-sync tick asking
 *  again before its own cursor has advanced) can replay its cached response instead of
 *  redoing the DB query/encode — see PeripheralSyncService's request-response cache. Deliberately
 *  a *deterministic* function of who's asking for what (see MulePullClient/mule-ble.js's
 *  `computeRequestKey`), not a fresh random value per call — a random key would never collide
 *  with itself and so could never actually get deduped. Left null (rather than required) so an
 *  old-build requester talking to a new-build responder still decodes fine and simply gets no
 *  dedup benefit — same graceful-degradation precedent as [DeviceInfo.pollIntervalMs]. */
@Serializable
data class PullRequest(
    val sinceLineNumber: Long,
    val originDeviceId: String? = null,
    val originRaceLabel: String? = null,
    val requestRelayManifest: Boolean = false,
    val requestKey: String? = null,
)

/** Written to [MuleGattProfile.ACK_CHARACTERISTIC_UUID] once a pulled stream is fully
 *  reassembled and durably stored, so the peripheral knows it's safe to mark those records
 *  synced. [deviceId] identifies the puller — lets the peripheral attribute each acked line
 *  to whichever device just took it (see PeripheralSyncService.markSynced). [deviceName] is
 *  the puller's own memorable name, carried alongside so the "synced to" feedback shown in
 *  Race History can display something more useful than a raw UUID.
 *
 *  [isSink] and [sinkConfirmedRecordUuids] are what turn a plain "somebody took a copy" ack
 *  into "this data has genuinely reached a sink" — a real destination (the racemaster server,
 *  or a Bluetooth device that identifies as one, e.g. the racemaster web app's own BLE client),
 *  as opposed to just another relay mule. [isSink] is the acker's own identity: true only for
 *  a genuine sink, always false for an ordinary racemaster-mobile phone acting as Mule.
 *  [recordUuids] alone (isSink false) means "relayed to a mule, not yet sink-confirmed" — the
 *  new intermediate (orange) state; [recordUuids] with isSink true means those lines are fully
 *  confirmed (green) right now. [sinkConfirmedRecordUuids] is the separate back-channel that
 *  lets that confirmation climb back through however many mule hops separate a sink from the
 *  device that originally recorded a line: a mule piggybacks every recordUuid it has separately
 *  learned is sink-confirmed (via its own successful server push, or a downstream device's own
 *  ack) onto its next routine ack to whoever it pulls from — see PeripheralSyncService.markSynced
 *  and MuleRepository.pullFrom for the two ends of this. Both new fields default so an ack from
 *  an unmodified older build still decodes fine (every `Json` instance in this codebase already
 *  sets `ignoreUnknownKeys = true`), just without ever reaching the new green state via that
 *  path. */
@Serializable
data class AckPayload(
    val deviceId: String,
    val recordUuids: List<String>,
    val deviceName: String = "",
    val isSink: Boolean = false,
    val sinkConfirmedRecordUuids: List<String> = emptyList(),
)

/**
 * One transferable record — Time Mode splits and Bibs Mode entries both flatten into this
 * same shape. Lands in the racemaster server's own `mobile` array (kept distinct from its
 * existing `finishers` array, not merged into it) via `recordUuid` (for dedup). `splitTime`
 * (elapsed-since-race-start) is only meaningful for Time Mode splits — Bibs Mode has no
 * stopwatch of its own, so its records leave `splitTime` null and rely purely on
 * `timestampMillis`, the raw wall-clock instant the record was created.
 *
 * `bibNumber` is a *string*, not an Int, and is null only for a genuine Time Mode record
 * (which has no bib concept at all) — that's the signal the server's own discriminator uses
 * to tell a Time record from a Bibs one (see server.js's own doc: `bibNumber == null` means
 * Time, `splitTime == null` means Bibs). Every Bibs record therefore always carries a non-null
 * `bibNumber`, even one with no bib of its own (Clock, Stop, Reset, ...) — those send the
 * literal string `"n/a"` (matching how the app's own history list already displays them; see
 * HistoryLineRow) rather than null, so a Bibs record can never accidentally read as
 * wire-identical to a Time record (both `bibNumber` and `splitTime` null) just because it
 * happens to have no bib of its own. See [toSyncRecord] for where this is computed.
 *
 * Deliberately carries no `deviceName`: every place this travels (a BLE pull stream, a
 * `PulledRecordEntity` row, a server push/status entry) is already scoped to one originating
 * device — via `DeviceInfo.deviceName` on the wire, `PulledRecordEntity.deviceName` locally,
 * or the enclosing `deviceName` key server-side — so repeating it on every line would be pure
 * redundancy, not information.
 *
 * `location` (RaceEntity.location's own doc) is the opposite case: it genuinely is repeated on
 * every single line, even though it's constant for the whole race — there's no separate
 * per-race metadata channel in this wire protocol to send it through just once the way
 * `deviceName` rides on [DeviceInfo] instead, so each record carries its own copy.
 */
@Serializable
data class SyncRecord(
    val recordUuid: String,
    val action: String,
    val bibNumber: String?,
    val splitTime: String?,
    val location: String,
    val splitNumber: Int?,
    // Permanent, ascending history position — see RaceEntity.nextLineNumber. What delta-sync
    // (both the BLE pull protocol and the server's mobile-sync endpoint) keys off.
    val lineNumber: Long,
    // Non-null for an edit-echo/undo-marker row — points at the original ROOT row's
    // lineNumber (see HistoryLineEntity's own refLineNumber doc). Carried over BLE/HTTP purely
    // for downstream (e.g. the racemaster web app) replay purposes — nothing in this repo
    // needs to interpret it once it's synced.
    val refLineNumber: Long? = null,
    val note: String?,
    val timestampMillis: Long,
)
