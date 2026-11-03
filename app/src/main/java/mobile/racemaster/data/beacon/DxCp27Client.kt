package mobile.racemaster.data.beacon

import android.bluetooth.le.ScanSettings
import com.juul.kable.Advertisement
import com.juul.kable.AndroidPeripheral
import com.juul.kable.ObsoleteKableApi
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toKotlinUuid

/** BLE central for one interactive beacon-programming session — a single cached [Peripheral]
 *  rather than [mobile.racemaster.data.mule.MulePullClient]'s per-address map, since this screen
 *  only ever talks to one beacon at a time. Reuses that file's own field-tested connection idioms
 *  (connect-timeout + evict-on-failure, a post-connect settle delay, best-effort MTU, a tracked
 *  [NonCancellable] disconnect) directly rather than rediscovering them against different
 *  hardware. Everything protocol-specific (framing, field layouts) lives in [DxCp27Protocol] and
 *  is ported call-for-call from the validated `~/dx-cp27-probing/dxbeacon.py` reference. */
@OptIn(ExperimentalUuidApi::class)
class DxCp27Client {
    // Unfiltered at the native ScanFilter level, filtered manually below. The 0xffe0 config
    // service never appears in the advertisement itself (only via GATT discovery once
    // connected), so a plain service-UUID filter (as MulePullClient.scanForDevices uses, for a
    // device that *does* advertise its own service) can't be used here.
    //
    // No single shape-based heuristic (name, a specific manufacturer company ID, a specific
    // service-data UUID) is safe to require on its own: this beacon rotates through advertising
    // each of its configured slots as its own separate packet — confirmed on real hardware to
    // include a device-name frame, an iBeacon frame (itself inconsistently tagged with Apple's
    // real company ID or this vendor's own `0x4458`, see DxCp27Protocol.VENDOR_COMPANY_ID's own
    // doc), and up to three different Eddystone sub-frames (UID/URL/TLM, each its own AD shape) —
    // and every one of those has, in turn, turned out to be missing from an earlier version of
    // this filter. Rather than keep extending an allowlist of shapes one bug report at a time,
    // this recognizes a device once (by name, or any of the known manufacturer/service-data
    // markers) and then accepts *every* advertisement from that same MAC address from then on,
    // whatever shape it takes — so a frame type this app doesn't explicitly know about yet still
    // reaches the UI (see ObservedAdvertFrame.Unrecognized) instead of being silently dropped at
    // the source. Persists for this client's lifetime, not just one scan — once a beacon's MAC is
    // known, there's no reason to make it clear its "name" hurdle again on a later scan.
    //
    // SCAN_MODE_LOW_LATENCY, unlike MulePullClient's own BALANCED choice — that file steps down
    // from LOW_LATENCY specifically because a Mule phone is simultaneously scanning *and*
    // advertising/hosting a GATT server on the same radio, and budget chipsets time-share those
    // two roles poorly at a high scan duty cycle. This screen never advertises anything of its
    // own; it's a plain foreground, user-initiated, one-shot scan, so there's no airtime
    // contention to trade off against — just use the fastest duty cycle to minimize how long an
    // operator stares at an empty list waiting for one beacon's advertising interval to tick over.
    @OptIn(ObsoleteKableApi::class)
    private val scanner = Scanner {
        scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    }

    private val recognizedAddresses = mutableSetOf<String>()

    fun scanForDevices(): Flow<Advertisement> = scanner.advertisements
        .filter { advertisement ->
            val name = advertisement.name.orEmpty().uppercase()
            val looksLikeOurs = "CP27" in name || "DX" in name ||
                advertisement.serviceData(DxCp27Protocol.VENDOR_IDENTITY_SERVICE_UUID.toKotlinUuid()) != null ||
                advertisement.serviceData(DxCp27Protocol.EDDYSTONE_SERVICE_UUID.toKotlinUuid()) != null ||
                advertisement.manufacturerData(DxCp27Protocol.APPLE_COMPANY_ID) != null ||
                advertisement.manufacturerData(DxCp27Protocol.VENDOR_COMPANY_ID) != null
            if (looksLikeOurs) recognizedAddresses += advertisement.identifier
            looksLikeOurs || advertisement.identifier in recognizedAddresses
        }

