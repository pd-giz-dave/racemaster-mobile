package mobile.racemaster.ui.mulemode

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mobile.racemaster.data.mule.DiscoveredDevice
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.mule.MuleSyncEngine
import mobile.racemaster.di.appContainer
import mobile.racemaster.di.applicationContext
import mobile.racemaster.util.hasInternetConnectivity

data class MuleModeUiState(
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
    // Typed as Application, not Context — see ViewModelFactorySupport.applicationContext's own
    // doc for why that's what keeps Lint's StaticFieldLeak check from flagging this field.
    private val context: Application,
) : ViewModel() {

    val deviceName: StateFlow<String?> = muleRepository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Read directly rather than via uiState.isLoggedIn — that StateFlow's initial value is
    // a synthetic "not logged in" default until the underlying DataStore read actually
    // completes, which would otherwise misfire the screen's auto-forward-to-Setup-Server
    // check (see MuleModeScreen) for an operator who genuinely already is logged in.
    suspend fun isLoggedIn(): Boolean = muleRepository.isLoggedIn.first()

    // Checked once, at the same moment as isLoggedIn() above (see MuleModeScreen) — a hint
    // for which of "With server"/"Without server" to recommend, not a live subscription.
    fun hasInternetConnectivity(): Boolean = hasInternetConnectivity(context)

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
    ) { values ->
        val isLoggedIn = values[4] as Boolean
        val autoSyncStopped = values[8] as Boolean
        val selfDevice = values[10] as DiscoveredDevice
        MuleModeUiState(
            discoveredDevices = ((values[0] as Map<String, DiscoveredDevice>).values + selfDevice)
                .sortedBy { it.raceLabel.ifEmpty { it.deviceKey } },
            unsyncedCount = values[1] as Int,
            lastSyncedAtMillis = values[2] as Long?,
            lastPulledAtMillis = values[3] as Long?,
            isLoggedIn = isLoggedIn,
            statusMessage = values[5] as String?,
            autoWarning = values[6] as String?,
            bluetoothWarning = values[9] as String?,
            isBusy = values[7] as Boolean,
            autoSyncStopped = autoSyncStopped,
            // Armed once logged in and auto-sync hasn't been explicitly stopped — no longer
            // gated on any particular device being visible, since every device seen (plus
            // self) is synced automatically each tick anyway.
            autoSyncArmed = isLoggedIn && !autoSyncStopped,
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

    fun dismissStatusMessage() {
        muleSyncEngine.dismissStatusMessage()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                MuleModeViewModel(
                    container.muleRepository,
                    container.muleSyncEngine,
                    applicationContext(),
                )
            }
        }
    }
}
