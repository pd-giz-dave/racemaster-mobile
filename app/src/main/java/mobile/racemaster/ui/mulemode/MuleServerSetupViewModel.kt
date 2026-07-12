package mobile.racemaster.ui.mulemode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import mobile.racemaster.data.mule.MuleRepository
import mobile.racemaster.data.settings.ServerSetupDraft
import mobile.racemaster.data.settings.SettingsRepository
import mobile.racemaster.di.appContainer

/** Device-wide Racemaster server URL + login, set together in one form — reached via a
 *  "Setup Server" link in Mule Mode's title bar. */
class MuleServerSetupViewModel(
    private val muleRepository: MuleRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    // What the operator last typed here, sticky across reopening the form (and even a
    // failed login attempt) — separate from currentServerUrl below, which only reflects a
    // *confirmed* session.
    val draft: StateFlow<ServerSetupDraft?> = settingsRepository.serverSetupDraft
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val currentServerUrl: StateFlow<String?> = settingsRepository.serverBaseUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isLoggedIn: StateFlow<Boolean> = muleRepository.isLoggedIn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    suspend fun save(url: String, username: String, password: String) {
        // Saved before attempting login, not after — so a failed attempt (e.g. a password
        // typo) still leaves the form sticky for a quick retry rather than losing everything
        // typed.
        settingsRepository.saveServerSetupDraft(url, username, password)
        muleRepository.login(url, username, password)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                MuleServerSetupViewModel(container.muleRepository, container.settingsRepository)
            }
        }
    }
}
