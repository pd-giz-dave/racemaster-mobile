package mobile.racemaster.data.mule

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds
import mobile.racemaster.MainActivity
import mobile.racemaster.RacemasterApplication
import mobile.racemaster.data.settings.AppMode

/**
 * Runs on every device (Time, Bibs, and Mule) regardless of which screen is showing, so a
 * Mule can discover and pull from a Time/Bibs phone without its operator doing anything.
 * Advertises [MuleGattProfile.SERVICE_UUID] and answers a pull request by streaming this
 * device's own unsynced splits/entries (there's nothing to serve while in Mule Mode itself —
 * Mule-to-mule relay is a follow-up, not part of this pass).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeripheralSyncService : Service() {

    private val container by lazy { (application as RacemasterApplication).container }
    private val serviceJob = SupervisorJob()

    // A background service that's always running and talks to arbitrary other phones over
    // BLE is exactly the place a stray uncaught exception (a BLE stack quirk, a malformed
    // peer response, ...) must never crash the whole app — the operator could be mid-race on
    // an unrelated screen. Without this handler, any exception escaping a serviceScope.launch
    // block (Kotlin coroutines' default behavior) takes down the entire process; this logs it
    // instead. Individual call sites should still catch what they can anticipate — this is
    // the last-resort net, not a substitute for that.
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught exception in PeripheralSyncService — swallowed to avoid crashing the app", throwable)
    }
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO + exceptionHandler)
    private val json = Json { ignoreUnknownKeys = true }

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var dataCharacteristic: BluetoothGattCharacteristic? = null

    private var deviceId: String = ""
    private var deviceName: String = ""

    @Volatile
    private var servingState = ServingState()

    private data class ServingState(
        val mode: AppMode? = null,
        val raceId: Long? = null,
        val raceLabel: String = "",
        val unsyncedCount: Int = 0,
    )

    // Chunked notification send queue, one per connected central (keyed by MAC address).
    private val outboundChunks = mutableMapOf<String, ArrayDeque<ByteArray>>()

    // Negotiated ATT MTU per connected central (keyed by MAC address) — see onMtuChanged.
    // Falls back to MuleGattProfile.FALLBACK_CHUNK_SIZE_BYTES for a device that never
    // negotiates (or hasn't yet when streamRecords() runs).
    private val deviceMtus = mutableMapOf<String, Int>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        observeServingState()
        startSelfSyncLoop()
        startAdvertisingRetryLoop()
    }

    // Advertising (and the GATT server backing it) used to start once, at service creation,
    // gated on a one-shot Bluetooth-enabled check — meaning a device that had Bluetooth off
    // at that exact moment (or toggled it off and back on at any later point) would silently
    // never advertise itself again for the rest of the process's life, with no way to
    // recover short of killing the whole app. This instead re-checks periodically and
    // (re)establishes the server/advertiser fresh on every transition from disabled to
    // enabled, so every device stays discoverable whenever Bluetooth genuinely is available
    // — in every mode (Time/Bibs/Mule), matching the same requirement the scanning side
    // already got (see BluetoothStateRepository / MuleModeViewModel's own retry loop).
    private fun startAdvertisingRetryLoop() {
        serviceScope.launch {
            // Advertising must wait for deviceName to actually resolve first — attempting it
            // immediately would race the DataStore read and could advertise a blank name for
            // the brief window before it completes. getOrCreateDeviceName() always ends up
            // with a real generated name, so this is purely an ordering fix, not a "wait
            // indefinitely" concern.
            deviceId = container.settingsRepository.getOrCreateDeviceId()
            deviceName = container.settingsRepository.getOrCreateDeviceName()
            var wasEnabled = false
            while (isActive) {
                val enabled = container.bluetoothStateRepository.isEnabled()
                if (enabled) {
                    if (!wasEnabled) {
                        // Just came on (or this is the first tick) — any earlier
                        // server/advertiser reference is stale once the radio's cycled, so
                        // tear down before rebuilding fresh rather than trusting it's still
                        // good.
                        closeGattServerIfPossible()
                        stopAdvertisingIfPossible()
                        gattServer = null
                    }
                    if (gattServer == null) startGattServerAndAdvertising()
                }
                wasEnabled = enabled
                delay(BLUETOOTH_RECHECK_INTERVAL)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopAdvertisingIfPossible()
        closeGattServerIfPossible()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun observeServingState() {
        serviceScope.launch {
            container.settingsRepository.activeRaceId
                .flatMapLatest { raceId ->
                    if (raceId == null) {
                        flowOf(ServingState())
                    } else {
                        combine(
                            container.settingsRepository.appMode,
                            container.raceRepository.observeRace(raceId),
                            container.timeModeRepository.observeUnsyncedCount(raceId),
                            container.bibsModeRepository.observeUnsyncedCount(raceId),
                        ) { mode, race, unsyncedSplits, unsyncedEntries ->
                            ServingState(
                                mode = mode,
                                raceId = raceId,
                                raceLabel = race?.label.orEmpty(),
                                unsyncedCount = when (mode) {
                                    AppMode.TIME -> unsyncedSplits
                                    AppMode.BIBS -> unsyncedEntries
                                    else -> 0
                                },
                            )
                        }
                    }
                }
                .distinctUntilChanged()
                .collect { servingState = it }
        }
    }

    // --- Self-push: a plain Time/Bibs phone pushing its own data straight to the server,
    // with no separate physical Mule involved. Uses the same login/dataset/stop settings
    // the operator configures via this same phone's Mule Mode screen (they're just shared
    // settings, not something exclusive to being in Mule Mode) — and running here, in the
    // always-on service rather than tied to a screen, means it keeps working regardless of
    // which screen is showing. This never disables the GATT server above, so a separate
    // physical Mule can still discover and pull from this device independently at any time
    // — the two paths just race harmlessly to mark the same records synced first.
    // --------------------------------------------------------------------------------------

    private fun startSelfSyncLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(SELF_SYNC_INTERVAL)
                runCatching { selfPushIfConfigured() }
                    .onFailure { Log.w(TAG, "Self-push tick failed", it) }
            }
        }
    }

    private suspend fun selfPushIfConfigured() {
        val snapshot = servingState
        val role = when (snapshot.mode) {
            AppMode.TIME -> DEVICE_ROLE_TIME
            AppMode.BIBS -> DEVICE_ROLE_BIBS
            // Mule mode has its own auto-sync loop (pulling from other devices), driven by
            // MuleModeViewModel while that screen is open — nothing to self-push here.
            else -> return
        }
        if (container.settingsRepository.autoSyncStopped.first()) return
        if (container.settingsRepository.authToken.first() == null) return

        val raceId = snapshot.raceId ?: return
        // Only bother pushing if something's actually new — but once triggered, send the
        // *full* current set below (not just the delta), so a deleted/corrupted server-side
        // file gets fully reconstructed on the next push rather than only receiving whatever
        // changed since.
        if (snapshot.unsyncedCount <= 0) return
        val race = container.raceRepository.getRace(raceId)
        val raceLabel = race?.label ?: return
        val records: List<SyncRecord> = when (snapshot.mode) {
            AppMode.TIME -> container.timeModeRepository.getAllSplits(raceId).map { it.toSyncRecord(race.timeModeStartedAtMillis) }
            AppMode.BIBS -> container.bibsModeRepository.getAllEntries(raceId).map { it.toSyncRecord() }
            else -> emptyList()
        }
        if (records.isEmpty()) return

        val pushed = container.muleRepository.pushOwnRecords(role, raceLabel, records)
        if (!pushed) return
        val uuids = records.map { it.recordUuid }
        when (snapshot.mode) {
            AppMode.TIME -> container.timeModeRepository.markSplitsSyncedByUuid(uuids)
            AppMode.BIBS -> container.bibsModeRepository.markEntriesSyncedByUuid(uuids)
            else -> {}
        }
    }

    // --- GATT server setup -------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startGattServerAndAdvertising() {
        // Belt-and-braces alongside the call-site ordering in onCreate() — a nameless
        // device has nothing meaningful to identify itself with in the nearby-devices list
        // (see MuleModeScreen), so it shouldn't be discoverable at all until it has one.
        if (deviceName.isBlank()) return
        if (!hasConnectPermission() || !hasAdvertisePermission()) return
        val bluetoothManager = getSystemService(BluetoothManager::class.java) ?: return
        val adapter = bluetoothManager.adapter ?: return
        if (!adapter.isEnabled) return

        val server = bluetoothManager.openGattServer(this, gattServerCallback) ?: return
        gattServer = server

        val deviceInfoCharacteristic = BluetoothGattCharacteristic(
            MuleGattProfile.DEVICE_INFO_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val controlCharacteristic = BluetoothGattCharacteristic(
            MuleGattProfile.CONTROL_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val dataCharacteristicLocal = BluetoothGattCharacteristic(
            MuleGattProfile.DATA_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CLIENT_CHARACTERISTIC_CONFIG_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        val ackCharacteristic = BluetoothGattCharacteristic(
            MuleGattProfile.ACK_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        dataCharacteristic = dataCharacteristicLocal

        val service = BluetoothGattService(MuleGattProfile.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(deviceInfoCharacteristic)
            addCharacteristic(controlCharacteristic)
            addCharacteristic(dataCharacteristicLocal)
            addCharacteristic(ackCharacteristic)
        }
        server.addService(service)

        startAdvertising(adapter)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(adapter: BluetoothAdapter) {
        if (!hasAdvertisePermission()) return
        val advertiserLocal = adapter.bluetoothLeAdvertiser ?: return
        advertiser = advertiserLocal
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(MuleGattProfile.SERVICE_UUID))
            .build()
        advertiserLocal.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE advertising failed to start: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisingIfPossible() {
        if (hasAdvertisePermission()) {
            advertiser?.stopAdvertising(advertiseCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGattServerIfPossible() {
        if (hasConnectPermission()) {
            gattServer?.close()
        }
    }

    // --- GATT server callback -----------------------------------------------------------

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != MuleGattProfile.DEVICE_INFO_CHARACTERISTIC_UUID) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                return
            }
            val info = DeviceInfo(
                deviceId = deviceId,
                deviceRole = servingState.mode?.name.orEmpty(),
                raceLabel = servingState.raceLabel,
                unsyncedCount = servingState.unsyncedCount,
                deviceName = deviceName,
            )
            val bytes = json.encodeToString(info).toByteArray(Charsets.UTF_8)
            val value = bytes.drop(offset).toByteArray()
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            when (characteristic.uuid) {
                MuleGattProfile.CONTROL_CHARACTERISTIC_UUID -> {
                    val command = String(value, Charsets.UTF_8)
                    if (command == MuleGattProfile.PULL_COMMAND) {
                        serviceScope.launch { streamRecords(device) }
                    }
                }
                MuleGattProfile.ACK_CHARACTERISTIC_UUID -> {
                    val recordUuids = runCatching { json.decodeFromString<List<String>>(String(value, Charsets.UTF_8)) }
                        .getOrDefault(emptyList())
                    serviceScope.launch { markSynced(recordUuids) }
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            // Central enabling/disabling notifications on the data characteristic — no local
            // state to track beyond acknowledging the write so the central's stack is happy.
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            sendNextChunk(device)
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                outboundChunks.remove(device.address)
                deviceMtus.remove(device.address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            deviceMtus[device.address] = mtu
        }
    }

    // --- Streaming unsynced records out to a connected Mule ------------------------------

    private suspend fun streamRecords(device: BluetoothDevice) {
        val snapshot = servingState
        val raceId = snapshot.raceId ?: return
        val records: List<SyncRecord> = when (snapshot.mode) {
            AppMode.TIME -> {
                val race = container.raceRepository.getRace(raceId)
                container.timeModeRepository.getUnsyncedSplits(raceId)
                    .map { it.toSyncRecord(race?.timeModeStartedAtMillis) }
            }
            AppMode.BIBS -> {
                container.bibsModeRepository.getUnsyncedEntries(raceId).map { it.toSyncRecord() }
            }
            else -> emptyList()
        }
        // ATT header is 3 bytes, so the usable notification payload is (MTU - 3). Sizing to
        // whatever actually got negotiated (see onMtuChanged) is what fixed real truncation
        // corruption in the field: a fixed chunk size larger than the un-negotiated default
        // MTU (23 bytes) gets silently cut off in transit by the BLE stack. The negotiated
        // MTU is *not* a reliable upper bound on its own though — Android doesn't guarantee
        // it matches what was requested, and a large enough negotiated value here produced a
        // chunk over the platform's hard 512-byte GATT attribute limit, which crashed the
        // whole app (notifyCharacteristicChanged throws, uncaught, on this coroutine) rather
        // than just failing the one transfer — so this is always capped to a safe max too.
        val negotiatedMtu = deviceMtus[device.address]
        val chunkSize = (negotiatedMtu?.let { (it - 3).coerceAtLeast(1) } ?: MuleGattProfile.FALLBACK_CHUNK_SIZE_BYTES)
            .coerceAtMost(MuleGattProfile.MAX_SAFE_CHUNK_SIZE_BYTES)

        val bytes = json.encodeToString(records).toByteArray(Charsets.UTF_8)
        val chunks = ArrayDeque(
            bytes.toList().chunked(chunkSize).map { it.toByteArray() } +
                listOf(byteArrayOf(MuleGattProfile.END_OF_STREAM_MARKER)),
        )
        outboundChunks[device.address] = chunks
        sendNextChunk(device)
    }

    @SuppressLint("MissingPermission")
    private fun sendNextChunk(device: BluetoothDevice) {
        if (!hasConnectPermission()) return
        val queue = outboundChunks[device.address] ?: return
        val chunk = queue.removeFirstOrNull() ?: return
        val characteristic = dataCharacteristic ?: return
        val server = gattServer ?: return
        // Belt-and-suspenders alongside the chunk-size cap in streamRecords(): a BLE API call
        // must never be allowed to crash this always-on service, no matter what edge case
        // produces a value the platform rejects — that took down the whole app in the field
        // (see MAX_SAFE_CHUNK_SIZE_BYTES). Worst case here is this one transfer stalls (the
        // Mule-side pull times out and can retry), not the operator's app disappearing
        // mid-race on an unrelated screen.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+: takes the value directly rather than mutating the characteristic's
                // own (deprecated, and not thread-safe to share across in-flight chunks) value
                // field.
                server.notifyCharacteristicChanged(device, characteristic, false, chunk)
            } else {
                // No alternative below API 33 (minSdk 24) — the mutate-then-notify pair is the
                // only API available, so it's suppressed here rather than avoided.
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        }.onFailure { Log.e(TAG, "notifyCharacteristicChanged failed for chunk of ${chunk.size} bytes", it) }
    }

    private suspend fun markSynced(recordUuids: List<String>) {
        if (recordUuids.isEmpty()) return
        when (servingState.mode) {
            AppMode.TIME -> container.timeModeRepository.markSplitsSyncedByUuid(recordUuids)
            AppMode.BIBS -> container.bibsModeRepository.markEntriesSyncedByUuid(recordUuids)
            else -> {}
        }
    }

    // --- Notification / permissions -----------------------------------------------------

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Race data sync", NotificationManager.IMPORTANCE_MIN),
                )
            }
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RaceMaster sync")
            .setContentText("Ready for a Mule to connect")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasAdvertisePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "PeripheralSyncService"
        private const val CHANNEL_ID = "mule_sync"
        private const val NOTIFICATION_ID = 1
        private val SELF_SYNC_INTERVAL = 20_000.milliseconds
        private val BLUETOOTH_RECHECK_INTERVAL = 3_000.milliseconds
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** No-ops quietly if Bluetooth runtime permissions haven't been granted yet — the
         *  permission-request flow calls this again once they have been. */
        fun startIfPermitted(context: Context) {
            val hasConnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val hasAdvertise = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            if (!hasConnect || !hasAdvertise) return
            // Called unconditionally from Application.onCreate(), which also runs when the
            // OS silently respawns the process in the background (e.g. after a previous
            // crash, restarting a START_STICKY service) with no foreground Activity — Android
            // 12+ throws ForegroundServiceStartNotAllowedException in exactly that situation,
            // which would otherwise crash the app a second time on its very next launch
            // attempt (confirmed via a real device crash log cascading from an unrelated
            // service crash). Best-effort: if it's blocked, the service just doesn't start
            // this time; MuleModeScreen/permission flow retries it from a foreground context.
            try {
                ContextCompat.startForegroundService(context, Intent(context, PeripheralSyncService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start PeripheralSyncService (no foreground context available)", e)
            }
        }
    }
}
