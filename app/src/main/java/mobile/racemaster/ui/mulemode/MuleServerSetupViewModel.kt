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
import mobile.racemaster.data.settings.DEFAULT_SERVER_SYNC_MAX_AGE_DAYS
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

    // See SettingsRepository.serverSyncMaxAgeDays's own doc.
    val maxAgeDays: StateFlow<Int> = settingsRepository.serverSyncMaxAgeDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_SERVER_SYNC_MAX_AGE_DAYS)

    // See SettingsRepository.revertServerSetupDraft's own doc — undoes the sticky-draft side
    // effect of a failed save() above when the operator cancels out instead of retrying.
    suspend fun revertDraft(lastKnownGood: ServerSetupDraft) {
        settingsRepository.revertServerSetupDraft(lastKnownGood.url, lastKnownGood.username, lastKnownGood.password)
    }

    suspend fun save(url: String, username: String, password: String, maxAgeDays: Int) {
        // Saved before attempting login, not after — so a failed attempt (e.g. a password
        // typo) still leaves the form sticky for a quick retry rather than losing everything
        // typed. maxAgeDays is a general Mule setting, unrelated to login success either way,
        // so it's persisted right alongside the draft rather than gated on the login call
        // below actually succeeding.
        settingsRepository.saveServerSetupDraft(url, username, password)
        settingsRepository.setServerSyncMaxAgeDays(maxAgeDays)
        muleRepository.login(url, username, password)
    }

    // "Reset to no server" — clears both the confirmed session (so sync genuinely stops, not
    // just the on-screen fields) and the sticky draft (so reopening this form afterward shows
    // blank fields, not the credentials just cleared). Deliberately distinct from Cancel, which
    // only ever reverts to the *last known good* state — this clears it outright, for an
    // operator who wants to genuinely stop syncing to any server rather than fix a mistake.
    // Doesn't touch serverCredentialHistory — those past logins stay available to pick again
    // later even after clearing the active one.
    suspend fun clearServer() {
        settingsRepository.revertServerSetupDraft("", "", "")
        muleRepository.clearServerSession()
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
