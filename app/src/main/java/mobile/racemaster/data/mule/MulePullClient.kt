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
    // encodeDefaults = true — see PeripheralSyncService's own doc on its identically-configured
    // Json instance for why a default-valued field (e.g. AckPayload.isSink, PullRequest's null
    // origin fields) must not be silently omitted from the wire payload just because it
    // happens to equal its Kotlin default.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
     *  the peripheral can attribute and mark them relayed. Acking is deliberately gated on
     *  [onReceived] completing without throwing: if it throws (a failed local insert, a
     *  mid-write disconnect,
     *  cancellation, ...), the peripheral never hears about these records and will still offer
     *  them again on the next pull — the safe failure mode is a harmless redundant re-pull
     *  (records are deduped by `recordUuid` on the way in), not the source silently marking
     *  data synced that the mule never actually captured.
     *
     *  [sinkConfirmedRecordUuids] piggybacks a *separate* set of recordUuids this caller already
     *  knows are confirmed at a genuine sink but hasn't yet told this specific source about — see
     *  AckPayload's own doc, and PulledRecordDao.getUnrelayedSinkConfirmedRecordUuidsForSource
     *  for why this is scoped to *un*relayed ones only (a bounded delta of what's genuinely new,
     *  not this source's entire ever-growing confirmed history — required for a large race, e.g.
     *  300 runners, where the full set would otherwise be re-sent every tick forever). Included
     *  in the ack whenever non-empty, regardless of whether this particular pull itself returned
     *  anything new: a mule fully caught up on a device's data may still owe it a freshly-learned
     *  sink confirmation, so the ack must still fire in that case even though `records` ends up
     *  empty. [onConfirmationsRelayed] fires once per batch, only after that batch's ack write
     *  has actually succeeded — same "only mark it done once truly sent" principle as
     *  [onReceived]/`records` above, so a write that fails partway through a large backlog leaves
     *  whatever didn't get out eligible to be retried next tick rather than silently marked
     *  told-but-not-actually-sent. This is now trustworthy end-to-end because
     *  PeripheralSyncService defers its own GATT write-response until it's genuinely finished
     *  applying the ack (see that class's own doc) — a successful write here means the
     *  peripheral durably processed it, not just that the bytes arrived. This device's own
     *  `isSink` is always false here (an ordinary racemaster-mobile phone acting as Mule is
     *  never itself a sink — see AckPayload's own default), left to AckPayload's default rather
     *  than a parameter this call site would always pass the same value for. */
    suspend fun pull(
        advertisement: Advertisement,
        pullerDeviceId: String,
        pullerDeviceName: String,
        sinceLineNumber: Long,
        // Null (the default) requests the peripheral's own race, exactly as before these two
        // params existed. Set together, requests a specific RelayManifestEntry it's relaying on
        // behalf of another device instead — see PullRequest's own doc.
        originDeviceId: String? = null,
        originRaceLabel: String? = null,
        sinkConfirmedRecordUuids: List<String> = emptyList(),
        onReceived: suspend (List<SyncRecord>) -> Unit,
        onConfirmationsRelayed: suspend (List<String>) -> Unit = {},
    ): Unit = coroutineScope {
        val peripheral = peripheralFor(advertisement)
        // Same reasoning as readDeviceInfo's own CONNECT_TIMEOUT — bounds just the connect
        // phase so a stuck handshake can't hang this call forever; PULL_TIMEOUT below already
        // separately bounds the actual data-collection phase once connected.
        withTimeout(CONNECT_TIMEOUT) { peripheral.connect() }
        try {
            val serviceUuid = MuleGattProfile.SERVICE_UUID.toKotlinUuid()
            val ackCharacteristic = characteristicOf(serviceUuid, MuleGattProfile.ACK_CHARACTERISTIC_UUID.toKotlinUuid())

            val pullRequest = json.encodeToString(PullRequest(sinceLineNumber, originDeviceId, originRaceLabel))
            val payload = collectChunkedResponse(peripheral, pullRequest)
            val records = if (payload.isBlank()) emptyList() else json.decodeFromString<List<SyncRecord>>(payload)

            if (records.isNotEmpty()) {
                onReceived(records)
            }
            // Split across as many separate, independently-complete ack writes as needed —
            // see ackBatches' own doc for why a single unchunked write can't safely carry
            // this, especially sinkConfirmedRecordUuids, which could otherwise cover a large
            // race's entire backlog at once (see AckPayload's own doc).
            for (batch in ackBatches(pullerDeviceId, pullerDeviceName, records.map { it.recordUuid }, sinkConfirmedRecordUuids) { json.encodeToString(it) }) {
                peripheral.write(ackCharacteristic, json.encodeToString(batch).toByteArray(Charsets.UTF_8), WriteType.WithResponse)
                if (batch.sinkConfirmedRecordUuids.isNotEmpty()) {
                    onConfirmationsRelayed(batch.sinkConfirmedRecordUuids)
                }
            }
        } finally {
            peripheral.disconnect()
        }
    }

    /** Fetches a peripheral's own current relay manifest — everything else it's holding
     *  relayable data for on behalf of other, genuinely different origin devices (see
     *  RelayManifestEntry's own doc for why this is its own separate, chunked pull rather than
     *  something [readDeviceInfo] returns directly: the manifest can grow arbitrarily large
     *  with however many devices are being relayed, and DEVICE_INFO is a single-read
     *  characteristic bounded by the ATT protocol's own 512-byte attribute value cap). Callers
     *  should only bother with this extra round trip when [DeviceInfo.relayCount] from a prior
     *  [readDeviceInfo] read was greater than 0 — see [MuleSyncEngine.pullAllVisibleDevices]. */
    suspend fun pullRelayManifest(advertisement: Advertisement): List<RelayManifestEntry> = coroutineScope {
        val peripheral = peripheralFor(advertisement)
        withTimeout(CONNECT_TIMEOUT) { peripheral.connect() }
        try {
            val pullRequest = json.encodeToString(PullRequest(sinceLineNumber = 0, requestRelayManifest = true))
            val payload = collectChunkedResponse(peripheral, pullRequest)
            if (payload.isBlank()) emptyList() else json.decodeFromString(payload)
        } finally {
            peripheral.disconnect()
        }
    }

    // Shared by pull()/pullRelayManifest(): negotiates MTU, subscribes to the DATA
    // characteristic, writes [requestJson] to CONTROL, and reassembles the notified chunks
    // into one payload string once the END_OF_STREAM_MARKER lands — everything both requests
    // have in common regardless of what shape (SyncRecord[] vs RelayManifestEntry[]) the
    // resulting JSON decodes as, which stays the caller's own concern. Deliberately does not
    // connect/disconnect the peripheral itself — pull() still needs the connection open
    // afterward to write its own ack, so that stays bracketing this call at each call site.
    private suspend fun collectChunkedResponse(peripheral: Peripheral, requestJson: String): String = coroutineScope {
        // The un-negotiated default ATT MTU is only 23 bytes (20 usable per notification
        // after the ATT header) — without this, the peripheral's larger chunks would be
        // silently truncated in transit, corrupting the reassembled JSON. Best-effort: if
        // negotiation fails/isn't supported, the peripheral's onMtuChanged never fires and
        // it falls back to FALLBACK_CHUNK_SIZE_BYTES, which is safe at the default MTU.
        runCatching { (peripheral as? AndroidPeripheral)?.requestMtu(MuleGattProfile.REQUESTED_MTU) }

        val serviceUuid = MuleGattProfile.SERVICE_UUID.toKotlinUuid()
        val controlCharacteristic = characteristicOf(serviceUuid, MuleGattProfile.CONTROL_CHARACTERISTIC_UUID.toKotlinUuid())
        val dataCharacteristic = characteristicOf(serviceUuid, MuleGattProfile.DATA_CHARACTERISTIC_UUID.toKotlinUuid())

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
        peripheral.write(controlCharacteristic, requestJson.toByteArray(Charsets.UTF_8), WriteType.WithResponse)
        withTimeout(PULL_TIMEOUT) { collectJob.join() }

        chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }.toString(Charsets.UTF_8)
    }

    companion object {
        // Deliberately independent of MuleGattProfile.RECOMMENDED_POLL_INTERVAL_MS — see that
        // constant's own doc for why. These bound how long a single real BLE connect/pull
        // handshake is allowed to take, a hardware-bound concern unrelated to how often the
        // sync loop chooses to re-poll; fixed at their original values regardless of how
        // aggressively the poll interval is tuned for latency.
        private val CONNECT_TIMEOUT = 10_000.milliseconds
        private val PULL_TIMEOUT = 15_000.milliseconds
    }
}