    private val commandCharacteristic = characteristicOf(
        service = DxCp27Protocol.SERVICE_UUID.toKotlinUuid(),
        characteristic = DxCp27Protocol.COMMAND_CHARACTERISTIC_UUID.toKotlinUuid(),
    )
    private val passwordCharacteristic = characteristicOf(
        service = DxCp27Protocol.SERVICE_UUID.toKotlinUuid(),
        characteristic = DxCp27Protocol.PASSWORD_CHARACTERISTIC_UUID.toKotlinUuid(),
    )
    private val notifyCharacteristic = characteristicOf(
        service = DxCp27Protocol.SERVICE_UUID.toKotlinUuid(),
        characteristic = DxCp27Protocol.NOTIFY_CHARACTERISTIC_UUID.toKotlinUuid(),
    )

    private val sessionMutex = Mutex()
    private var peripheral: Peripheral? = null

    // Owns the long-lived notify subscription for the current connection — cancelled (not just
    // disconnected-from) on every disconnect/evict path, so a stale collector never outlives its
    // connection. Mirrors dxbeacon.py's single asyncio.Queue: one outstanding reply at a time,
    // fed here instead by whatever the beacon's notify characteristic delivers.
    private var connectionScope: CoroutineScope? = null
    private var replyChannel: Channel<ByteArray>? = null

    // See MulePullClient.evictAfterFailedConnect's own doc for why a Peripheral must be
    // discarded (not just disconnected) after any failed/cancelled connect — Kable permanently
    // poisons the instance otherwise.
    private suspend fun teardownSession(peripheralToDisconnect: Peripheral?) {
        peripheral = null
        connectionScope?.cancel()
        connectionScope = null
        replyChannel = null
        if (peripheralToDisconnect != null) {
            withContext(NonCancellable) { runCatching { peripheralToDisconnect.disconnect() } }
        }
    }

    suspend fun connect(advertisement: Advertisement): Result<Unit> = sessionMutex.withLock {
        // Confirmed in the field: reconnecting to the *same* remote device right after closing
        // the previous BluetoothGatt (as every multi-candidate retry in this screen does —
        // [mobile.racemaster.data.beacon.BeaconRepository.connectAndAuthenticate] reconnects
        // fresh between password candidates) can make the very next characteristic write fail
        // with status 133 (GATT_ERROR) — a well-known Android BLE race between a GATT client
        // actually being released natively and the next connectGatt() for the same address,
        // not anything our own code is holding onto (it survives a Bluetooth toggle, which rules
        // out stuck app-side state — see this method's git history for the ruled-out theories).
        // A short settle delay between the two is the standard workaround.
        val hadPreviousSession = peripheral != null
        teardownSession(peripheral)
        if (hadPreviousSession) delay(RECONNECT_SETTLE_DELAY)
        val fresh = Peripheral(advertisement)
        peripheral = fresh
        try {
            withTimeout(CONNECT_TIMEOUT) { fresh.connect() }
        } catch (e: Throwable) {
            teardownSession(fresh)
            return@withLock Result.failure(e)
        }
        delay(CONNECT_SETTLE_DELAY)
        // Every other suspending BLE call in this class is bounded by OPERATION_TIMEOUT — this
        // one wasn't, and it showed: after a factory reset (plausibly a full DA14531 chip
        // reboot, a much bigger disruption than a plain settings apply), the MTU callback could
        // apparently never arrive, hanging this call forever. Since it runs under sessionMutex
        // (this whole function is sessionMutex.withLock'd), an unbounded hang here wedges that
        // lock permanently — every future connect()/disconnect() call, for *any* beacon, then
        // blocks forever just trying to acquire it, which matches exactly what was seen in the
        // field (every connect broken, including to a beacon never seen before, surviving a
        // Bluetooth toggle since it's a stuck coroutine/lock in this process, not OS BLE state).
        runCatching { withTimeout(OPERATION_TIMEOUT) { (fresh as? AndroidPeripheral)?.requestMtu(REQUESTED_MTU) } }

        val channel = Channel<ByteArray>(capacity = 8)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        connectionScope = scope
        replyChannel = channel
        scope.launch { runCatching { fresh.observe(notifyCharacteristic).collect { bytes -> channel.trySend(bytes) } } }
        // The actual bug, per the field evidence (the *first* authenticate attempt on a fresh
        // connection reliably timing out, as if the beacon's reply never arrived at all): nothing
        // here waited for that collect() above to actually land its CCCD subscription write
        // before returning, so the very first command below could be sent — and the beacon could
        // reply — before we were actually subscribed to hear it, silently dropping that first
        // reply. This exact race, and this exact fix, already has precedent in this codebase —
        // see MulePullClient.collectChunkedResponse's own identical delay before its first
        // request, with the identical reasoning ("gives the notification subscription (CCCD
        // write) time to land... otherwise the first chunks could be sent before we're
        // subscribed and silently dropped"). None of the other theories tried (MTU timeout,
        // close/reopen settle delay, Bluetooth toggle, phone reboot) touch this at all, since
        // it's a race purely between two things this process itself does right after connecting
        // — which is also exactly why nothing external to this process ever cleared it.
        delay(OBSERVE_SETTLE_DELAY)
        Result.success(Unit)
    }

