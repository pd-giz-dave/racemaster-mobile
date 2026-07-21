package mobile.racemaster.data.mule

import java.util.UUID
import kotlinx.serialization.Serializable

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
}

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val deviceRole: String,
    val raceLabel: String,
    // The highest permanent history line number (RaceEntity.nextLineNumber - 1) this device
    // currently holds for its active race — 0 if it holds none. A puller compares this
    // against whatever it's already pulled from this specific device (see
    // MuleRepository.lastPulledLineNumber) to decide whether there's a delta worth pulling,
    // and if so, requests only lines after that point (see PullRequest) — replaces the old
    // unsyncedCount-based "pull everything currently unsynced" model.
    val lastLineNumber: Long,
    val deviceName: String = "",
)

/** Written to [MuleGattProfile.CONTROL_CHARACTERISTIC_UUID] to request a delta stream of
 *  every line after [sinceLineNumber] (0 to request the device's entire history). */
@Serializable
data class PullRequest(val sinceLineNumber: Long)

/** Written to [MuleGattProfile.ACK_CHARACTERISTIC_UUID] once a pulled stream is fully
 *  reassembled and durably stored, so the peripheral knows it's safe to mark those records
 *  synced. [deviceId] identifies the puller — lets the peripheral attribute each acked line
 *  to whichever device just took it (see PeripheralSyncService.markSynced). [deviceName] is
 *  the puller's own memorable name, carried alongside so the "synced to" feedback shown in
 *  Race History can display something more useful than a raw UUID. */
@Serializable
data class AckPayload(val deviceId: String, val recordUuids: List<String>, val deviceName: String = "")

/**
 * One transferable record — Time Mode splits and Bibs Mode entries both flatten into this
 * same shape. Lands in the racemaster server's own `mobile` array (kept distinct from its
 * existing `finishers` array, not merged into it) via `recordUuid` (for dedup). `time`
 * (elapsed-since-race-start, matching `finishers`' convention) is only meaningful for Time
 * Mode splits — Bibs Mode has no stopwatch of its own, so its records leave `time` null and
 * rely purely on `timestampMillis`, the raw wall-clock instant the record was created.
 *
 * Deliberately carries no `deviceName`: every place this travels (a BLE pull stream, a
 * `PulledRecordEntity` row, a server push/status entry) is already scoped to one originating
 * device — via `DeviceInfo.deviceName` on the wire, `PulledRecordEntity.deviceName` locally,
 * or the enclosing `deviceName` key server-side — so repeating it on every line would be pure
 * redundancy, not information.
 */
@Serializable
data class SyncRecord(
    val recordUuid: String,
    val action: String,
    val number: Int?,
    val time: String?,
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
