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

    /** Write: the central writes [PULL_COMMAND] here to request a record stream. */
    val CONTROL_CHARACTERISTIC_UUID: UUID = UUID.fromString("6d6f6269-6c65-2e72-6163-000000000002")

    /** Notify: chunked JSON array of [SyncRecord], see class doc above. */
    val DATA_CHARACTERISTIC_UUID: UUID = UUID.fromString("6d6f6269-6c65-2e72-6163-000000000003")

    /** Write: the central writes the JSON array of received `recordUuid`s back here once the
     *  stream is fully reassembled, so the peripheral knows it's safe to mark them synced. */
    val ACK_CHARACTERISTIC_UUID: UUID = UUID.fromString("6d6f6269-6c65-2e72-6163-000000000004")

    const val PULL_COMMAND = "PULL"
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
    val unsyncedCount: Int,
)

/**
 * One transferable record — Time Mode splits and Bibs Mode entries both flatten into this
 * same shape. Lands in the racemaster server's own `mobile` array (kept distinct from its
 * existing `finishers` array, not merged into it) via `recordUuid` (for dedup). `time`
 * (elapsed-since-race-start, matching `finishers`' convention) is only meaningful for Time
 * Mode splits — Bibs Mode has no stopwatch of its own, so its records leave `time` null and
 * rely purely on `timestampMillis`, the raw wall-clock instant the record was created.
 */
@Serializable
data class SyncRecord(
    val recordUuid: String,
    val action: String,
    val number: Int?,
    val time: String?,
    val splitNumber: Int?,
    val note: String?,
    val timestampMillis: Long,
)
