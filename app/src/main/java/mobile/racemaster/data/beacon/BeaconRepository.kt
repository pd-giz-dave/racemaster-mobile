package mobile.racemaster.data.beacon

import com.juul.kable.Advertisement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

enum class BeaconConnectionPhase { IDLE, CONNECTING, CONNECTED, ERROR }

enum class BeaconLogDirection { TX, RX, INFO, ERROR }

data class BeaconLogEntry(
    val id: Long,
    val atMillis: Long,
    val direction: BeaconLogDirection,
    val note: String,
)

data class SlotSummary(val slot: Int, val frameType: Int, val label: String = DxCp27Protocol.frameTypeLabel(frameType))

data class BeaconConfig(
    val slots: List<SlotSummary>,
    val iBeaconFrame: DxCp27Protocol.IBeaconFrame?,
    val deviceInfo: DxCp27Protocol.DeviceInfo,
)

/** Owns the one unified session log and serializes every operation through [opMutex] — see
 *  [DxCp27Client]'s own doc for the BLE-central machinery this sits on top of. This is the
 *  domain layer: [connectAndAuthenticate]/[loadCurrentConfig]/[calibrateMeasuredPower]/
 *  [programForRace] are the four operations [mobile.racemaster.ui.beacon.BeaconProgrammerScreen]
 *  actually drives, each narrated into [log] as it runs — the log is the only place an operator
 *  can tell whether a step genuinely took effect on the real beacon. */
class BeaconRepository(private val client: DxCp27Client) {
    private val opMutex = Mutex()

    private val logFlow = MutableStateFlow<List<BeaconLogEntry>>(emptyList())
    val log: StateFlow<List<BeaconLogEntry>> = logFlow.asStateFlow()

    private val isBusyFlow = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = isBusyFlow.asStateFlow()

    private val connectionPhaseFlow = MutableStateFlow(BeaconConnectionPhase.IDLE)
    val connectionPhase: StateFlow<BeaconConnectionPhase> = connectionPhaseFlow.asStateFlow()

    private val configFlow = MutableStateFlow<BeaconConfig?>(null)
    val config: StateFlow<BeaconConfig?> = configFlow.asStateFlow()

    private var nextLogId = 0L
    private var currentPassword: String? = null
    private var lastAdvertisement: Advertisement? = null

    fun scanForDevices() = client.scanForDevices()

    fun logScanStarted() {
        log(BeaconLogDirection.INFO, "Scanning for beacons…")
    }

    fun logBeaconFound(name: String?, address: String, rssi: Int) {
        log(BeaconLogDirection.RX, "Found ${name ?: "(unnamed)"} — $address — $rssi dBm")
    }

    /** Called from the scan coroutine's own `finally`, so this fires no matter why scanning
     *  stopped — the operator tapping Stop, tapping a beacon to connect (which stops scanning
     *  first), or the scan Flow itself ending/erroring. */
    fun logScanStopped() {
        log(BeaconLogDirection.INFO, "Scan stopped.")
    }

    fun clearLog() {
        logFlow.value = emptyList()
    }

    private fun log(direction: BeaconLogDirection, note: String) {
        logFlow.value = (logFlow.value + BeaconLogEntry(nextLogId++, System.currentTimeMillis(), direction, note))
            .takeLast(MAX_LOG_ENTRIES)
    }

    private suspend fun <T> withBusyLock(block: suspend () -> T): T = opMutex.withLock {
        isBusyFlow.value = true
        try {
            block()
        } finally {
            isBusyFlow.value = false
        }
    }

