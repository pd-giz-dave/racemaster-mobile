package mobile.racemaster.data.mule

import com.juul.kable.Advertisement
import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
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

    private val scanner = Scanner {
        filters {
            match {
                services = listOf(MuleGattProfile.SERVICE_UUID.toKotlinUuid())
            }
        }
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

    suspend fun readDeviceInfo(advertisement: Advertisement): DeviceInfo {
        val peripheral = peripheralFor(advertisement)
        peripheral.connect()
        try {
            val characteristic = characteristicOf(
                service = MuleGattProfile.SERVICE_UUID.toKotlinUuid(),
                characteristic = MuleGattProfile.DEVICE_INFO_CHARACTERISTIC_UUID.toKotlinUuid(),
            )
            val bytes = peripheral.read(characteristic)
            return json.decodeFromString(String(bytes, Charsets.UTF_8))
        } finally {
            peripheral.disconnect()
        }
    }

    /** Connects, requests a pull, reassembles the chunked/notified record stream, hands the
     *  records to [onReceived] to persist, and — only once that returns successfully — acks
     *  back the received `recordUuid`s so the peripheral can mark them synced. Acking is
     *  deliberately gated on [onReceived] completing without throwing: if it throws (a failed
     *  local insert, a mid-write disconnect, cancellation, ...), the peripheral never hears
     *  about these records and will still offer them again on the next pull — the safe
     *  failure mode is a harmless redundant re-pull (records are deduped by `recordUuid` on
     *  the way in), not the source silently marking data synced that the mule never actually
     *  captured. */
    suspend fun pull(advertisement: Advertisement, onReceived: suspend (List<SyncRecord>) -> Unit): Unit = coroutineScope {
        val peripheral = peripheralFor(advertisement)
        peripheral.connect()
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
            peripheral.write(controlCharacteristic, MuleGattProfile.PULL_COMMAND.toByteArray(Charsets.UTF_8), WriteType.WithResponse)
            withTimeout(PULL_TIMEOUT) { collectJob.join() }

            val payload = chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }.toString(Charsets.UTF_8)
            val records = if (payload.isBlank()) emptyList() else json.decodeFromString<List<SyncRecord>>(payload)

            if (records.isNotEmpty()) {
                onReceived(records)
                val ackPayload = json.encodeToString(records.map { it.recordUuid })
                peripheral.write(ackCharacteristic, ackPayload.toByteArray(Charsets.UTF_8), WriteType.WithResponse)
            }
        } finally {
            peripheral.disconnect()
        }
    }

    companion object {
        private val PULL_TIMEOUT = 15_000.milliseconds
    }
}
