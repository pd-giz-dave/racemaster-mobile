package mobile.racemaster.data.mule

import android.bluetooth.le.ScanSettings
import android.util.Log
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.Collections
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
    // SCAN_MODE_BALANCED (was SCAN_MODE_LOW_LATENCY), not Android's un-set default of
    // SCAN_MODE_LOW_POWER (a short scan window over a long interval) — every phone running
    // Mule Mode is simultaneously scanning *and* advertising/serving a GATT server on the same
    // radio, and budget/older BLE chipsets time-share those roles poorly. Confirmed in the
    // field on exactly this kind of hardware: a scan that's only "listening" for a small
    // fraction of the time has far less chance of a window landing on a peer's advertisement
    // burst while also fending off this device's own advertiser for airtime. This can't fix a
    // controller that genuinely can't run both roles at once, but it meaningfully improves the
    // odds on the (more common) chipsets that can, just inconsistently at low duty cycle.
    //
    // Deliberately stepped down only to BALANCED, not all the way to LOW_POWER, even though
    // PeripheralSyncService's advertise side did move to its own low-power mode as part of this
    // same change — going straight to LOW_POWER scan *combined with* a low-power advertise
    // interval on every peer risked compounding (multiplicatively, not additively) the exact
    // scan-window/advertise-interval mismatch failure this comment already documents above.
    // Revisit empirically against real multi-device field testing before ever going lower.
    @OptIn(ObsoleteKableApi::class)
    private val scanner = Scanner {
        scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
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

    // Kable's Peripheral becomes permanently unusable once a connect attempt against it is
    // externally cancelled (e.g. by CONNECT_TIMEOUT below firing while .connect() is still
    // suspended) — a later .connect() on that exact same instance then throws
    // IllegalStateException("Cannot connect peripheral that has been cancelled") rather than
    // retrying, since peripheralFor() above otherwise reuses the identical cached instance
    // forever (see its own doc for why that reuse exists at all — it's real and still needed
    // for the healthy case). Confirmed in the field: a source device whose connect timed out
    // once, deep into a long-running session, then failed *every* subsequent attempt against
    // it — including the much cheaper readDeviceInfo, not just pull()'s longer round trip — for
    // the rest of that process's life, and cleared immediately (first attempt succeeded) the
    // moment the app restarted, i.e. the moment peripherals started out empty again. Evicting
    // the cached instance here on any connect failure (not narrowly matched to that one
    // exception type, in case Kable's exact wording ever changes) is what makes restarting the
    // whole app unnecessary — the next attempt against this address gets a genuinely fresh
    // Peripheral instead of inheriting a poisoned one. Never swallows the failure itself: always
    // rethrown, so every existing call site's own runCatching/error handling is unaffected.
    private fun evictAfterFailedConnect(advertisement: Advertisement) {
        peripherals.remove(advertisement.identifier)
    }

    // Shared by pull()/pullRelayManifest() — their identical
    // "withTimeout(CONNECT_TIMEOUT) { peripheral.connect() }" plus the evictAfterFailedConnect
    // call above on any failure. readDeviceInfo doesn't use this: its own CONNECT_TIMEOUT
    // deliberately bounds the read (and, now, an optional piggybacked ack write) that follows
    // too, not just the connect, so it wraps its connect+evict by hand instead.
    private suspend fun connectOrEvict(advertisement: Advertisement, peripheral: Peripheral) {
        try {
            withTimeout(CONNECT_TIMEOUT) { peripheral.connect() }
        } catch (e: Throwable) {
            evictAfterFailedConnect(advertisement)
            throw e
        }
    }

    // One Mutex per address, guarding the connect→operate→disconnect sequence in
    // readDeviceInfo/pull/pullRelayManifest below — confirmed in the field as the actual cause
    // of a device stuck at "Discovering…" (or never resolving at all): a freshly-discovered
    // device gets an independent connect from both MuleSyncEngine.refreshDeviceInfo (its own
    // first-sighting resolve) and the very next pullAllVisibleDevices tick (shouldConnect
    // returns true unconditionally for anything still unresolved), and since both share the
    // one Peripheral object peripheralFor() reuses for a given address, two concurrent
    // connect()/read()/disconnect() sequences on it interleave — observed live as one side's
    // disconnect() throwing NotConnectedException mid-read on the other, and the other's own
    // characteristic read coming back corrupted/truncated (a JsonDecodingException on visibly
    // mangled bytes, two responses' worth spliced together). connectSemaphore doesn't prevent
    // this — it only bounds how many *different* devices are connected at once, not repeat
    // concurrent use of the *same* one. A device with only one resolved connect at a time never
    // hits this; only ever seen on a still-unresolved device precisely because it's the one
    // case two independent call sites both decide to connect to right away.
    private val peripheralMutexes = mutableMapOf<String, Mutex>()

    @Synchronized
    private fun mutexFor(advertisement: Advertisement): Mutex =
        peripheralMutexes.getOrPut(advertisement.identifier) { Mutex() }

    // Temporary/diagnostic — added while chasing a peer that never resolves out of
    // "Discovering…" (or never appears at all) despite both ends' own advertising/scanning
    // otherwise looking healthy. Logs every distinct BLE address seen at all (once each, not
    // per-callback — this scan is unfiltered at the OS level, see scanForDevices' own doc, so
    // logging every callback would mean logging every ambient BLE device in range on every
    // ~scan interval) so a peer that never even reaches the SERVICE_UUID filter below is
    // distinguishable from one that's seen but doesn't match it — those are different bugs
    // (nothing physically arriving vs. a filter/payload mismatch) that otherwise look identical
    // from MuleSyncEngine's side (a device that just never shows up).
    private val loggedRawAddresses = Collections.synchronizedSet(mutableSetOf<String>())

    fun scanForDevices(): Flow<Advertisement> = scanner.advertisements
        .onEach { advertisement ->
            if (loggedRawAddresses.add(advertisement.identifier)) {
                Log.d(
                    TAG,
                    "raw scan result (first sighting): address=${advertisement.identifier} " +
                        "name=${advertisement.name} rssi=${advertisement.rssi} uuids=${advertisement.uuids} " +
                        "hasServiceUuid=${MuleGattProfile.SERVICE_UUID.toKotlinUuid() in advertisement.uuids}",
                )
            }
        }
        .filter { advertisement -> MuleGattProfile.SERVICE_UUID.toKotlinUuid() in advertisement.uuids }

    /** Cheap, non-authoritative identity hint read straight off [advertisement]'s scan-response
     *  manufacturer data — no BLE connect involved (pure byte parsing of data already delivered
     *  by [scanForDevices]'s own scan callback). See [MuleSyncEngine]'s connect-gating logic for
     *  why this exists: deciding whether a real, expensive [readDeviceInfo] connect is even
     *  worth attempting. Null whenever nothing usable was advertised (missed scan window, a
     *  peer running an older build that predates this payload, or this scan simply not carrying
     *  a scan response at all) — every caller treats that exactly like "unknown," never as a
     *  signal on its own. */
    fun decodeAdvertisedIdentity(advertisement: Advertisement): MuleGattProfile.AdvertisedIdentity? =
        MuleGattProfile.decodeAdvertisedIdentity(advertisement.manufacturerData(MuleGattProfile.ADVERTISING_MANUFACTURER_ID))

    // The connect phase, the read phase, and (when present) each ack-write batch each get
    // their own independent timeout budget via connectOrEvict/READ_TIMEOUT/ACK_WRITE_TIMEOUT,
    // rather than one shared CONNECT_TIMEOUT covering all of them together — confirmed in the
    // field as a real, self-inflicted regression from an earlier version of this function that
    // *did* share one combined budget: adding the optional ack write on top of connect+read
    // pushed the combined total past CONNECT_TIMEOUT on a marginal link often enough that even
    // the plain read (which used to succeed here reliably on its own, in well under
    // CONNECT_TIMEOUT) started failing 100% of the time too, just from sharing a clock with a
    // step that hadn't even been added yet when that budget was originally sized. Each phase
    // failing (or not) on its own genuine merits, independently, is what a device with a
    // basically-fine connect but an occasionally-slow read (or vice versa) actually needs.
    //
    // [sinkConfirmedRecordUuids] (default empty — every existing caller's own behavior is
    // unchanged) lets a caller that's about to connect anyway for a routine DeviceInfo refresh
    // also deliver an already-owed confirmation in that exact same connection, instead of a
    // separate relayConfirmationOnly call reconnecting moments later. That separate-reconnect
    // shape was tried first and confirmed in the field as a *different* 100%-reproducible
    // failure against a real device: this same readDeviceInfo connect, immediately followed by
    // a fresh relayConfirmationOnly connect to the identical peripheral moments later, failed at
    // the connect step every single time (always right at CONNECT_TIMEOUT) even though this
    // read's own connect kept succeeding fast and reliably — some BLE stacks evidently can't
    // (or won't) accept a second incoming connection from the same central right after the
    // previous one from it just disconnected. Doing both under the one connection this pairs
    // into sidesteps that regardless of its exact cause. [pullerDeviceId] is required whenever
    // [sinkConfirmedRecordUuids] is non-empty (asserted, not silently ignored, so a caller
    // wiring this up wrong fails loudly rather than the confirmation silently never going out).
    suspend fun readDeviceInfo(
        advertisement: Advertisement,
        pullerDeviceId: String? = null,
        pullerDeviceName: String = "",
        sinkConfirmedRecordUuids: List<String> = emptyList(),
        onConfirmationsRelayed: suspend (List<String>) -> Unit = {},
    ): DeviceInfo = mutexFor(advertisement).withLock {
        require(sinkConfirmedRecordUuids.isEmpty() || pullerDeviceId != null) {
            "pullerDeviceId is required when sinkConfirmedRecordUuids is non-empty"
        }
        coroutineScope {
            val peripheral = peripheralFor(advertisement)
            connectOrEvict(advertisement, peripheral)
            try {
                withTimeout(READ_TIMEOUT) {
                    // Missing before, unlike collectChunkedResponse's identical call (used by
                    // pull()/pullRelayManifest()) — confirmed in the field as the actual cause of
                    // a device stuck at "Discovering…": DeviceInfo's JSON easily exceeds the
                    // un-negotiated default ATT MTU (23 bytes, ~20 usable), so without this the
                    // read falls back to Android's own multi-fragment "read blob" reassembly —
                    // which on at least one real budget/rugged chipset reproducibly corrupted the
                    // result (the same read, retried repeatedly seconds apart against unchanged
                    // data, came back with an identical byte-for-byte splice of two fragments,
                    // never a transient/random glitch). Requesting the larger MTU first lets the
                    // whole value fit in a single ATT response instead, sidestepping that
                    // reassembly path entirely. Best-effort like collectChunkedResponse's own copy
                    // — if negotiation fails/isn't supported, this falls back to the same
                    // read-blob path as before, no worse than today.
                    runCatching { (peripheral as? AndroidPeripheral)?.requestMtu(MuleGattProfile.REQUESTED_MTU) }
                }
                val characteristic = characteristicOf(
                    service = MuleGattProfile.SERVICE_UUID.toKotlinUuid(),
                    characteristic = MuleGattProfile.DEVICE_INFO_CHARACTERISTIC_UUID.toKotlinUuid(),
                )
                val bytes = withTimeout(READ_TIMEOUT) { peripheral.read(characteristic) }
                val info = json.decodeFromString<DeviceInfo>(String(bytes, Charsets.UTF_8))
                if (sinkConfirmedRecordUuids.isNotEmpty()) {
                    val ackCharacteristic = characteristicOf(
                        service = MuleGattProfile.SERVICE_UUID.toKotlinUuid(),
                        characteristic = MuleGattProfile.ACK_CHARACTERISTIC_UUID.toKotlinUuid(),
                    )
                    for (batch in ackBatches(pullerDeviceId!!, pullerDeviceName, emptyList(), sinkConfirmedRecordUuids) { json.encodeToString(it) }) {
                        withTimeout(ACK_WRITE_TIMEOUT) {
                            peripheral.write(ackCharacteristic, json.encodeToString(batch).toByteArray(Charsets.UTF_8), WriteType.WithResponse)
                        }
                        if (batch.sinkConfirmedRecordUuids.isNotEmpty()) {
                            onConfirmationsRelayed(batch.sinkConfirmedRecordUuids)
                        }
                    }
                }
                info
            } finally {
                peripheral.disconnect()
            }
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
    ): Unit = mutexFor(advertisement).withLock {
        coroutineScope {
            val peripheral = peripheralFor(advertisement)
            // Same reasoning as readDeviceInfo's own CONNECT_TIMEOUT — bounds just the connect
            // phase so a stuck handshake can't hang this call forever; PULL_TIMEOUT below already
            // separately bounds the actual data-collection phase once connected.
            connectOrEvict(advertisement, peripheral)
            try {
                val serviceUuid = MuleGattProfile.SERVICE_UUID.toKotlinUuid()
                val ackCharacteristic = characteristicOf(serviceUuid, MuleGattProfile.ACK_CHARACTERISTIC_UUID.toKotlinUuid())

                val requestKey = computeRequestKey(pullerDeviceId, originDeviceId, originRaceLabel, sinceLineNumber)
                val pullRequest = json.encodeToString(PullRequest(sinceLineNumber, originDeviceId, originRaceLabel, requestKey = requestKey))
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
                    // WithResponse means this suspends until the peripheral's GATT response
                    // arrives — PeripheralSyncService now always sends one (see its own doc), but
                    // this timeout is defense-in-depth against any peer (a different app version,
                    // or anything else speaking this protocol) that doesn't, so a stuck peer can't
                    // hang this whole pull forever.
                    withTimeout(ACK_WRITE_TIMEOUT) {
                        peripheral.write(ackCharacteristic, json.encodeToString(batch).toByteArray(Charsets.UTF_8), WriteType.WithResponse)
                    }
                    if (batch.sinkConfirmedRecordUuids.isNotEmpty()) {
                        onConfirmationsRelayed(batch.sinkConfirmedRecordUuids)
                    }
                }
            } finally {
                peripheral.disconnect()
            }
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
    suspend fun pullRelayManifest(advertisement: Advertisement): List<RelayManifestEntry> = mutexFor(advertisement).withLock {
        coroutineScope {
            val peripheral = peripheralFor(advertisement)
            connectOrEvict(advertisement, peripheral)
            try {
                val pullRequest = json.encodeToString(PullRequest(sinceLineNumber = 0, requestRelayManifest = true))
                val payload = collectChunkedResponse(peripheral, pullRequest)
                if (payload.isBlank()) emptyList() else json.decodeFromString(payload)
            } finally {
                peripheral.disconnect()
            }
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
        // Same reasoning as the ack write's own ACK_WRITE_TIMEOUT — a WithResponse write
        // suspends until the peripheral responds, and PULL_TIMEOUT below only bounds the
        // *data collection* phase that follows, not this write itself.
        withTimeout(ACK_WRITE_TIMEOUT) {
            peripheral.write(controlCharacteristic, requestJson.toByteArray(Charsets.UTF_8), WriteType.WithResponse)
        }
        withTimeout(PULL_TIMEOUT) { collectJob.join() }

        chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }.toString(Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "MulePullClient"

        // Deliberately independent of MuleGattProfile.RECOMMENDED_POLL_INTERVAL_MS — see that
        // constant's own doc for why. These bound how long a single real BLE connect/pull
        // handshake is allowed to take, a hardware-bound concern unrelated to how often the
        // sync loop chooses to re-poll; fixed at their original values regardless of how
        // aggressively the poll interval is tuned for latency.
        private val CONNECT_TIMEOUT = 10_000.milliseconds
        private val PULL_TIMEOUT = 15_000.milliseconds

        // Bounds readDeviceInfo's own MTU negotiation + characteristic read, once connected —
        // deliberately its own independent budget, not shared with CONNECT_TIMEOUT above; see
        // that function's own doc for the regression this separation fixes.
        private val READ_TIMEOUT = 10_000.milliseconds

        // Bounds a single WithResponse write's wait for the peripheral's GATT response —
        // see the two call sites' own docs for why this can't just fall under PULL_TIMEOUT.
        private val ACK_WRITE_TIMEOUT = 10_000.milliseconds
    }
}

/**
 * Deterministically identifies one "give me your data since X" ask so a responder that's
 * already answered it once — e.g. this same puller retrying after a dropped connection, or
 * this same auto-sync tick asking again before its own cursor has advanced — can recognize and
 * replay its cached answer (see PeripheralSyncService's request-response cache) instead of
 * redoing the work. Deliberately NOT a fresh random value per call: a random key could never
 * collide with itself, so could never actually get deduped. Scoped to [pullerDeviceId] so two
 * genuinely different requesters asking the same target for the same data always get their own
 * independent key — each still needs its own real stream + ack cycle regardless of how often
 * the underlying data happens to repeat; this is responder-side work-avoidance for repeats of
 * the *same* ask, not cross-requester data dedup (that's already fully handled elsewhere by
 * `recordUuid` + the per-origin `MuleRepository.lastPulledLineNumber` delta cursor).
 */
internal fun computeRequestKey(pullerDeviceId: String, originDeviceId: String?, originRaceLabel: String?, sinceLineNumber: Long): String =
    "$pullerDeviceId:${originDeviceId ?: "self"}:${originRaceLabel.orEmpty()}:$sinceLineNumber"

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
