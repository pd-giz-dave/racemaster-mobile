package mobile.racemaster.data.mule

import android.bluetooth.le.ScanSettings
import android.util.Log
import com.juul.kable.Advertisement
import com.juul.kable.AndroidPeripheral
import com.juul.kable.ObsoleteKableApi
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.withContext
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
    //
    // Also disconnects the evicted instance (best-effort, swallowed) before discarding it —
    // without this, only *our own* cache entry was ever forgotten; whatever native
    // BluetoothGatt/gatt_if client registration the OS had already allocated for this attempt
    // before it timed out was never explicitly released, since connectOrEvict is called outside
    // every caller's own try/finally { peripheral.disconnect() }, so that cleanup never runs on
    // a failed connect either. That's exactly the same gatt_if-exhaustion class of bug
    // peripheralFor()'s own reuse was originally built to prevent (see its own doc), just
    // reintroduced via this eviction path instead — confirmed in the field as a device whose
    // connect attempts were originally an intermittent mix of successes/timeouts degrading, over
    // a long session with repeated failures, into every single attempt against every peer
    // failing near-instantly (milliseconds, not CONNECT_TIMEOUT) once the OS's GATT client table
    // was exhausted.
    private suspend fun evictAfterFailedConnect(advertisement: Advertisement, peripheral: Peripheral) {
        peripherals.remove(advertisement.identifier)
        // NonCancellable — see endConnection's own doc below for why a bare suspend disconnect()
        // call here can't be trusted to actually run when the coroutine calling this is itself
        // already being cancelled (e.g. connect() failed *because* an ambient timeout cancelled
        // it), which is one of the two paths that lead here.
        withContext(NonCancellable) { runCatching { peripheral.disconnect() } }
    }

    // Generalizes evictAfterFailedConnect's own reasoning (see its doc) from "the initial
    // connect failed" to "anything at all went wrong after a successful connect, cancellation
    // included" — confirmed in the field (TODO.md's Sony-Mule investigation) as a real,
    // previously-latent gap: readDeviceInfoOnce/pull/pullRelayManifest's own
    // try { ... } finally { peripheral.disconnect() } blocks disconnected on ANY exit, but only
    // ever evicted the cached Peripheral on a *connect* failure — a read/write/settle-delay
    // instead getting cancelled by MuleSyncEngine's own OVERALL_TICK_TIMEOUT (more likely once
    // this file's own settle delays lengthened how long a connection stays open, giving more of
    // them a chance to still be in flight when that 90s ceiling fires) left the *cached* instance
    // exactly as poisoned as an interrupted connect() does, surfacing later as "Auto-pull failed:
    // Cannot connect peripheral that has been cancelled" against a phone that was working
    // perfectly moments earlier — and, per Kable's own documented behavior, on *every* subsequent
    // attempt against that address until the whole app restarts, since peripheralFor() keeps
    // reusing the same (now-permanently-broken) instance forever otherwise.
    //
    // [succeeded] distinguishes a clean return (only disconnect — the common case, no reason to
    // discard a perfectly good cached instance) from any other exit, including cancellation
    // (evict, exactly like evictAfterFailedConnect). The disconnect call itself always runs
    // inside NonCancellable regardless of which branch: a coroutine already in the process of
    // being cancelled skips any further *unprotected* suspension point immediately rather than
    // actually running it, so a bare `peripheral.disconnect()` inside a `finally` block reached
    // via cancellation could silently no-op — never actually telling Kable/the OS to tear down
    // the link — unless explicitly shielded from that. Evicting our own cache entry still helps
    // even then (the next attempt gets a genuinely fresh Peripheral instead of reusing whatever
    // Kable now considers this one to be), but a real disconnect attempt is strictly better when
    // it can actually happen.
    private suspend fun endConnection(advertisement: Advertisement, peripheral: Peripheral, succeeded: Boolean) {
        if (!succeeded) peripherals.remove(advertisement.identifier)
        withContext(NonCancellable) { runCatching { peripheral.disconnect() } }
    }

    // Shared by readDeviceInfo/pull()/pullRelayManifest() — their identical
    // "withTimeout(CONNECT_TIMEOUT) { peripheral.connect() }" plus the evictAfterFailedConnect
    // call above on any failure.
    //
    // Proactively checks peripheral.state before ever calling connect() — a cached Peripheral
    // reused across calls (see peripheralFor's own doc) should always already be Disconnected
    // here: either this is its first-ever use, or the previous call's own
    // try/finally { peripheral.disconnect() } (on success) or evictAfterFailedConnect above (on
    // failure) already returned it there. Kable's own connect() has no independent "are you
    // actually free right now" guard of its own — see evictAfterFailedConnect's own doc for what
    // happens when a connect attempt is abandoned (by our own CONNECT_TIMEOUT) without first
    // telling Kable to stop it: the underlying attempt keeps running orphaned in Kable's own
    // Peripheral-scoped coroutine (entirely independent of whatever coroutine is awaiting
    // connect()), so a later blind connect() call on the same instance doesn't fail outright,
    // it just piles a fresh attempt on top of one Kable still believes is in flight. Finding
    // anything other than Disconnected here means some earlier call path didn't clean up after
    // itself as expected — logged so that's visible — and is treated the same way
    // evictAfterFailedConnect already does: disconnect (cancels and joins whatever's still
    // running) before ever attempting a fresh connect, rather than trusting every call site
    // upstream got its own cleanup right.
    private suspend fun connectOrEvict(advertisement: Advertisement, peripheral: Peripheral) {
        val stateBeforeConnect = peripheral.state.value
        if (stateBeforeConnect !is State.Disconnected) {
            Log.w(TAG, "peripheral not Disconnected before connect (was $stateBeforeConnect) — disconnecting first: address=${advertisement.identifier}")
            runCatching { peripheral.disconnect() }
        }
        try {
            withTimeout(CONNECT_TIMEOUT) { peripheral.connect() }
        } catch (e: Throwable) {
            evictAfterFailedConnect(advertisement, peripheral)
            throw e
        }
        // Mirrors js/mule-ble.js's GATT_CONNECT_SETTLE_MS — see that constant's own doc for the
        // field evidence: a newly-established link resolving connect() quickly but then dying
        // again within 1-3s, before service/characteristic discovery (which this app's own first
        // read/write on a fresh connection triggers implicitly) could finish, traced there to
        // the peripheral's BLE stack still settling its initial, often-conservative connection
        // interval down to a stable one. This app had no equivalent at all before TODO.md's
        // Sony-Mule investigation: every call here went straight from a successful connect() into
        // its first real GATT operation. Doesn't, on its own, cover the *separate* issue
        // readDeviceInfoOnce's own INTER_OPERATION_SETTLE_DELAY exists for (a second operation on
        // an already-settled connection failing) — this settles the link once, right after
        // connect; that one settles between two later operations on the same, already-settled
        // link. Both are kept since neither one's own theory has been ruled out, and there's no
        // evidence either is harmful on its own (unlike the reconnect-cooldown experiment tried
        // and reverted alongside this — see INTER_OPERATION_SETTLE_DELAY's own doc).
        delay(CONNECT_SETTLE_DELAY)
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
    // Retries the whole connect+read(+ack) sequence a couple of times, in-line, before this
    // call gives up — this is the read MuleSyncEngine's markUnreachable reacts to, and without
    // any retry a single transient failure (one dropped ATT response, one busy radio moment)
    // costs a full MuleSyncEngine.VERIFY_INTERVAL (60s) wait for the next attempt, and three
    // such single-shot misses in a row (~3 minutes) before the device is even flagged
    // unreachable. Mirrors the racemaster web app's own MulePullClient equivalent
    // (js/mule-ble.js's DEVICE_INFO_ATTEMPTS/GATT_RECONNECT_TIMEOUT_MS) — added there after
    // exactly this shape of field issue ("gatt.connect() ... reads timing out on attempt after
    // attempt") on that platform's own BLE central. READ_DEVICE_INFO_ATTEMPTS is deliberately
    // small and READ_DEVICE_INFO_RETRY_DELAY short: each attempt already carries its own
    // CONNECT_TIMEOUT+READ_TIMEOUT budget, and this whole call runs inside
    // MuleSyncEngine.pullAllVisibleDevices' connectSemaphore permit — a long retry loop here
    // would starve every other device queued behind that same tick's OVERALL_TICK_TIMEOUT.
    // Retries the connect too (not just the read) since connectOrEvict already leaves the
    // Peripheral cleanly Disconnected on failure — a fresh connect attempt is exactly as valid
    // a retry unit as a bare re-read would be, and covers a failure at either phase.
    suspend fun readDeviceInfo(
        advertisement: Advertisement,
        pullerDeviceId: String? = null,
        pullerDeviceName: String = "",
        sinkConfirmedRecordUuids: List<String> = emptyList(),
        onConfirmationsRelayed: suspend (List<String>) -> Unit = {},
        // Fires (with a short, [describeConnectFailure]-style reason) whenever the best-effort
        // ack write below fails/times out — see that call site's own doc for why this can't just
        // be a Log.w the way every other best-effort fallback in this file already is: unlike
        // those, a still-broken confirmation relay is a real, ongoing field problem (TODO.md's
        // Sony-Mule report) that an operator needs some way to actually see.
        onAckFailure: suspend (String) -> Unit = {},
    ): DeviceInfo {
        require(sinkConfirmedRecordUuids.isEmpty() || pullerDeviceId != null) {
            "pullerDeviceId is required when sinkConfirmedRecordUuids is non-empty"
        }
        return mutexFor(advertisement).withLock {
            var lastError: Throwable? = null
            for (attempt in 1..READ_DEVICE_INFO_ATTEMPTS) {
                try {
                    return@withLock readDeviceInfoOnce(advertisement, pullerDeviceId, pullerDeviceName, sinkConfirmedRecordUuids, onConfirmationsRelayed, onAckFailure)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                    if (attempt < READ_DEVICE_INFO_ATTEMPTS) {
                        Log.w(TAG, "readDeviceInfo attempt $attempt/$READ_DEVICE_INFO_ATTEMPTS failed for address=${advertisement.identifier} — retrying", e)
                        delay(READ_DEVICE_INFO_RETRY_DELAY)
                    }
                }
            }
            throw lastError!!
        }
    }

    private suspend fun readDeviceInfoOnce(
        advertisement: Advertisement,
        pullerDeviceId: String?,
        pullerDeviceName: String,
        sinkConfirmedRecordUuids: List<String>,
        onConfirmationsRelayed: suspend (List<String>) -> Unit,
        onAckFailure: suspend (String) -> Unit,
    ): DeviceInfo = coroutineScope {
        val peripheral = peripheralFor(advertisement)
        // Tagged by phase (connect vs MTU negotiation vs the actual read) rather than left as a
        // bare TimeoutCancellationException — see MulePhaseTimeoutException's own doc
        // for why: TODO.md's "Sony Mule reports every peer unreachable" investigation found the
        // peripheral side proves the read request itself arrived (bumps its own
        // lastPolledAtMillis), yet the central still calls this a plain "timeout" — which phase
        // is actually stuck materially changes the diagnosis (a connect that never completes vs
        // a read whose response never resolves this coroutine, e.g. a missed/misrouted Kable
        // GATT callback), so the previously-undifferentiated label wasn't precise enough to tell
        // those apart from the device's own Nearby-devices row.
        try {
            connectOrEvict(advertisement, peripheral)
        } catch (e: TimeoutCancellationException) {
            throw MulePhaseTimeoutException("connecting", e)
        }
        // See endConnection's own doc for why this is tracked (rather than a bare
        // finally { peripheral.disconnect() }) — anything past this point that doesn't complete
        // normally, cancellation included, must evict the cached Peripheral, not just disconnect
        // it, or a later attempt against this address inherits Kable's "cancelled" instance.
        var succeeded = false
        try {
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
            } catch (e: TimeoutCancellationException) {
                throw MulePhaseTimeoutException("negotiating MTU", e)
            }
            // Read first, same order as originally — see below for why this was briefly tried in
            // the other order and reverted. The critical operation (this read drives reachability
            // and every device's own data-sync state) gets the safest slot; the non-critical,
            // already-best-effort ack below absorbs whatever residual risk this connection's
            // *second* operation still carries.
            val characteristic = characteristicOf(
                service = MuleGattProfile.SERVICE_UUID.toKotlinUuid(),
                characteristic = MuleGattProfile.DEVICE_INFO_CHARACTERISTIC_UUID.toKotlinUuid(),
            )
            val bytes = try {
                withTimeout(READ_TIMEOUT) { peripheral.read(characteristic) }
            } catch (e: TimeoutCancellationException) {
                throw MulePhaseTimeoutException("reading", e)
            }
            val info = json.decodeFromString<DeviceInfo>(String(bytes, Charsets.UTF_8))
            if (sinkConfirmedRecordUuids.isNotEmpty()) {
                val ackCharacteristic = characteristicOf(
                    service = MuleGattProfile.SERVICE_UUID.toKotlinUuid(),
                    characteristic = MuleGattProfile.ACK_CHARACTERISTIC_UUID.toKotlinUuid(),
                )
                // A settle gap between the read above and this write — TODO.md's Sony-Mule
                // investigation went through three rounds before landing here: (1) no delay at
                // all — write reliably timed out; (2) a general post-connect settle
                // (CONNECT_SETTLE_DELAY) plus a per-address reconnect cooldown, mirroring the
                // racemaster web app's own GATT_CONNECT_SETTLE_MS/RECONNECT_COOLDOWN_MS —
                // neither budged this write, and the cooldown in particular was a real
                // regression: this app reconnects to the *same* peer several times within one
                // MuleSyncEngine tick (a readDeviceInfo, a pullFrom, a refresh readDeviceInfo),
                // unlike the web app's human-paced single-connection use case, so a 10s-per
                // -reconnect cooldown compounded across devices until whole ticks blew past
                // OVERALL_TICK_TIMEOUT and otherwise-healthy devices started reporting
                // unreachable — reverted, along with the settle delay's own connect-timing
                // theory, once the next experiment pointed elsewhere; (3) reordering this write
                // ahead of the read instead of adding a delay — the write itself then reliably
                // *succeeded*, but the read (now second) started failing instead, with
                // GattRequestRejectedException ("device is busy — a previous request is still
                // in-progress", per Kable's own doc), not a timeout. That result is the reason
                // this delay exists at all: it confirms the real issue was never about elapsed
                // time since connect or since a prior disconnect, but about issuing a *second*
                // GATT operation on this connection too soon after the *first one's own
                // completion*, regardless of which operation goes first or which one is a read
                // vs a write. Kept at the same generous value as CONNECT_SETTLE_DELAY rather
                // than re-guessing a smaller one, on the same reasoning the web app's own
                // GATT_CONNECT_SETTLE_MS doc gives: the cost of settling longer than strictly
                // necessary is a barely-noticeable pause, versus landing back in the failure
                // this whole investigation is chasing.
                delay(INTER_OPERATION_SETTLE_DELAY)
                // Best-effort, unlike every other phase here: this ack is piggybacking an
                // already-owed sink confirmation onto a connection that's already delivered its
                // real payload (the DeviceInfo just decoded into [info]) — see this function's
                // caller-facing doc for why it's bundled in here at all. Since a device is never
                // dropped from PulledRecordDao.getUnrelayedSinkConfirmedRecordUuidsForSource's
                // own owed set until its ack genuinely lands, failing to relay one here is always
                // safely retried on a later reconnect (exactly like pull()'s own ack failure is
                // already treated) — there's no correctness reason a stuck ack write should cost
                // this call the read it already has in hand. Stops trying further batches the
                // moment one fails, rather than attempting each independently: a write hanging on
                // this connection is why the *first* one failed, so there's no reason to expect a
                // second write on the same connection to fare any better. [onAckFailure] tells
                // the caller what happened (not just Log.w) — swallowing a failure here must not
                // also swallow the *fact* it happened, or a still-broken confirmation relay
                // becomes invisible instead of merely non-fatal (confirmed live: exactly this gap
                // is why the confirmation stopped reaching leaf devices with no visible sign once
                // the failure here was first made best-effort).
                for (batch in ackBatches(pullerDeviceId!!, pullerDeviceName, emptyList(), sinkConfirmedRecordUuids) { json.encodeToString(it) }) {
                    try {
                        withTimeout(ACK_WRITE_TIMEOUT) {
                            peripheral.write(ackCharacteristic, json.encodeToString(batch).toByteArray(Charsets.UTF_8), WriteType.WithResponse)
                        }
                    } catch (e: CancellationException) {
                        // A genuine outer cancellation (this coroutineScope itself torn down,
                        // e.g. the whole app shutting down) must still propagate — only our own
                        // ACK_WRITE_TIMEOUT firing is treated as best-effort.
                        if (e !is TimeoutCancellationException) throw e
                        val reason = describeConnectFailure(MulePhaseTimeoutException("acking", e))
                        Log.w(TAG, "ack write timed out for address=${advertisement.identifier} — confirmation stays owed, DeviceInfo read above still counts", e)
                        onAckFailure(reason)
                        break
                    } catch (e: Throwable) {
                        val reason = describeConnectFailure(e)
                        Log.w(TAG, "ack write failed for address=${advertisement.identifier} — confirmation stays owed, DeviceInfo read above still counts", e)
                        onAckFailure(reason)
                        break
                    }
                    if (batch.sinkConfirmedRecordUuids.isNotEmpty()) {
                        onConfirmationsRelayed(batch.sinkConfirmedRecordUuids)
                    }
                }
            }
            succeeded = true
            info
        } finally {
            endConnection(advertisement, peripheral, succeeded)
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
            try {
                connectOrEvict(advertisement, peripheral)
            } catch (e: TimeoutCancellationException) {
                throw MulePhaseTimeoutException("connecting", e)
            }
            // See endConnection's own doc (and readDeviceInfoOnce's matching use) for why this
            // is tracked rather than a bare finally { peripheral.disconnect() }.
            var succeeded = false
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
                // race's entire backlog at once (see AckPayload's own doc). Unlike
                // readDeviceInfo's own ack write, this one is deliberately NOT best-effort/
                // swallowed on failure — this function's own doc already explains why a thrown
                // failure here is safe (the peripheral just re-offers these records next pull),
                // so there's no correctness reason to hide it from the caller the way
                // readDeviceInfo's ack (which was masking an already-successful read) needed to
                // be. Still phase-tagged for the same diagnostic value as everything else here.
                val ackBatchesToSend = ackBatches(pullerDeviceId, pullerDeviceName, records.map { it.recordUuid }, sinkConfirmedRecordUuids) { json.encodeToString(it) }
                // Same settle gap as readDeviceInfoOnce's own INTER_OPERATION_SETTLE_DELAY, and
                // for the same reason — confirmed live (TODO.md's Sony-Mule investigation) as the
                // same underlying issue, not something specific to a read preceding a write: this
                // ack write follows collectChunkedResponse's own control write + notification
                // wait with no gap at all, and it started timing out ("Auto-pull failed: timed
                // out acking") the same way readDeviceInfoOnce's ack once did, once that one was
                // fixed and traffic moved on to exercising this one. Guarded on there actually
                // being a batch to send — ackBatches returns empty when both recordUuids and
                // sinkConfirmedRecordUuids are empty, in which case this loop does nothing and
                // the delay would be pure waste.
                if (ackBatchesToSend.isNotEmpty()) delay(INTER_OPERATION_SETTLE_DELAY)
                for (batch in ackBatchesToSend) {
                    // WithResponse means this suspends until the peripheral's GATT response
                    // arrives — PeripheralSyncService now always sends one (see its own doc), but
                    // this timeout is defense-in-depth against any peer (a different app version,
                    // or anything else speaking this protocol) that doesn't, so a stuck peer can't
                    // hang this whole pull forever.
                    try {
                        withTimeout(ACK_WRITE_TIMEOUT) {
                            peripheral.write(ackCharacteristic, json.encodeToString(batch).toByteArray(Charsets.UTF_8), WriteType.WithResponse)
                        }
                    } catch (e: TimeoutCancellationException) {
                        throw MulePhaseTimeoutException("acking", e)
                    }
                    if (batch.sinkConfirmedRecordUuids.isNotEmpty()) {
                        onConfirmationsRelayed(batch.sinkConfirmedRecordUuids)
                    }
                }
                succeeded = true
            } finally {
                endConnection(advertisement, peripheral, succeeded)
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
            try {
                connectOrEvict(advertisement, peripheral)
            } catch (e: TimeoutCancellationException) {
                throw MulePhaseTimeoutException("connecting", e)
            }
            // See endConnection's own doc (and readDeviceInfoOnce's matching use) for why this
            // is tracked rather than a bare finally { peripheral.disconnect() }.
            var succeeded = false
            try {
                val pullRequest = json.encodeToString(PullRequest(sinceLineNumber = 0, requestRelayManifest = true))
                val payload = collectChunkedResponse(peripheral, pullRequest)
                val result: List<RelayManifestEntry> = if (payload.isBlank()) emptyList() else json.decodeFromString(payload)
                succeeded = true
                result
            } finally {
                endConnection(advertisement, peripheral, succeeded)
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
        // *data collection* phase that follows, not this write itself. Phase-tagged (see
        // MulePhaseTimeoutException's own doc) — this is the same WriteType.WithResponse shape
        // as readDeviceInfo's own ack write, which TODO.md's Sony-Mule investigation found
        // hangs reliably on at least one real device; tagging this one the same way is what
        // will tell us whether this *particular* write is the same failure, now that a device
        // can show "has new data" (mergeDeviceInfo already succeeded) yet never actually catch
        // up (this write, further downstream, silently failing every attempt).
        try {
            withTimeout(ACK_WRITE_TIMEOUT) {
                peripheral.write(controlCharacteristic, requestJson.toByteArray(Charsets.UTF_8), WriteType.WithResponse)
            }
        } catch (e: TimeoutCancellationException) {
            throw MulePhaseTimeoutException("requesting", e)
        }
        try {
            withTimeout(PULL_TIMEOUT) { collectJob.join() }
        } catch (e: TimeoutCancellationException) {
            throw MulePhaseTimeoutException("collecting the pulled data", e)
        }

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

        // See readDeviceInfo's own doc for why this retry exists and why both of these are kept
        // small — 3 total attempts (matching the web app's own DEVICE_INFO_ATTEMPTS) with a
        // short pause between them, not this app's much longer inter-device intervals.
        private const val READ_DEVICE_INFO_ATTEMPTS = 3
        private val READ_DEVICE_INFO_RETRY_DELAY = 1_500.milliseconds

        // Mirrors js/mule-ble.js's GATT_CONNECT_SETTLE_MS exactly (same value, same
        // field-tested reasoning) — see connectOrEvict's own doc at its call site. Kept even
        // though it didn't fix the ack-write failure on its own (see
        // INTER_OPERATION_SETTLE_DELAY's own doc for what did) — nothing suggests it's harmful,
        // and its own independent theory (a fresh link needing real time to settle before *any*
        // GATT traffic) stands on its own regardless of the separate issue that turned out to
        // need INTER_OPERATION_SETTLE_DELAY.
        private val CONNECT_SETTLE_DELAY = 2_000.milliseconds

        // See readDeviceInfoOnce's own doc at its call site — the fix that actually worked, after
        // a per-address reconnect cooldown mirroring the web app's own RECONNECT_COOLDOWN_MS was
        // tried and reverted here (see the same doc) as a real regression: this app's own
        // multiple-reconnects-to-the-same-peer-per-tick pattern doesn't tolerate a human-paced
        // web-app cooldown value. Also used by pull()'s own ack write, right after
        // collectChunkedResponse's own control write + notification wait — confirmed live as the
        // exact same underlying issue (a write immediately following prior GATT activity on the
        // connection, not something specific to a read preceding a write), once readDeviceInfo's
        // own case was fixed and real traffic exercised this second, previously-unprotected spot.
        private val INTER_OPERATION_SETTLE_DELAY = 2_000.milliseconds
    }
}

/**
 * Wraps a [TimeoutCancellationException] caught immediately at its own call site inside
 * [MulePullClient]'s connect/pull functions, tagged with which [phase] was still pending when
 * the timeout fired — see each call site's own doc for its exact phase names ("connecting",
 * "negotiating MTU", "reading", "requesting", "collecting the pulled data", "acking", ...).
 * Shared across [MulePullClient.readDeviceInfoOnce], [MulePullClient.pull], and
 * [MulePullClient.pullRelayManifest]/[MulePullClient.collectChunkedResponse] rather than each
 * inventing its own tagging scheme, since they're all diagnosing the exact same underlying
 * question — which specific GATT operation against a real device isn't completing. Deliberately
 * never thrown for [MulePullClient.readDeviceInfoOnce]'s own trailing ack-write phase — see that
 * phase's own doc for why a stuck ack is caught and swallowed there instead of failing the whole
 * call the way every other phase does. `internal`, not `private`, so [describeConnectFailure] in
 * MuleSyncEngine.kt (same package) can pattern-match on it to give a more specific label than
 * the bare "timeout" it falls back to for anything else that throws a plain, untagged
 * [TimeoutCancellationException].
 */
internal class MulePhaseTimeoutException(val phase: String, cause: TimeoutCancellationException) : Exception("timed out $phase", cause)

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
 * needed to keep every one of them under [maxEncodedBytes] once JSON-encoded. An
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
 *
 * [maxEncodedBytes] defaults to [MuleGattProfile.MAX_SAFE_CHUNK_SIZE_BYTES] (509) — Android
 * hard-caps a single GATT characteristic write at 512 bytes (`GATT_MAX_ATTR_LEN`; confirmed in
 * the field: a write past that throws `IllegalArgumentException`), so this is the one true
 * correctness ceiling for how big a single [AckPayload] batch is *allowed* to be. It says
 * nothing about how many over-the-air ATT packets a write of that size costs, though — that's
 * governed by whatever MTU the connection actually negotiated (REQUESTED_MTU=247 is a request,
 * "Android doesn't guarantee it matches"), and a write whose encoded size exceeds (negotiated MTU
 * − 3) doesn't fail — it silently becomes a multi-PDU "prepared write" transaction instead
 * (`BluetoothGattServerCallback.onExecuteWrite`). TODO.md's Sony-Mule investigation traced a
 * real, 100%-reproducible "timed out acking" failure to exactly that path: an oversized ack (242
 * bytes — comfortably under this 509-byte ceiling, but over what a 247-MTU connection's single
 * PDU can carry) arrived at `PeripheralSyncService.onCharacteristicWriteRequest` as a partial
 * chunk it had no reassembly logic for, decoded as garbage, and the write's own GATT response
 * then simply never came — the central's `write()` call sat until its own ACK_WRITE_TIMEOUT
 * (10s). The real fix was implementing proper prepared-write/onExecuteWrite reassembly on the
 * peripheral side (see that class's own `PendingWrite`/`onExecuteWrite` doc), not shrinking this
 * ceiling — a batch below the fixed per-payload JSON overhead (deviceId, deviceName, field names)
 * isn't achievable anyway, so artificially lowering this just fragments a large confirmation
 * backlog into needlessly many separate writes without preventing the multi-PDU path at all.
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
