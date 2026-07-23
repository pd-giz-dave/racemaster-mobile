package mobile.racemaster.data.mule

import android.bluetooth.le.ScanSettings
import com.juul.kable.Advertisement
import com.juul.kable.AndroidPeripheral
import com.juul.kable.ObsoleteKableApi
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toKotlinUuid

/** BLE central: scans for [MuleGattProfile.SERVICE_UUID], connects to a nearby Time/Bibs/Mule
 *  phone, and pulls whatever unsynced records it's holding. */
@OptIn(ExperimentalUuidApi::class)
class MulePullClient {
    private val json = Json { ignoreUnknownKeys = true }

    // Deliberately unfiltered at the Scanner level — Kable's `filters { match { services = ... } }`
    // compiles down to a single native android.bluetooth.le.ScanFilter.setServiceUuid(), which
    // hands matching to the device's own BLE controller/stack. That's a well-documented source
    // of false negatives for custom 128-bit service UUIDs on exactly the kind of budget/older
    // chipsets this app runs on (confirmed in the field: three phones simultaneously running
    // Mule Mode saw wildly inconsistent subsets of each other even after every device's
    // *advertising* was confirmed still active — the timeout/busyFlow fixes elsewhere in this
    // file's history addressed a stuck-connect hang, not this: a scan that simply never
    // delivers a result for another device's advertisement can't be timed out, since nothing is
    // ever pending). Matching [MuleGattProfile.SERVICE_UUID] ourselves against every
    // unfiltered advertisement (see scanForDevices below) moves that check into this process,
    // which is exactly as correct and avoids trusting the controller's own filter hardware/
    // firmware to do it right.
    // SCAN_MODE_LOW_LATENCY (near-continuous listening), not Android's un-set default of
    // SCAN_MODE_LOW_POWER (a short scan window over a long interval) — every phone running
    // Mule Mode is simultaneously scanning *and* advertising/serving a GATT server on the same
    // radio, and budget/older BLE chipsets time-share those roles poorly. Confirmed in the
    // field on exactly this kind of hardware: a scan that's only "listening" for a small
    // fraction of the time has far less chance of a window landing on a peer's advertisement
    // burst while also fending off this device's own advertiser for airtime. This can't fix a
    // controller that genuinely can't run both roles at once, but it meaningfully improves the
    // odds on the (more common) chipsets that can, just inconsistently at low duty cycle.
    @OptIn(ObsoleteKableApi::class)
    private val scanner = Scanner {
        scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    }

    // One Peripheral per device address, reused across calls — a fresh Peripheral(advertisement)
    // on every readDeviceInfo/pull call (which happens every ~15s per discovered device once
    // Mule Mode's periodic refresh loop is running) registers a brand new BLE GATT client with
    // the OS each time; disconnect() alone doesn't release that registration. Confirmed live on
    // a real device: gatt_if climbed continuously (204, 205, 206, 207, 210, 211...) until the
    // system's GATT client table was exhausted, destabilizing Bluetooth for the whole app.
    // Reusing the same Peripheral and just connect()/disconnect()-ing it is Kable's intended
    // usage and keeps this to one registration per device for the life of the process.
    private val peripherals = mutableMapOf<String, Peripheral>()

    private fun peripheralFor(advertisement: Advertisement): Peripheral =
        peripherals.getOrPut(advertisement.identifier) { Peripheral(advertisement) }

    fun scanForDevices(): Flow<Advertisement> = scanner.advertisements
        .filter { advertisement -> MuleGattProfile.SERVICE_UUID.toKotlinUuid() in advertisement.uuids }

    // Bounded so one unresponsive/stuck-mid-handshake device can never hang this call
    // indefinitely — confirmed in the field: a GATT connect() with no timeout of its own can
    // simply never return on some radios/OEM stacks, and since MuleSyncEngine.pullAllVisibleDevices
    // processes devices in a plain sequential loop, one such hang used to wedge that device
    // permanently in "Discovering…" (see DiscoveredDevice's own doc) *and* block every device
    // after it in the same list forever, since the loop itself never moved on. A timeout here
    // turns that into an ordinary, recoverable per-device failure — runCatching at the call
    // site already treats it exactly like a failed read.
    suspend fun readDeviceInfo(advertisement: Advertisement): DeviceInfo = withTimeout(CONNECT_TIMEOUT) {
        val peripheral = peripheralFor(advertisement)
        peripheral.connect()
        try {
            val characteristic = characteristicOf(
                service = MuleGattProfile.SERVICE_UUID.toKotlinUuid(),
                characteristic = MuleGattProfile.DEVICE_INFO_CHARACTERISTIC_UUID.toKotlinUuid(),
            )
            val bytes = peripheral.read(characteristic)
            json.decodeFromString(String(bytes, Charsets.UTF_8))
        } finally {
            peripheral.disconnect()
        }
    }