    suspend fun disconnect() = sessionMutex.withLock { teardownSession(peripheral) }

    /** Sends [DxCp27Protocol.CMD_PRE_AUTH_CHECK] (0x25), then authenticates by writing [password]
     *  directly to [passwordCharacteristic] as plain ASCII — deliberately unframed (see that
     *  characteristic's own doc). The 0x25 isn't optional preamble: every single authentication
     *  in the real capture (`~/dx-cp27-probing/snooped_data.txt`) sends it immediately before the
     *  password write, and `dxbeacon.py`'s own `authenticate()` does too — confirmed on real
     *  hardware that skipping it makes every password attempt fail, including ones later
     *  confirmed correct, once the connection has already been through one auth attempt (e.g.
     *  retrying a second candidate password on the same connection, as [BeaconRepository] does).
     *  Doesn't by itself confirm the password was accepted; [BeaconRepository] confirms by
     *  following up with [readSlots]. */
    suspend fun authenticate(password: String): Result<Unit> = withSession { current, channel ->
        sendCommandLocked(current, channel, DxCp27Protocol.CMD_PRE_AUTH_CHECK)
        withTimeout(OPERATION_TIMEOUT) {
            current.write(passwordCharacteristic, password.toByteArray(Charsets.US_ASCII), WriteType.WithResponse)
        }
    }

    suspend fun readSlots(): Result<List<Int>> = withSession { current, channel ->
        val reply = sendReadLocked(current, channel, DxCp27Protocol.CMD_READ_SLOTS)
        DxCp27Protocol.parseSlotTable(reply)
    }

    suspend fun readFrame(frameType: Int): Result<ByteArray> = withSession { current, channel ->
        sendReadLocked(current, channel, frameType)
    }

    suspend fun readDeviceInfo(): Result<DxCp27Protocol.DeviceInfo> = withSession { current, channel ->
        val reply = sendReadLocked(current, channel, DxCp27Protocol.CMD_DEVICE_INFO)
        DxCp27Protocol.parseDeviceInfo(reply)
    }

    /** Ports `dxbeacon.py`'s `set_ibeacon` call-for-call: select the slot, set its frame type
     *  (no ack expected), write every field (each ack'd), then save and apply. Runs under one
     *  held lock for its whole sequence (see [withSession]) so nothing else can interleave a
     *  command into the middle of it. The [APPLY_SETTLE_DELAY] after apply isn't optional —
     *  confirmed on real hardware that the very next command issued right after a successful
     *  apply ack can still time out waiting for a reply, because the beacon is momentarily busy
     *  reconfiguring its advertising to match (the apply ack itself just means "accepted", not
     *  "finished settling"). */
    suspend fun setIBeacon(
        slot: Int,
        uuid: ByteArray,
        major: Int,
        minor: Int,
        measuredPowerDbm: Int,
        intervalRaw: Int,
        txPowerRaw: Int,
    ): Result<Unit> = withSession { current, channel ->
        sendAckLocked(current, channel, DxCp27Protocol.selectSlotCommand(slot))
        sendCommandLocked(current, channel, DxCp27Protocol.FRAME_IBEACON, expectReply = false)
        sendAckLocked(current, channel, DxCp27Protocol.CMD_SET_UUID, uuid)
        sendAckLocked(current, channel, DxCp27Protocol.CMD_SET_MAJOR, be16(major))
        sendAckLocked(current, channel, DxCp27Protocol.CMD_SET_MINOR, be16(minor))
        sendAckLocked(current, channel, DxCp27Protocol.CMD_SET_MEASURED_POWER, byteArrayOf(measuredPowerDbm.toByte()))
        sendAckLocked(current, channel, DxCp27Protocol.CMD_SET_ADV_INTERVAL, byteArrayOf(intervalRaw.toByte()))
        sendAckLocked(current, channel, DxCp27Protocol.CMD_SET_TX_POWER, byteArrayOf(txPowerRaw.toByte()))
        sendAckLocked(current, channel, DxCp27Protocol.CMD_SAVE)
        sendAckLocked(current, channel, DxCp27Protocol.CMD_APPLY)
        delay(APPLY_SETTLE_DELAY)
    }

