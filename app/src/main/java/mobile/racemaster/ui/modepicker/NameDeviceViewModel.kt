package mobile.racemaster.ui.modepicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer
import mobile.racemaster.util.generateDeviceName

/** This device's name (see [SettingsRepository.getOrCreateDeviceName]) tags every split/bib
 *  entry it records — auto-generated once ("clever-cricket"), freely renamable here. */
class NameDeviceViewModel(private val settingsRepository: SettingsRepository) : ViewModel() {

    val deviceName: StateFlow<String?> = settingsRepository.deviceName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Ensures a name exists (generating one on first-ever launch) so the field always
        // has something to prefill, rather than showing blank until the operator types.
        viewModelScope.launch { settingsRepository.getOrCreateDeviceName() }
    }

    // Just a suggestion into the text field, not persisted until Save is tapped — matches
    // the rest of this form's edit-then-save pattern rather than committing a rename the
    // operator hasn't confirmed yet.
    fun generateAnother(): String = generateDeviceName()

    fun save(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { settingsRepository.setDeviceName(trimmed) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                NameDeviceViewModel(container.settingsRepository)
            }
        }
    }
}