    /** Tries [DxCp27Protocol.DEFAULT_PASSWORD] then [DxCp27Protocol.RACEMASTER_PASSWORD], in that
     *  fixed order — no candidate guessing based on session memory (an earlier version tried a
     *  previous session's password first, which went wrong once that in-memory state legitimately
     *  outlived what the operator believed was an app restart — and masked the real bug, a stray
     *  notification confusing the read path; see [DxCp27Client.sendReadLocked]'s own doc). This
     *  facility normally programs a beacon exactly once from purchase (see [programForRace]'s own
     *  doc on skipping a no-op rewrite), so a fresh connect is almost always the factory default —
     *  but a beacon already programmed once can legitimately come back for re-programming (e.g.
     *  field experience calling for a different interval/TX power), hence the second, fixed
     *  fallback rather than dropping it entirely.
     *
     *  Reconnects fresh between the two attempts — confirmed on real hardware that trying a second
     *  password on an already-authenticated-once connection fails even for the genuinely correct
     *  password, since `dxbeacon.py` (the validated reference) only ever authenticates once per
     *  connection.
     *
     *  If both fail — e.g. a beacon some other tool changed the password on — [authenticateWithPassword]
     *  is the manual fallback. */
    suspend fun connectAndAuthenticate(advertisement: Advertisement): Result<Unit> = withBusyLock {
        configFlow.value = null
        lastAdvertisement = advertisement
        for (candidate in listOf(DxCp27Protocol.DEFAULT_PASSWORD, DxCp27Protocol.RACEMASTER_PASSWORD)) {
            connectLocked(advertisement).onFailure { return@withBusyLock Result.failure(it) }
            if (tryPasswordLocked(candidate)) {
                connectionPhaseFlow.value = BeaconConnectionPhase.CONNECTED
                return@withBusyLock Result.success(Unit)
            }
        }
        connectionPhaseFlow.value = BeaconConnectionPhase.ERROR
        log(BeaconLogDirection.ERROR, "Authentication failed with known passwords — enter the current password manually.")
        Result.failure(IllegalStateException("authentication failed"))
    }

    /** Manual fallback for when every candidate in [connectAndAuthenticate] failed (e.g. a
     *  beacon someone else already changed the password on) — reconnects fresh first, for the
     *  same reason [connectAndAuthenticate] does between its own candidates. */
    suspend fun authenticateWithPassword(password: String): Result<Unit> = withBusyLock {
        val advertisement = checkNotNull(lastAdvertisement) { "no advertisement on record to reconnect with" }
        connectLocked(advertisement).onFailure { return@withBusyLock Result.failure(it) }
        if (tryPasswordLocked(password)) {
            connectionPhaseFlow.value = BeaconConnectionPhase.CONNECTED
            Result.success(Unit)
        } else {
            log(BeaconLogDirection.ERROR, "Authentication failed with the entered password.")
            Result.failure(IllegalStateException("authentication failed"))
        }
    }

    private suspend fun connectLocked(advertisement: Advertisement): Result<Unit> {
        connectionPhaseFlow.value = BeaconConnectionPhase.CONNECTING
        log(BeaconLogDirection.INFO, "Connecting to ${advertisement.name ?: advertisement.identifier}…")
        val connectResult = client.connect(advertisement)
        if (connectResult.isFailure) {
            connectionPhaseFlow.value = BeaconConnectionPhase.ERROR
            log(BeaconLogDirection.ERROR, "Connect failed: ${connectResult.exceptionOrNull()?.message ?: "unknown error"}")
            return Result.failure(connectResult.exceptionOrNull()!!)
        }
        log(BeaconLogDirection.INFO, "Connected — authenticating…")
        return Result.success(Unit)
    }

    private suspend fun tryPasswordLocked(password: String): Boolean {
        log(BeaconLogDirection.TX, "Trying password '$password'…")
        val authResult = client.authenticate(password)
        if (authResult.isFailure) {
            log(BeaconLogDirection.RX, "Password write for '$password' failed: ${authResult.exceptionOrNull()?.message}")
            return false
        }
        val slotsResult = client.readSlots()
        val confirmed = slotsResult.isSuccess
        if (confirmed) {
            currentPassword = password
            log(BeaconLogDirection.RX, "Authenticated with password '$password'.")
        } else {
            log(BeaconLogDirection.RX, "Password '$password' rejected (read-slots failed: ${slotsResult.exceptionOrNull()?.message}).")
        }
        return confirmed
    }