    /** Ports `dxbeacon.py`'s `delete_slot`: a pre-edit query, select the slot, set its frame type
     *  to empty (no ack expected), then apply. */
    suspend fun deleteSlot(slot: Int): Result<Unit> = withSession { current, channel ->
        sendCommandLocked(current, channel, DxCp27Protocol.CMD_SET_EMPTY)
        sendAckLocked(current, channel, DxCp27Protocol.selectSlotCommand(slot))
        sendCommandLocked(current, channel, DxCp27Protocol.CMD_SET_EMPTY, expectReply = false)
        sendAckLocked(current, channel, DxCp27Protocol.CMD_APPLY)
        delay(APPLY_SETTLE_DELAY)
    }

    /** Ports `dxbeacon.py`'s `set_password`: the framed change-password command (distinct from
     *  [authenticate]'s unframed write — see [DxCp27Protocol.PASSWORD_CHARACTERISTIC_UUID]'s own
     *  doc), then apply. */
    suspend fun setPassword(newPassword: String): Result<Unit> = withSession { current, channel ->
        sendAckLocked(current, channel, DxCp27Protocol.CMD_CHANGE_PASSWORD, newPassword.toByteArray(Charsets.US_ASCII))
        sendAckLocked(current, channel, DxCp27Protocol.CMD_APPLY)
    }

    suspend fun factoryReset(): Result<Unit> = withSession { current, channel ->
        sendAckLocked(current, channel, DxCp27Protocol.CMD_FACTORY_RESET)
    }

    suspend fun powerOff(): Result<Unit> = withSession { current, channel ->
        // The link may drop before/after this ack (the beacon is powering off) — matches
        // dxbeacon.py's own power-off, which swallows exactly this.
        runCatching { sendAckLocked(current, channel, DxCp27Protocol.CMD_POWER_OFF) }
    }

    private suspend fun <T> withSession(block: suspend (Peripheral, Channel<ByteArray>) -> T): Result<T> = sessionMutex.withLock {
        val current = peripheral ?: return@withLock Result.failure(IllegalStateException("not connected"))
        val channel = replyChannel ?: return@withLock Result.failure(IllegalStateException("not connected"))
        runCatching { block(current, channel) }
    }

    /** Writes a framed command and, unless [expectReply] is false, awaits the next item off
     *  [channel] — ports `dxbeacon.py`'s `send()`, including its settle delays: 150ms after an
     *  unacked write (used for a slot's frame-type-switch write, which the beacon needs time to
     *  process before the next command — confirmed on real hardware: omitting this let the
     *  following field writes land before the frame-type switch took effect, so the save/apply
     *  silently persisted the slot's *previous* frame type instead) and 50ms after every acked
     *  reply. Drains any stale queued reply first, exactly like the Python reference's own
     *  `while not queue.empty(): queue.get_nowait()`. Must only ever be called from inside
     *  [withSession]'s lock. */
    private suspend fun sendCommandLocked(
        current: Peripheral,
        channel: Channel<ByteArray>,
        cmd: Int,
        data: ByteArray = ByteArray(0),
        expectReply: Boolean = true,
    ): ByteArray? {
        while (channel.tryReceive().isSuccess) { /* drain */ }
        val frame = DxCp27Protocol.buildFrame(cmd, data)
        return withTimeout(OPERATION_TIMEOUT) {
            current.write(commandCharacteristic, frame, WriteType.WithResponse)
            if (!expectReply) {
                delay(UNACKED_SETTLE_DELAY)
                null
            } else {
                val reply = channel.receive()
                delay(ACKED_SETTLE_DELAY)
                reply
            }
        }
    }

