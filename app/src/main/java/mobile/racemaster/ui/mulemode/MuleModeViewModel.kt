package mobile.racemaster.ui.mulemode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mobile.racemaster.data.db.entity.KnownDeviceEntity
import mobile.racemaster.data.mule.DiscoveredDevice
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.mule.MuleSyncEngine
import mobile.racemaster.data.mule.previouslySeenDevices
import mobile.racemaster.di.appContainer

data class MuleModeUiState(
    // Live BLE-discovered/relayed devices, self, and stale entries from the persisted
    // known-devices roster (see DiscoveredDevice.isStale's own doc) — one flat list, sorted
    // most-recently-seen first, so a device that's gone quiet sinks toward the bottom in place
    // rather than living in a separate section.
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val unsyncedCount: Int = 0,
    val lastSyncedAtMillis: Long? = null,
    val lastPulledAtMillis: Long? = null,
    val isLoggedIn: Boolean = false,
    val statusMessage: String? = null,
    val autoWarning: String? = null,
    val bluetoothWarning: String? = null,
    val isBusy: Boolean = false,
    val autoSyncStopped: Boolean = false,
    val autoSyncArmed: Boolean = false,
    val bluetoothOff: Boolean = false,
    val serverSyncOff: Boolean = false,
)

/**
 * Thin presentation layer over [MuleSyncEngine]: renders its flows and forwards this
 * screen's button taps to it, but owns none of the actual scanning/pulling/pushing itself.
 * That all lives in the engine, which [mobile.racemaster.data.mule.PeripheralSyncService]
 * starts once for the life of the process — so it keeps running (and this phone keeps
 * acting as a Mule for every other nearby device, in parallel with whatever mode it's
 * itself recording) regardless of whether this screen/ViewModel is even alive.
 */
class MuleModeViewModel(
    private val muleRepository: MuleRepository,
    private val muleSyncEngine: MuleSyncEngine,
) : ViewModel() {

    val deviceName: StateFlow<String?> = muleRepository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<MuleModeUiState> = combine(
        muleSyncEngine.discoveredDevices,
        muleRepository.unsyncedCount,
        muleRepository.lastSyncedAtMillis,
        muleRepository.lastPulledAtMillis,
        muleRepository.isLoggedIn,
        muleSyncEngine.statusMessage,
        muleSyncEngine.autoWarning,
        muleSyncEngine.isBusy,
        muleRepository.autoSyncStopped,
        muleSyncEngine.bluetoothWarning,
        muleSyncEngine.selfDevice,
        muleRepository.bluetoothOff,
        muleRepository.serverSyncOff,
        muleSyncEngine.relayDevices,
        muleRepository.knownDevices,
    ) { values ->
        val isLoggedIn = values[4] as Boolean
        val autoSyncStopped = values[8] as Boolean
        val selfDevice = values[10] as DiscoveredDevice
        val bluetoothOff = values[11] as Boolean
        val serverSyncOff = values[12] as Boolean
        val relayDevices = values[13] as Map<String, DiscoveredDevice>
        val knownDevices = values[14] as List<KnownDeviceEntity>
        val directDevices = values[0] as Map<String, DiscoveredDevice>
        val liveDeviceIds = (directDevices.values + relayDevices.values).mapNotNull { it.deviceId }.toSet()
        // Stale rows: a device this phone has resolved before but can't currently see, given
        // no live advertisement/unreachable-tracking of its own — see DiscoveredDevice.isStale's
        // own doc. lastReachableAtMillis is repurposed to mean "last resolved at all" for these,
        // which is exactly what sorts them toward the bottom below.
        val staleDevices = previouslySeenDevices(knownDevices, liveDeviceIds).map { known ->
            DiscoveredDevice(
                deviceKey = known.deviceId,
                advertisement = null,
                deviceId = known.deviceId,
                deviceName = known.deviceName,
                lastReachableAtMillis = known.lastSeenAtMillis,
                isStale = true,
            )
        }
        MuleModeUiState(
            discoveredDevices = (directDevices.values + relayDevices.values + selfDevice + staleDevices)
                .sortedByDescending { it.lastReachableAtMillis },
            unsyncedCount = values[1] as Int,
            lastSyncedAtMillis = values[2] as Long?,
            lastPulledAtMillis = values[3] as Long?,
            isLoggedIn = isLoggedIn,
            statusMessage = values[5] as String?,
            autoWarning = values[6] as String?,
            bluetoothWarning = values[9] as String?,
            isBusy = values[7] as Boolean,
            autoSyncStopped = autoSyncStopped,
            // Armed once logged in, auto-sync hasn't been explicitly stopped, and server sync
            // hasn't been explicitly turned off — no longer gated on any particular device
            // being visible, since every device seen (plus self) is synced automatically each
            // tick anyway. Deliberately not gated on bluetoothOff: that only affects the pull
            // side (nothing to discover), not whether this device still pushes to the server.
            autoSyncArmed = isLoggedIn && !autoSyncStopped && !serverSyncOff,
            bluetoothOff = bluetoothOff,
            serverSyncOff = serverSyncOff,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MuleModeUiState())

    fun forceSyncNow() {
        muleSyncEngine.forceSyncNow()
    }

    fun stopAutoSync() {
        viewModelScope.launch { muleRepository.setAutoSyncStopped(true) }
    }

    fun resumeAutoSync() {
        viewModelScope.launch { muleRepository.setAutoSyncStopped(false) }
    }

    fun turnBluetoothOff() {
        viewModelScope.launch { muleRepository.setBluetoothOff(true) }
    }

    fun turnBluetoothOn() {
        viewModelScope.launch { muleRepository.setBluetoothOff(false) }
    }

    fun turnServerSyncOff() {
        viewModelScope.launch { muleRepository.setServerSyncOff(true) }
    }

    fun turnServerSyncOn() {
        viewModelScope.launch { muleRepository.setServerSyncOff(false) }
    }

    fun dismissStatusMessage() {
        muleSyncEngine.dismissStatusMessage()
    }

    // See MuleSyncEngine.forgetDevice's own doc — key is a resolved device's deviceId (also
    // works for a relay-only row, matched by its true origin id) or an unresolved ghost's raw
    // BLE address, whichever MuleModeScreen had on hand for the row being forgotten.
    fun forgetDevice(key: String) {
        muleSyncEngine.forgetDevice(key)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                MuleModeViewModel(
                    container.muleRepository,
                    container.muleSyncEngine,
                )
            }
        }
    }
}