    suspend fun disconnect() = withBusyLock {
        client.disconnect()
        configFlow.value = null
        connectionPhaseFlow.value = BeaconConnectionPhase.IDLE
        log(BeaconLogDirection.INFO, "Disconnected")
    }

    /** Both end the session on the beacon itself (power-off; factory-reset back to firmware
     *  defaults) rather than just dropping this app's own connection — there's nothing left on
     *  this beacon worth showing config for afterward, so both tear the local session down the
     *  same way [disconnect] does, for the screen to return to the scan list. */
    private fun endSessionLocked() {
        configFlow.value = null
        connectionPhaseFlow.value = BeaconConnectionPhase.IDLE
    }

    /** Matches `dxbeacon.py`'s own power-off: the beacon drops the BLE link as it shuts down,
     *  which [DxCp27Client.powerOff] already swallows rather than surfacing as a failure. */
    suspend fun powerOff(): Result<Unit> = withBusyLock {
        runCatching {
            log(BeaconLogDirection.TX, "Powering off…")
            client.powerOff().getOrThrow()
            log(BeaconLogDirection.RX, "Beacon powered off.")
        }.onFailure { e -> log(BeaconLogDirection.ERROR, "Power off failed: ${e.message}") }
            .also { client.disconnect(); endSessionLocked() }
    }

    /** Resets the beacon to firmware defaults — password and every slot revert, so there's
     *  nothing of this app's own programming left on it. Doesn't try to reconnect and confirm
     *  (an earlier version did — dropped per field feedback: the reset itself was never in
     *  doubt, confirmed independently via `dxbeacon.py`, and the real bug this screen hit
     *  afterward wasn't about this beacon's password at all — *every* subsequent connect
     *  attempt failed, including to a beacon never seen before, which points at something
     *  broken at the Android BLE stack/adapter level rather than anything this method itself
     *  could confirm or fix by retrying harder on this one connection). */
    suspend fun factoryReset(): Result<Unit> = withBusyLock {
        runCatching {
            log(BeaconLogDirection.TX, "Factory resetting…")
            client.factoryReset().getOrThrow()
            log(BeaconLogDirection.RX, "Beacon factory reset.")
        }.onFailure { e -> log(BeaconLogDirection.ERROR, "Factory reset failed: ${e.message}") }
            .also { client.disconnect(); endSessionLocked() }
    }

    private suspend fun readSlotsRaw(): List<Int> = client.readSlots().getOrThrow()

    /** Reads the slot table, every non-empty slot's own frame (just the iBeacon one is decoded
     *  for display — others show as a labelled-but-opaque row, since this screen doesn't need to
     *  edit them), and device info — everything [BeaconConfig] needs. */
    suspend fun loadCurrentConfig(): Result<BeaconConfig> = withBusyLock {
        runCatching {
            val slotTypes = readSlotsRaw()
            val slots = slotTypes.mapIndexed { index, type -> SlotSummary(index + 1, type) }
            val iBeaconFrame = slots.firstOrNull { it.frameType == DxCp27Protocol.FRAME_IBEACON }
                ?.let { DxCp27Protocol.parseIBeaconFrame(client.readFrame(DxCp27Protocol.FRAME_IBEACON).getOrThrow()) }
            val deviceInfo = client.readDeviceInfo().getOrThrow()
            BeaconConfig(slots, iBeaconFrame, deviceInfo).also {
                configFlow.value = it
                log(BeaconLogDirection.INFO, "Read current config — battery ${it.deviceInfo.batteryMillivolts} mV")
            }
        }.onFailure { e -> log(BeaconLogDirection.ERROR, "Reading current config failed: ${e.message}") }
    }

    /** Drops the GATT connection so the beacon can advertise again — confirmed on real hardware
     *  that it stops advertising entirely while connected (a single-radio chip can't do both at
     *  once), so [calibrationRssiSamples] saw nothing until this runs first. [lastAdvertisement]/
     *  [currentPassword] are untouched, for [finishCalibrating] to reconnect with
     *  afterward — [connectionPhase] is deliberately left at CONNECTED throughout, since from the
     *  operator's point of view they still are; only the underlying link is actually cycled. */
    suspend fun startCalibrating(): Result<Unit> = runCatching {
        log(BeaconLogDirection.INFO, "Disconnecting so the beacon can advertise for calibration…")
        client.disconnect()
    }

