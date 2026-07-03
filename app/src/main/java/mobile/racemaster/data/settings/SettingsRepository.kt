package mobile.racemaster.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
}