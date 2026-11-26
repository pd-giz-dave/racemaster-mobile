package mobile.racemaster.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import mobile.racemaster.data.mule.BluetoothStateRepository
import mobile.racemaster.data.mule.BtPollingStatus
import mobile.racemaster.data.mule.LocationStateRepository
import mobile.racemaster.data.settings.SettingsRepository

// Small shared StateFlow wiring used identically by several ViewModels — kept as plain
// extension functions (not a shared base class) since each ViewModel otherwise differs enough
// in shape that a base class would buy little beyond these few lines.

/** Device-wide, not tied to whether a race is selected — a device's own name has nothing to do
 *  with race state. Shared by TimeModeViewModel/BibsModeViewModel/RaceDetailsViewModel. */
fun ViewModel.deviceNameFlow(settingsRepository: SettingsRepository): StateFlow<String?> =
    settingsRepository.deviceName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

/** Device-wide, not tied to whether a race is selected — whether this phone is actually being
 *  polled over BT has nothing to do with race state. Shared by TimeModeViewModel/BibsModeViewModel.
 *  [SettingsRepository.bluetoothOff] is this app's own in-app Bluetooth toggle (Mule Mode's own
 *  Setup Options screen — see [BtPollingStatus.bluetoothOff]'s own doc) — included here so a
 *  device left in that state shows it on every mode screen, not only the one screen that can
 *  actually flip it back. Confirmed in the field: an operator had no way to tell a device was
 *  deliberately not advertising at all (plain "Last polled by BT: never", indistinguishable from
 *  "nobody's nearby yet") versus actively failing, since nothing surfaced this setting outside
 *  Mule Mode's own screen. */
fun ViewModel.btPollingStatusFlow(
    bluetoothStateRepository: BluetoothStateRepository,
    locationStateRepository: LocationStateRepository,
    settingsRepository: SettingsRepository,
): StateFlow<BtPollingStatus> =
    combine(
        bluetoothStateRepository.advertisingWarning,
        bluetoothStateRepository.lastPolledAtMillis,
        locationServicesEnabledFlow(locationStateRepository),
        settingsRepository.bluetoothOff,
        bluetoothStateRepository.observeEnabled(),
        ::BtPollingStatus,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BtPollingStatus())

// LocationManager has no built-in reactive "is it on" Flow the way BluetoothStateRepository's
// own advertisingWarning/lastPolledAtMillis are genuine StateFlows — this toggle lives entirely
// outside this app's own code (the operator flips it from system Settings), so a plain re-check
// ticker is the simplest way to notice a change without a BroadcastReceiver this codebase has no
// other precedent for. Top-level (not a ViewModel extension) so ModePickerViewModel's own inline
// combine (see its own doc for why it doesn't share btPollingStatusFlow above) can reuse this one
// ticker rather than writing a second copy of the loop itself.
internal fun locationServicesEnabledFlow(locationStateRepository: LocationStateRepository): Flow<Boolean> = flow {
    while (true) {
        emit(locationStateRepository.isEnabled())
        delay(LOCATION_POLL_INTERVAL.inWholeMilliseconds)
    }
}

private val LOCATION_POLL_INTERVAL = 5.seconds

/** Every race name previously saved from Setup Race/Relocate, most-recent-first — lets a form's
 *  name field offer a history dropdown. Shared by SetupRaceViewModel/RaceDetailsViewModel. */
fun ViewModel.raceNameHistoryFlow(settingsRepository: SettingsRepository): StateFlow<List<String>> =
    settingsRepository.raceNameHistory.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

/** Every location previously saved from Setup Race/Relocate, most-recent-first — lets a form's
 *  location field offer a history dropdown. Shared by SetupRaceViewModel/RaceDetailsViewModel. */
fun ViewModel.locationHistoryFlow(settingsRepository: SettingsRepository): StateFlow<List<String>> =
    settingsRepository.locationHistory.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