    /** The beacon's own advertisement RSSI, as a live stream the caller samples for as long as
     *  the operator wants (open-ended, not a fixed window — confirmed on real hardware that even
     *  10s wasn't reliably enough at this beacon's factory-default, battery-saving advertising
     *  interval) — not a connection-RSSI read: Kable's `Peripheral.rssi()`
     *  (`BluetoothGatt.readRemoteRssi()`) causes this beacon to drop the connection outright, a
     *  known compatibility gap on some BLE stacks/chipsets. Advertisement RSSI needs no
     *  connection-level RSSI support, and is the more common real-world iBeacon calibration
     *  method anyway (hold it at 1m, listen to what it broadcasts). Call [startCalibrating]
     *  first — the beacon must not be GATT-connected for this to see anything. Returns null if
     *  there's no beacon on record at all; each sample is also logged as it arrives, via
     *  [logCalibrationSample], so the operator can watch it live and decide for themselves when
     *  to stop. */
    fun calibrationRssiSamples(): Flow<Int>? {
        val targetAddress = lastAdvertisement?.identifier ?: return null
        return client.scanForDevices()
            .filter { it.identifier == targetAddress }
            .map { it.rssi }
            .onEach(::logCalibrationSample)
    }

    /** Reconnects and re-authenticates after [startCalibrating] — must be called once the
     *  operator stops calibrating, before any further operation touches the beacon. */
    suspend fun finishCalibrating(): Result<Unit> = runCatching {
        reconnectAndReauthenticate(checkNotNull(currentPassword) { "no known password to reauthenticate with" })
        log(BeaconLogDirection.INFO, "Reconnected after calibration.")
    }

    fun logCalibrationSample(rssi: Int) {
        log(BeaconLogDirection.RX, "Advertisement RSSI: $rssi dBm")
    }

    /** Called once the operator stops calibration — logs the median of whatever was collected,
     *  or that nothing was seen. */
    fun logCalibrationResult(median: Int?, sampleCount: Int) {
        if (median != null) {
            log(BeaconLogDirection.INFO, "Calibrated measured power: $median dBm (from $sampleCount samples)")
        } else {
            log(BeaconLogDirection.ERROR, "Calibration stopped with no advertisement RSSI samples seen.")
        }
    }