    /** Ports `dxbeacon.py`'s `send_ack()`: send, then require the reply to be exactly the
     *  success-ack shape for [cmd]. If a reply arrives that's shaped like a valid ack frame
     *  (`4e4f` + cmd + len 1 + data 1 + checksum) but for some *other* command — confirmed on
     *  real hardware (e.g. an ack-shaped `4e4ff20100f3` arriving in place of 0x60's own reply,
     *  for a command byte never sent anywhere in this sequence) — it's an autonomous notification
     *  the beacon pushed on its own initiative, sharing this one reply channel with real command
     *  acks. Discard it and keep waiting rather than failing on a stray. Must only ever be called
     *  from inside [withSession]'s lock. */
    private suspend fun sendAckLocked(current: Peripheral, channel: Channel<ByteArray>, cmd: Int, data: ByteArray = ByteArray(0)) {
        var reply = sendCommandLocked(current, channel, cmd, data)
        var staleReplies = 0
        while (reply != null && !DxCp27Protocol.isSuccessAck(cmd, reply) && isAckShaped(reply) && staleReplies < MAX_STALE_ACK_RETRIES) {
            reply = withTimeout(OPERATION_TIMEOUT) { channel.receive() }
            staleReplies++
        }
        check(reply != null && DxCp27Protocol.isSuccessAck(cmd, reply)) {
            "unexpected reply to 0x${cmd.toString(16)}: ${reply?.joinToString("") { "%02x".format(it) } ?: "none"}"
        }
    }

    /** A real read reply (slot table, a frame, device info) is never 6 bytes starting with
     *  `"NO"` — only an ack/notification frame is shaped that way (see [isAckShaped]). Discards
     *  any such stray reply before accepting one as the real answer — ports the same protection
     *  [sendAckLocked] already has against an autonomous notification landing in the single reply
     *  channel ahead of the real reply, which the plain read path lacked entirely: confirmed on
     *  real hardware that this let a stray ack-shaped notification get misread as e.g. a slot
     *  table, with no validation catching it ([DxCp27Protocol.parseSlotTable] just maps bytes),
     *  reporting a wrong password as authenticated because *some* reply arrived and parsed
     *  without throwing — not because reads aren't actually gated by the password; they are. */
    private suspend fun sendReadLocked(current: Peripheral, channel: Channel<ByteArray>, cmd: Int): ByteArray {
        var reply = sendCommandLocked(current, channel, cmd)
        var staleReplies = 0
        while (reply != null && isAckShaped(reply) && staleReplies < MAX_STALE_ACK_RETRIES) {
            reply = withTimeout(OPERATION_TIMEOUT) { channel.receive() }
            staleReplies++
        }
        return checkNotNull(reply) { "no reply to 0x${cmd.toString(16)}" }
    }

    private fun isAckShaped(reply: ByteArray): Boolean =
        reply.size == 6 && reply[0] == 0x4E.toByte() && reply[1] == 0x4F.toByte()

    private fun be16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

    companion object {
        private val CONNECT_TIMEOUT = 10_000.milliseconds
        private val OPERATION_TIMEOUT = 5_000.milliseconds

        // Ports dxbeacon.py's send()'s own post-write delays exactly (same values) — see
        // sendCommandLocked's doc for why the unacked one in particular is load-bearing.
        private val UNACKED_SETTLE_DELAY = 150.milliseconds
        private val ACKED_SETTLE_DELAY = 50.milliseconds

        // Bounded: a real protocol mismatch (not a stray notification) must still fail promptly
        // rather than loop until the overall operation timeout.
        private const val MAX_STALE_ACK_RETRIES = 2

        private val APPLY_SETTLE_DELAY = 2_000.milliseconds

        // Same value as MulePullClient.CONNECT_SETTLE_DELAY, reused as a starting point for this
        // different chipset (DA14531) — not yet independently field-tuned for it.
        private val CONNECT_SETTLE_DELAY = 2_000.milliseconds

        // See connect()'s own doc — the delay between closing one BluetoothGatt and opening the
        // next for the same remote device, to dodge a field-confirmed GATT_ERROR(133) on the
        // very next characteristic write otherwise. Not yet independently field-tuned; a round
        // number in the range commonly cited for this specific Android race.
        private val RECONNECT_SETTLE_DELAY = 500.milliseconds

        // Same value and reasoning as MulePullClient's own identical delay before its first
        // post-subscribe request — see connect()'s own doc for why this one in particular is the
        // real fix for the field-confirmed "first authenticate attempt always times out" bug.
        private val OBSERVE_SETTLE_DELAY = 300.milliseconds

        // See MuleGattProfile.REQUESTED_MTU's own doc — same reasoning (request the BLE max, let
        // the stack negotiate down), copied rather than shared since this talks to unrelated
        // hardware over an unrelated profile. The real capture showed this beacon itself asking
        // for 256 and settling at a 256-byte negotiated MTU regardless of what's requested here.
        private const val REQUESTED_MTU = 517
    }
}