/**
 * Splits [recordUuids] and [sinkConfirmedRecordUuids] across as many separate [AckPayload]s as
 * needed to keep every one of them under [maxEncodedBytes] once JSON-encoded — Android hard-caps
 * a single GATT characteristic write at 512 bytes (`GATT_MAX_ATTR_LEN`; confirmed in the field:
 * a write past that throws `IllegalArgumentException("value should not be longer than max
 * length of an attribute value")`, silently failing every subsequent pull from that device). An
 * ack has no chunking of its own the way the (notify-based) DATA stream does, so an unbounded
 * uuid list has nowhere else to go. [sinkConfirmedRecordUuids] is kept bounded to a genuine delta
 * (see `PulledRecordDao.getUnrelayedSinkConfirmedRecordUuidsForSource`'s own doc), but a source
 * that's been offline a while, or a mule freshly reconnecting after a large backlog piled up
 * (e.g. a 300-runner race), can still owe it more confirmations at once than fit in a single
 * write. Each resulting batch is a fully valid,
 * self-contained [AckPayload] — `PeripheralSyncService.markSynced` already applies every ack as
 * an independent, idempotent update, so sending N small acks instead of one changes nothing
 * about correctness, only how many separate GATT writes it costs. Batch sizing is done by
 * actually encoding each candidate (via [encode]) rather than guessing a safe fixed
 * uuids-per-batch count, since device names have no enforced length limit (see
 * NameDeviceScreen) and are part of every batch's fixed overhead.
 */
internal fun ackBatches(
    deviceId: String,
    deviceName: String,
    recordUuids: List<String>,
    sinkConfirmedRecordUuids: List<String>,
    maxEncodedBytes: Int = MuleGattProfile.MAX_SAFE_CHUNK_SIZE_BYTES,
    encode: (AckPayload) -> String,
): List<AckPayload> {
    fun chunksOf(uuids: List<String>, toPayload: (List<String>) -> AckPayload): List<AckPayload> {
        if (uuids.isEmpty()) return emptyList()
        val batches = mutableListOf<AckPayload>()
        var current: List<String> = emptyList()
        for (uuid in uuids) {
            val candidate = current + uuid
            if (current.isNotEmpty() && encode(toPayload(candidate)).toByteArray(Charsets.UTF_8).size > maxEncodedBytes) {
                batches += toPayload(current)
                current = listOf(uuid)
            } else {
                current = candidate
            }
        }
        batches += toPayload(current)
        return batches
    }
    return chunksOf(recordUuids) { AckPayload(deviceId = deviceId, recordUuids = it, deviceName = deviceName) } +
        chunksOf(sinkConfirmedRecordUuids) {
            AckPayload(deviceId = deviceId, recordUuids = emptyList(), deviceName = deviceName, sinkConfirmedRecordUuids = it)
        }
}
