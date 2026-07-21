package mobile.racemaster.ui.mulemode

import android.app.Application
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
import mobile.racemaster.di.applicationContext
import mobile.racemaster.util.hasInternetConnectivity

/** Device-wide Racemaster server URL + login, set together in one form — reached via a
 *  "Setup Server" link in Mule Mode's title bar. */
class MuleServerSetupViewModel(
    private val muleRepository: MuleRepository,
    private val settingsRepository: SettingsRepository,
    // Application, not Context — see ViewModelFactorySupport.applicationContext's own doc for
    // why that's what keeps Lint's StaticFieldLeak check from flagging a ViewModel field here.
    private val context: Application,
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

    // Every url/username/password combination previously submitted here, most-recent-first —
    // see MuleServerSetupScreen, which derives each field's own history dropdown (and the
    // URL/username auto-fill behavior) from this one list.
    val credentialHistory: StateFlow<List<ServerSetupDraft>> = settingsRepository.serverCredentialHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun save(url: String, username: String, password: String) {
        // Saved before attempting login, not after — so a failed attempt (e.g. a password
        // typo) still leaves the form sticky for a quick retry rather than losing everything
        // typed.
        settingsRepository.saveServerSetupDraft(url, username, password)
        muleRepository.login(url, username, password)
    }

    // Checked once when the screen opens (see MuleServerSetupScreen) — a plain synchronous
    // hint, not a live subscription, same one-shot use NetworkStatus.hasInternetConnectivity's
    // own doc already describes for Mule Mode's with/without-server prompt. Surfacing it here
    // too means an operator about to attempt a login sees upfront why it's likely to fail,
    // rather than only finding out after tapping Save & Log In.
    fun hasInternetConnectivity(): Boolean = hasInternetConnectivity(context)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer()
                MuleServerSetupViewModel(container.muleRepository, container.settingsRepository, applicationContext())
            }
        }
    }
}