    /** Connects, requests every line after [sinceLineNumber] (delta-sync — 0 requests the
     *  device's entire history), reassembles the chunked/notified record stream, hands the
     *  records to [onReceived] to persist, and — only once that returns successfully — acks
     *  back the received `recordUuid`s (tagged with [pullerDeviceId]/[pullerDeviceName]) so
     *  the peripheral can attribute and mark them synced. Acking is deliberately gated on
     *  [onReceived] completing without throwing: if it throws (a failed local insert, a
     *  mid-write disconnect,
     *  cancellation, ...), the peripheral never hears about these records and will still offer
     *  them again on the next pull — the safe failure mode is a harmless redundant re-pull
     *  (records are deduped by `recordUuid` on the way in), not the source silently marking
     *  data synced that the mule never actually captured. */
    suspend fun pull(
        advertisement: Advertisement,
        pullerDeviceId: String,
        pullerDeviceName: String,
        sinceLineNumber: Long,
        onReceived: suspend (List<SyncRecord>) -> Unit,
    ): Unit = coroutineScope {
        val peripheral = peripheralFor(advertisement)
        // Same reasoning as readDeviceInfo's own CONNECT_TIMEOUT — bounds just the connect
        // phase so a stuck handshake can't hang this call forever; PULL_TIMEOUT below already
        // separately bounds the actual data-collection phase once connected.
        withTimeout(CONNECT_TIMEOUT) { peripheral.connect() }
        try {
            // The un-negotiated default ATT MTU is only 23 bytes (20 usable per notification
            // after the ATT header) — without this, the peripheral's larger chunks would be
            // silently truncated in transit, corrupting the reassembled JSON. Best-effort: if
            // negotiation fails/isn't supported, the peripheral's onMtuChanged never fires and
            // it falls back to FALLBACK_CHUNK_SIZE_BYTES, which is safe at the default MTU.
            runCatching { (peripheral as? AndroidPeripheral)?.requestMtu(MuleGattProfile.REQUESTED_MTU) }

            val serviceUuid = MuleGattProfile.SERVICE_UUID.toKotlinUuid()
            val controlCharacteristic = characteristicOf(serviceUuid, MuleGattProfile.CONTROL_CHARACTERISTIC_UUID.toKotlinUuid())
            val dataCharacteristic = characteristicOf(serviceUuid, MuleGattProfile.DATA_CHARACTERISTIC_UUID.toKotlinUuid())
            val ackCharacteristic = characteristicOf(serviceUuid, MuleGattProfile.ACK_CHARACTERISTIC_UUID.toKotlinUuid())

            val chunks = mutableListOf<ByteArray>()
            val collectJob = launch {
                peripheral.observe(dataCharacteristic).takeWhile { chunk ->
                    val isEndMarker = chunk.size == 1 && chunk[0] == MuleGattProfile.END_OF_STREAM_MARKER
                    if (!isEndMarker) chunks.add(chunk)
                    !isEndMarker
                }.collect()
            }
            // Gives the notification subscription (CCCD write) time to land on the peripheral
            // before the pull request does — otherwise the first chunks could be sent before
            // we're subscribed and silently dropped.
            delay(300.milliseconds)
            // WithResponse, matching PROPERTY_WRITE (not PROPERTY_WRITE_NO_RESPONSE) declared
            // on this characteristic server-side — Kable's write() defaults to
            // WriteType.WithoutResponse, which fails against a with-response-only
            // characteristic ("writeWithoutResponse property not found").
            val pullRequest = json.encodeToString(PullRequest(sinceLineNumber))
            peripheral.write(controlCharacteristic, pullRequest.toByteArray(Charsets.UTF_8), WriteType.WithResponse)
            withTimeout(PULL_TIMEOUT) { collectJob.join() }

            val payload = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }.toString(Charsets.UTF_8)
            val records = if (payload.isBlank()) emptyList() else json.decodeFromString<List<SyncRecord>>(payload)

            if (records.isNotEmpty()) {
                onReceived(records)
                val ackPayload = json.encodeToString(AckPayload(pullerDeviceId, records.map { it.recordUuid }, pullerDeviceName))
                peripheral.write(ackCharacteristic, ackPayload.toByteArray(Charsets.UTF_8), WriteType.WithResponse)
            }
        } finally {
            peripheral.disconnect()
        }
    }

    companion object {
        private val PULL_TIMEOUT = 15_000.milliseconds
        private val CONNECT_TIMEOUT = 10_000.milliseconds
    }
}
