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
 * Mule-to-mule relay is a follow-up, not part of this pass). Also starts [MuleSyncEngine]
 * unconditionally — so every phone, regardless of its own current mode, is simultaneously
 * scanning for and pulling from every *other* nearby device and pushing to the server, not
 * just serving/self-pushing its own data. This is what lets a single phone record Time or
 * Bibs mode and act as a Mule at the same time.
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

    // Kept live for this service's whole lifetime by observeDeviceName() below — a rename via
    // NameDeviceScreen must reach onCharacteristicReadRequest's DeviceInfo replies immediately,
    // not just the next time this long-running service happens to restart. @Volatile for the
    // same cross-thread visibility reason as servingState below — written from a
    // serviceScope coroutine, read from the GATT callback thread.
    @Volatile
    private var deviceName: String = ""

    @Volatile
    private var servingState = ServingState()

    private data class ServingState(
        val mode: AppMode? = null,
        val raceId: Long? = null,
        val raceLabel: String = "",
        val lastLineNumber: Long = 0,
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
        observeDeviceName()
        startAdvertisingRetryLoop()
        container.muleSyncEngine.start()
    }

    // Keeps the `deviceName` field genuinely live for the rest of this process's life —
    // without this, a rename via NameDeviceScreen only ever reached SettingsRepository's own
    // DataStore, never this already-running service, so onCharacteristicReadRequest kept
    // answering every DeviceInfo read with whatever name was current when the service last
    // started (confirmed in the field: a device renamed mid-session kept showing its old name
    // to every nearby Mule until the app itself was killed and relaunched). A rename now
    // reaches every device already scanning for this one on its very next DeviceInfo read,
    // same as any other change to servingState.
    private fun observeDeviceName() {
        serviceScope.launch {
            container.settingsRepository.deviceName.collect { name ->
                if (!name.isNullOrBlank()) deviceName = name
            }
        }
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

    // A foreground service otherwise keeps running (and advertising) even after the user
    // swipes the app away from Recents — by design, that's what lets a Mule keep relaying in
    // the background while the operator is on another screen or the phone's asleep. But once
    // the whole app is genuinely dismissed, this device shouldn't keep showing up as a nearby
    // advertiser to every other phone forever; stopSelf() here tears everything down via the
    // usual onDestroy() path below. android:stopWithTask="true" (see the manifest) is the
    // declarative form of the same intent, but relying on that alone has proven unreliable
    // for foreground services on some OEM Android skins — this is the explicit, always-honored
    // backstop.
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

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
                        ) { mode, race ->
                            ServingState(
                                mode = mode,
                                raceId = raceId,
                                raceLabel = race?.label.orEmpty(),
                                lastLineNumber = (race?.nextLineNumber ?: 1) - 1,
                            )
                        }
                    }
                }
                .distinctUntilChanged()
                .collect { servingState = it }
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
                lastLineNumber = servingState.lastLineNumber,
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
                    val request = runCatching { json.decodeFromString<PullRequest>(String(value, Charsets.UTF_8)) }.getOrNull()
                    if (request != null) {
                        serviceScope.launch { streamRecords(device, request.sinceLineNumber) }
                    }
                }
                MuleGattProfile.ACK_CHARACTERISTIC_UUID -> {
                    val ack = runCatching { json.decodeFromString<AckPayload>(String(value, Charsets.UTF_8)) }.getOrNull()
                    if (ack != null) {
                        serviceScope.launch { markSynced(ack.recordUuids, ack.deviceId, ack.deviceName) }
                    }
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

    // --- Streaming delta records out to a connected Mule ---------------------------------

    private suspend fun streamRecords(device: BluetoothDevice, sinceLineNumber: Long) {
        val raceId = servingState.raceId ?: return
        // Unconditional — not scoped to servingState.mode: a mixed-mode race (nothing in the
        // app prevents switching AppMode without starting a new race) must still sync
        // everything this device holds over BLE, regardless of which mode's screen the
        // operator currently has open. Fixes a latent bug: this used to only ever stream
        // whichever category matched the currently displayed screen.
        val race = container.raceRepository.getRace(raceId)
        val records = container.raceRepository.getHistorySinceLineNumber(raceId, sinceLineNumber)
            .map { it.toSyncRecord(race?.timeModeStartedAtMillis) }
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

    // [ackDeviceId]/[ackDeviceName] identify the puller that just took these records —
    // recorded per-line as "synced to" feedback (see LineSyncEntity) alongside the existing
    // syncedAtMillis marking. Unconditional — not scoped to servingState.mode, for the same
    // reason as streamRecords: a mixed-mode race's ack can cover rows from either family.
    private suspend fun markSynced(recordUuids: List<String>, ackDeviceId: String, ackDeviceName: String) {
        if (recordUuids.isEmpty()) return
        val raceId = servingState.raceId ?: return
        container.raceRepository.markHistorySyncedByUuid(recordUuids)
        val lineNumbers = container.raceRepository.getHistoryLineNumbersForUuids(recordUuids)
        container.raceRepository.recordLineSyncs(raceId, lineNumbers, ackDeviceId, targetName = ackDeviceName)
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
        private val BLUETOOTH_RECHECK_INTERVAL = 3_000.milliseconds
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** No-ops quietly if Bluetooth runtime permissions haven't been granted yet — the
         *  permission-request flow calls this again once they have been. */
        fun startIfPermitted(context: Context) {
            val hasConnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val hasAdvertise = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            // Missing before: this service also drives MuleSyncEngine's scan (see onCreate
            // below), which needs BLUETOOTH_SCAN, not just CONNECT/ADVERTISE — without this
            // check the service could start with scanning permanently broken, which
            // MuleSyncEngine's generic exception handler then misreported as "Bluetooth is
            // off" instead of a permission problem.
            val hasScan = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            if (!hasConnect || !hasAdvertise || !hasScan) return
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