    /** The one real feature: rewrite the password unless it's already this app's own target,
     *  write the iBeacon frame into [DxCp27Protocol.IBEACON_SLOT], clear every other slot, then
     *  read everything back and verify it genuinely matches what was just written — see this
     *  class's own doc for why that verification matters (a transport-level ack isn't proof the
     *  beacon actually applied it).
     *
     *  Deliberately compares against [DxCp27Protocol.RACEMASTER_PASSWORD] rather than tracking
     *  "was the default" as a separate flag — a beacon can land on some other non-default
     *  password (e.g. a run that changed the password but failed before finishing, or this app's
     *  own target password constant changing between runs), and it still needs fixing up to the
     *  current target, not left alone just because it isn't the factory default.
     *
     *  Likewise skips the iBeacon write entirely (see `alreadyCorrect` below) if the beacon's
     *  currently-loaded config already matches every target field exactly — not just to avoid
     *  pointless I/O, but because every write here ends in an apply, and apply is the step
     *  that's proven disruptive on real hardware (drops the connection for a password change,
     *  can leave the beacon unresponsive for a stretch for an iBeacon/slot write). Don't pay
     *  that cost for a no-op. */
    suspend fun programForRace(minor: Int, measuredPowerDbm: Int): Result<Unit> = withBusyLock {
        runCatching {
            if (currentPassword != DxCp27Protocol.RACEMASTER_PASSWORD) {
                log(BeaconLogDirection.TX, "Changing password from '$currentPassword' to '${DxCp27Protocol.RACEMASTER_PASSWORD}'…")
                val changeResult = client.setPassword(DxCp27Protocol.RACEMASTER_PASSWORD)
                // Real hardware drops the BLE connection once a password change is applied
                // (confirmed against ~/dx-cp27-probing/snooped_data.txt: the real app's own
                // capture shows exactly this, followed by a reconnect and re-authenticating with
                // the new password) — not a failure, so reconnect and continue rather than
                // propagating changeResult's exception.
                log(
                    BeaconLogDirection.RX,
                    if (changeResult.isSuccess) "Password changed." else "Beacon disconnected applying the new password (expected) — reconnecting…",
                )
                reconnectAndReauthenticate(DxCp27Protocol.RACEMASTER_PASSWORD)
                currentPassword = DxCp27Protocol.RACEMASTER_PASSWORD
                log(BeaconLogDirection.INFO, "Re-authenticated with the new password.")
            } else {
                log(BeaconLogDirection.INFO, "Already authenticated with '${DxCp27Protocol.RACEMASTER_PASSWORD}' — skipping password change.")
            }

            val expected = DxCp27Protocol.IBeaconFrame(
                uuid = DxCp27Protocol.RACEMASTER_IBEACON_UUID,
                major = DxCp27Protocol.RACEMASTER_MAJOR,
                minor = minor,
                measuredPowerDbm = measuredPowerDbm,
                intervalRaw = DxCp27Protocol.RACEMASTER_ADV_INTERVAL_RAW,
                txPowerRaw = DxCp27Protocol.RACEMASTER_TX_POWER_RAW,
                // A race beacon must advertise continuously, never just on a click/motion
                // trigger — DxCp27Client.setIBeacon always saves via CMD_SAVE (trigger off, never
                // CMD_SAVE_WITH_TRIGGER), so the read-back must show it off too, not just assume it.
                triggerEnabled = false,
                triggerMode = DxCp27Protocol.TRIGGER_OFF,
                triggerSeconds = 0,
            )
            val current = configFlow.value
            val alreadyCorrect = current != null && current.iBeaconFrame == expected &&
                current.slots.all {
                    when (it.slot) {
                        DxCp27Protocol.PRESERVED_DEVICE_NAME_SLOT -> true // left alone, whatever it holds
                        DxCp27Protocol.IBEACON_SLOT -> it.frameType == DxCp27Protocol.FRAME_IBEACON
                        else -> it.frameType == DxCp27Protocol.FRAME_EMPTY
                    }
                }

            if (alreadyCorrect) {
                // Every write here ends in an apply, and apply is the disruptive step (password
                // changes drop the connection outright; iBeacon/slot writes can leave the beacon
                // unresponsive for a stretch) — so if the beacon already holds exactly these
                // values, skip writing anything at all rather than reapplying a no-op change.
                log(BeaconLogDirection.INFO, "Already programmed with these exact values — nothing to write.")
            } else {
                log(BeaconLogDirection.TX, "Writing iBeacon frame — major ${DxCp27Protocol.RACEMASTER_MAJOR}, minor $minor, measured power $measuredPowerDbm dBm…")
                client.setIBeacon(
                    slot = DxCp27Protocol.IBEACON_SLOT,
                    uuid = DxCp27Protocol.RACEMASTER_IBEACON_UUID,
                    major = DxCp27Protocol.RACEMASTER_MAJOR,
                    minor = minor,
                    measuredPowerDbm = measuredPowerDbm,
                    intervalRaw = DxCp27Protocol.RACEMASTER_ADV_INTERVAL_RAW,
                    txPowerRaw = DxCp27Protocol.RACEMASTER_TX_POWER_RAW,
                ).getOrThrow()
                log(BeaconLogDirection.RX, "iBeacon frame written.")

                // Confirmed on real hardware: applying a settings change (iBeacon frame, slot
                // delete) can leave the very next command waiting indefinitely for a reply that
                // never comes, with no clean disconnect Kable can detect — a longer post-apply
                // settle delay alone didn't fix it. Retrying via a full reconnect + re-auth,
                // exactly like the password-change case above, recovers either way: whether the
                // link actually died silently or the beacon just stopped honoring the old
                // subscription.
                val slotsAfterWrite = retryingAfterReconnect { readSlotsRaw() }
                for (slot in (DxCp27Protocol.IBEACON_SLOT + 1)..7) {
                    if (slotsAfterWrite[slot - 1] != DxCp27Protocol.FRAME_EMPTY) {
                        log(BeaconLogDirection.TX, "Clearing slot $slot (${DxCp27Protocol.frameTypeLabel(slotsAfterWrite[slot - 1])})…")
                        retryingAfterReconnect { client.deleteSlot(slot).getOrThrow() }
                        log(BeaconLogDirection.RX, "Slot $slot cleared.")
                    }
                }

                log(BeaconLogDirection.INFO, "Verifying…")
                val finalSlots = retryingAfterReconnect { readSlotsRaw() }
                val otherSlotsEmpty = ((DxCp27Protocol.IBEACON_SLOT + 1)..7).all { finalSlots[it - 1] == DxCp27Protocol.FRAME_EMPTY }
                check(finalSlots[DxCp27Protocol.IBEACON_SLOT - 1] == DxCp27Protocol.FRAME_IBEACON && otherSlotsEmpty) {
                    "slot table after programming was $finalSlots, expected [preserved, iBeacon, empty×5]"
                }
                val written = retryingAfterReconnect { client.readFrame(DxCp27Protocol.FRAME_IBEACON).getOrThrow() }
                    .let(DxCp27Protocol::parseIBeaconFrame)
                check(written == expected) { "read-back frame $written didn't match what was written ($expected)" }
                log(BeaconLogDirection.INFO, "Programmed and verified — minor $minor, measured power $measuredPowerDbm dBm.")
            }
        }.onFailure { e -> log(BeaconLogDirection.ERROR, "Programming failed: ${e.message}") }
    }

