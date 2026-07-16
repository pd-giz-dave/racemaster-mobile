package mobile.racemaster.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import mobile.racemaster.util.generateDeviceName

data class ServerSetupDraft(val url: String, val username: String, val password: String)

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val APP_MODE = stringPreferencesKey("app_mode")
        val ACTIVE_RACE_ID = longPreferencesKey("active_race_id")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val SERVER_BASE_URL = stringPreferencesKey("server_base_url")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val AUTO_SYNC_STOPPED = booleanPreferencesKey("auto_sync_stopped")
        val DRAFT_SERVER_URL = stringPreferencesKey("draft_server_url")
        val DRAFT_USERNAME = stringPreferencesKey("draft_username")
        val DRAFT_PASSWORD = stringPreferencesKey("draft_password")
    }

    val appMode: Flow<AppMode?> = dataStore.data.map { prefs ->
        prefs[Keys.APP_MODE]?.let { AppMode.valueOf(it) }
    }

    val activeRaceId: Flow<Long?> = dataStore.data.map { prefs -> prefs[Keys.ACTIVE_RACE_ID] }

    suspend fun setAppMode(mode: AppMode) {
        dataStore.edit { prefs -> prefs[Keys.APP_MODE] = mode.name }
    }

    suspend fun setActiveRaceId(id: Long) {
        dataStore.edit { prefs -> prefs[Keys.ACTIVE_RACE_ID] = id }
    }

    // A destructive Room migration (schema version bump with no explicit Migration, see
    // RacemasterDatabase) wipes every race — and every Mule-pulled record — but leaves all of
    // this DataStore-backed state untouched, since it's an entirely separate storage
    // mechanism. Without this, the app would silently resume into a mode screen referencing a
    // race that no longer exists (crashing the moment any action button hits it). Called once
    // at startup after validating the referenced race is actually gone (see
    // AppEntryViewModel). Login state (server URL, auth token) is deliberately left alone —
    // a local data wipe doesn't invalidate who you're logged in as.
    suspend fun clearStaleSessionState() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.ACTIVE_RACE_ID)
            prefs.remove(Keys.APP_MODE)
        }
    }

    // Identifies this phone (not any particular race) to Mule/sync — generated once on first
    // access and persisted, since Room's local autoincrement ids collide once records from
    // multiple phones are merged.
    suspend fun getOrCreateDeviceId(): String {
        var deviceId: String? = null
        dataStore.edit { prefs ->
            deviceId = prefs[Keys.DEVICE_ID] ?: java.util.UUID.randomUUID().toString().also {
                prefs[Keys.DEVICE_ID] = it
            }
        }
        return requireNotNull(deviceId)
    }

    // The human-facing counterpart to deviceId — memorable ("clever-cricket") rather than a
    // UUID, since this is what an operator reads out loud or picks out of a list of nearby
    // phones. Generated once on first access (same get-or-create pattern as deviceId above),
    // then freely renamable by the operator via setDeviceName.
    val deviceName: Flow<String?> = dataStore.data.map { prefs -> prefs[Keys.DEVICE_NAME] }

    suspend fun getOrCreateDeviceName(): String {
        var deviceName: String? = null
        dataStore.edit { prefs ->
            deviceName = prefs[Keys.DEVICE_NAME] ?: generateDeviceName().also {
                prefs[Keys.DEVICE_NAME] = it
            }
        }
        return requireNotNull(deviceName)
    }

    suspend fun setDeviceName(name: String) {
        dataStore.edit { prefs -> prefs[Keys.DEVICE_NAME] = name }
    }

    val serverBaseUrl: Flow<String?> = dataStore.data.map { prefs -> prefs[Keys.SERVER_BASE_URL] }
    val authToken: Flow<String?> = dataStore.data.map { prefs -> prefs[Keys.AUTH_TOKEN] }

    suspend fun setServerSession(baseUrl: String, token: String) {
        dataStore.edit { prefs ->
            prefs[Keys.SERVER_BASE_URL] = baseUrl
            prefs[Keys.AUTH_TOKEN] = token
        }
    }

    // What the operator last typed into the Setup Server form — kept separate from the
    // confirmed session above (serverBaseUrl/authToken, only updated on a *successful*
    // login) so the form stays sticky even after a failed attempt (e.g. a password typo),
    // letting the operator just fix the one field rather than retyping everything. Persisted
    // the same way the auth token already is; this app has no encrypted-storage story yet,
    // and a saved password here is no more sensitive than the long-lived bearer token it
    // sits next to.
    val serverSetupDraft: Flow<ServerSetupDraft> = dataStore.data.map { prefs ->
        ServerSetupDraft(
            url = prefs[Keys.DRAFT_SERVER_URL].orEmpty(),
            username = prefs[Keys.DRAFT_USERNAME].orEmpty(),
            password = prefs[Keys.DRAFT_PASSWORD].orEmpty(),
        )
    }

    suspend fun saveServerSetupDraft(url: String, username: String, password: String) {
        dataStore.edit { prefs ->
            prefs[Keys.DRAFT_SERVER_URL] = url
            prefs[Keys.DRAFT_USERNAME] = username
            prefs[Keys.DRAFT_PASSWORD] = password
        }
    }

    // Explicit operator on/off switch for the background auto-sync loop, independent of
    // whether it's otherwise "armed" (logged in).
    val autoSyncStopped: Flow<Boolean> = dataStore.data.map { prefs -> prefs[Keys.AUTO_SYNC_STOPPED] ?: false }

    suspend fun setAutoSyncStopped(stopped: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.AUTO_SYNC_STOPPED] = stopped }
    }
}
