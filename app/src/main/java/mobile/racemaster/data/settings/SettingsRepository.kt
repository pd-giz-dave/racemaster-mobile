package mobile.racemaster.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val APP_MODE = stringPreferencesKey("app_mode")
        val ACTIVE_RACE_ID = longPreferencesKey("active_race_id")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val SERVER_BASE_URL = stringPreferencesKey("server_base_url")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val SELECTED_DATASET_OWNER = stringPreferencesKey("selected_dataset_owner")
        val SELECTED_DATASET_FULL_NAME = stringPreferencesKey("selected_dataset_full_name")
        val LOCKED_BIBS_DEVICE_ID = stringPreferencesKey("locked_bibs_device_id")
        val LOCKED_TIME_DEVICE_ID = stringPreferencesKey("locked_time_device_id")
        val AUTO_SYNC_STOPPED = booleanPreferencesKey("auto_sync_stopped")
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
    // race that no longer exists (crashing the moment any action button hits it), or Mule
    // Mode would silently re-arm auto-sync against a locked device from before the wipe as if
    // nothing happened. Called once at startup after validating the referenced race is
    // actually gone (see AppEntryViewModel). Login/dataset selection (server URL, auth token,
    // selected dataset) are deliberately left alone — a local data wipe doesn't invalidate
    // who you're logged in as or which server-side dataset you'd push to.
    suspend fun clearStaleSessionState() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.ACTIVE_RACE_ID)
            prefs.remove(Keys.APP_MODE)
            prefs.remove(Keys.LOCKED_BIBS_DEVICE_ID)
            prefs.remove(Keys.LOCKED_TIME_DEVICE_ID)
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

    val serverBaseUrl: Flow<String?> = dataStore.data.map { prefs -> prefs[Keys.SERVER_BASE_URL] }
    val authToken: Flow<String?> = dataStore.data.map { prefs -> prefs[Keys.AUTH_TOKEN] }

    val selectedDataset: Flow<Pair<String, String>?> = dataStore.data.map { prefs ->
        val owner = prefs[Keys.SELECTED_DATASET_OWNER]
        val fullName = prefs[Keys.SELECTED_DATASET_FULL_NAME]
        if (owner != null && fullName != null) owner to fullName else null
    }

    suspend fun setServerSession(baseUrl: String, token: String) {
        dataStore.edit { prefs ->
            prefs[Keys.SERVER_BASE_URL] = baseUrl
            prefs[Keys.AUTH_TOKEN] = token
        }
    }

    // Picking a *different* dataset than the one currently selected stops auto-sync — a
    // stray tap shouldn't silently redirect an already-running auto-sync's data to a new
    // target; the operator has to consciously confirm via Force Sync Now (which also
    // resumes auto-sync) once they're sure.
    suspend fun setSelectedDataset(owner: String, fullName: String) {
        dataStore.edit { prefs ->
            val changed = prefs[Keys.SELECTED_DATASET_OWNER] != owner || prefs[Keys.SELECTED_DATASET_FULL_NAME] != fullName
            prefs[Keys.SELECTED_DATASET_OWNER] = owner
            prefs[Keys.SELECTED_DATASET_FULL_NAME] = fullName
            if (changed) prefs[Keys.AUTO_SYNC_STOPPED] = true
        }
    }

    // The Bibs/Time device Mule is currently "locked" onto for auto-pull — one of each role
    // at most, so Mule can attach to a Bibs phone and a Time phone at the same time (or just
    // one of either). Identified by that device's own persisted deviceId (stable across
    // reconnects/app restarts), not its BLE address (which can rotate). Set whenever the
    // operator manually pulls from a device of that role, replacing any previous lock for
    // that same role.
    val lockedBibsDeviceId: Flow<String?> = dataStore.data.map { prefs -> prefs[Keys.LOCKED_BIBS_DEVICE_ID] }
    val lockedTimeDeviceId: Flow<String?> = dataStore.data.map { prefs -> prefs[Keys.LOCKED_TIME_DEVICE_ID] }

    suspend fun setLockedBibsDeviceId(deviceId: String) {
        dataStore.edit { prefs -> prefs[Keys.LOCKED_BIBS_DEVICE_ID] = deviceId }
    }

    suspend fun setLockedTimeDeviceId(deviceId: String) {
        dataStore.edit { prefs -> prefs[Keys.LOCKED_TIME_DEVICE_ID] = deviceId }
    }

    // Explicit operator on/off switch for the background auto-sync loop, independent of
    // whether it's otherwise "armed" (logged in + dataset selected).
    val autoSyncStopped: Flow<Boolean> = dataStore.data.map { prefs -> prefs[Keys.AUTO_SYNC_STOPPED] ?: false }

    suspend fun setAutoSyncStopped(stopped: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.AUTO_SYNC_STOPPED] = stopped }
    }
}