    /** Reconnects to [lastAdvertisement] and authenticates with [password], retrying with a
     *  growing delay — the beacon needs real time to reboot/reinitialize its BLE stack after an
     *  apply (confirmed on real hardware that reconnecting right away, relying only on
     *  `connect()`'s own short settle delay, was too fast and just timed out again). */
    private suspend fun reconnectAndReauthenticate(password: String) {
        val advertisement = checkNotNull(lastAdvertisement) { "no advertisement on record to reconnect with" }
        var reconnectError: Throwable? = null
        for (attempt in 1..RECONNECT_AFTER_APPLY_ATTEMPTS) {
            log(BeaconLogDirection.INFO, "Waiting for the beacon to come back (attempt $attempt)…")
            delay(RECONNECT_AFTER_APPLY_DELAY * attempt)
            val reconnected = runCatching {
                client.connect(advertisement).getOrThrow()
                client.authenticate(password).getOrThrow()
                readSlotsRaw()
            }
            reconnectError = reconnected.exceptionOrNull()
            if (reconnectError == null) return
        }
        throw checkNotNull(reconnectError)
    }

    /** Runs [block] once; if it throws, reconnects and re-authenticates with the password
     *  already confirmed working this session, then retries [block] exactly once more. */
    private suspend fun <T> retryingAfterReconnect(block: suspend () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            log(BeaconLogDirection.INFO, "Lost contact with the beacon (${e.message}) — reconnecting…")
            reconnectAndReauthenticate(checkNotNull(currentPassword) { "no known password to reauthenticate with" })
            log(BeaconLogDirection.INFO, "Reconnected.")
            block()
        }

    companion object {
        private const val MAX_LOG_ENTRIES = 500

        // 3 attempts at 3s/6s/9s (18s total) — roughly matches the gap seen in the real capture
        // between the beacon disconnecting and the next successful reconnect.
        private const val RECONNECT_AFTER_APPLY_ATTEMPTS = 3
        private val RECONNECT_AFTER_APPLY_DELAY = 3_000.milliseconds
    }
}
