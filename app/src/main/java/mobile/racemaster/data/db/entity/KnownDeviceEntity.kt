package mobile.racemaster.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per physical Mule device this phone has ever successfully identified over BLE — a
 * durable "seen devices" roster, independent of MuleSyncEngine's own in-memory discoveredFlow
 * (which only tracks devices from the *current* scan session and drops one that's gone quiet
 * past its own threshold, or was never resolved at all — see DiscoveredDevice's own doc for
 * why a flaky/out-of-range budget-chipset device can sit unresolved as "Discovering…" for a
 * long time). Upserted every time a GATT DeviceInfo read resolves a device's name (see
 * MuleSyncEngine.mergeDeviceInfo), so a device that's currently unreachable still shows up by
 * name in MuleModeScreen's "Previously seen" list instead of vanishing entirely once it drops
 * out of discoveredFlow, and can be explicitly forgotten (MuleRepository.forgetKnownDevice) —
 * e.g. an old test device, or a stuck ghost the operator doesn't want to keep waiting on.
 */
@Entity(tableName = "known_devices")
data class KnownDeviceEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val lastSeenAtMillis: Long,
)
