package mobile.racemaster.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import mobile.racemaster.data.mule.BluetoothStateRepository
import mobile.racemaster.data.mule.BtPollingStatus
import mobile.racemaster.data.settings.SettingsRepository

// Small shared StateFlow wiring used identically by several ViewModels — kept as plain
// extension functions (not a shared base class) since each ViewModel otherwise differs enough
// in shape that a base class would buy little beyond these few lines.

/** Device-wide, not tied to whether a race is selected — a device's own name has nothing to do
 *  with race state. Shared by TimeModeViewModel/BibsModeViewModel/RaceDetailsViewModel. */
fun ViewModel.deviceNameFlow(settingsRepository: SettingsRepository): StateFlow<String?> =
    settingsRepository.deviceName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

/** Device-wide, not tied to whether a race is selected — whether this phone is actually being
 *  polled over BT has nothing to do with race state. Shared by TimeModeViewModel/BibsModeViewModel. */
fun ViewModel.btPollingStatusFlow(bluetoothStateRepository: BluetoothStateRepository): StateFlow<BtPollingStatus> =
    combine(
        bluetoothStateRepository.advertisingWarning,
        bluetoothStateRepository.lastPolledAtMillis,
        ::BtPollingStatus,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BtPollingStatus())

/** Every race name previously saved from Setup Race/Relocate, most-recent-first — lets a form's
 *  name field offer a history dropdown. Shared by SetupRaceViewModel/RaceDetailsViewModel. */
fun ViewModel.raceNameHistoryFlow(settingsRepository: SettingsRepository): StateFlow<List<String>> =
    settingsRepository.raceNameHistory.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

/** Every location previously saved from Setup Race/Relocate, most-recent-first — lets a form's
 *  location field offer a history dropdown. Shared by SetupRaceViewModel/RaceDetailsViewModel. */
fun ViewModel.locationHistoryFlow(settingsRepository: SettingsRepository): StateFlow<List<String>> =
    settingsRepository.locationHistory.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
