package mobile.racemaster.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mobile.racemaster.util.generateDeviceName

@Serializable
data class ServerSetupDraft(val url: String, val username: String, val password: String)

// Capped so History picker menus stay a manageable length and DataStore doesn't accumulate an
// unbounded JSON blob over a device's lifetime — most-recent-first, oldest entries drop off.
private const val MAX_HISTORY_ENTRIES = 20

// Offered in the Course field's history dropdown even before the operator has ever typed one —
// common recurring categories, not tied to any particular race. See courseHistory's own doc.
private val DEFAULT_COURSES = listOf("Seniors", "Juniors", "Pairs")

// Offered in the Location field's history dropdown even before the operator has ever typed
// one — the common finish/start/checkpoint stations. See locationHistory's own doc.
private val DEFAULT_LOCATIONS = listOf("Finish", "Start") + (1..9).map { "CP$it" }

// See SettingsRepository.serverSyncMaxAgeDays's own doc.
const val DEFAULT_SERVER_SYNC_MAX_AGE_DAYS = 2

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
        val BLUETOOTH_OFF = booleanPreferencesKey("bluetooth_off")
        val SERVER_SYNC_OFF = booleanPreferencesKey("server_sync_off")
        val DRAFT_SERVER_URL = stringPreferencesKey("draft_server_url")
        val DRAFT_USERNAME = stringPreferencesKey("draft_username")
        val DRAFT_PASSWORD = stringPreferencesKey("draft_password")
        val RACE_NAME_HISTORY = stringPreferencesKey("race_name_history")
        val COURSE_HISTORY = stringPreferencesKey("course_history")
        val LOCATION_HISTORY = stringPreferencesKey("location_history")
        val SERVER_CREDENTIAL_HISTORY = stringPreferencesKey("server_credential_history")
        val SERVER_SYNC_MAX_AGE_DAYS = intPreferencesKey("server_sync_max_age_days")
    }

    private val json = Json { ignoreUnknownKeys = true }

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

    // Unlike clearStaleSessionState below (a startup-only, whole-DB-wipe backstop), this is
    // the in-session counterpart: called by RaceRepository.deleteRace when the race it just
    // deleted was the operator's own selected one, so Time/Bibs Mode's own activeRaceId-driven
    // state doesn't keep dangling on a now-nonexistent raceId for the rest of this process's
    // life. appMode is deliberately left alone here (unlike clearStaleSessionState, which
    // clears both) — the operator is still legitimately in whichever mode they were in, just
    // without a race selected anymore, exactly the state a race deleted before ever picking a
    // mode already renders correctly.
    suspend fun clearActiveRaceId() {
        dataStore.edit { prefs -> prefs.remove(Keys.ACTIVE_RACE_ID) }
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

    // The "reset to no server" counterpart to setServerSession above — for the Setup Server
    // form's own "Clear server" action (see MuleServerSetupViewModel.clearServer), which resets
    // both this confirmed session and the sticky draft back to nothing, distinct from just
    // cancelling out of an in-progress edit. Sync simply stops (MuleRepository.pushToServer
    // already requires a non-null baseUrl/token) rather than needing any explicit "log out"
    // server-side call — there's no server-side session to invalidate, only this device's own
    // locally-remembered one.
    suspend fun clearServerSession() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.SERVER_BASE_URL)
            prefs.remove(Keys.AUTH_TOKEN)
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
        writeServerSetupDraft(url, username, password)
        addServerCredentialToHistory(url, username, password)
    }

    // Restores the sticky draft to a specific earlier state (e.g. whatever it held before the
    // operator started editing this time) without logging a new attempt to serverCredentialHistory
    // — unlike saveServerSetupDraft above, a revert isn't itself something the operator typed
    // and submitted. Exists for Cancel after a failed "Save & Log In": that failure still
    // updates the draft (see saveServerSetupDraft's own doc — sticky-on-failure is deliberate,
    // for a quick in-place retry), but if the operator cancels out instead of retrying, that
    // half-edited/bad state must not be left behind masking the credentials actually still
    // active for sync — see MuleServerSetupScreen's own Cancel handling.
    suspend fun revertServerSetupDraft(url: String, username: String, password: String) {
        writeServerSetupDraft(url, username, password)
    }

    private suspend fun writeServerSetupDraft(url: String, username: String, password: String) {
        dataStore.edit { prefs ->
            prefs[Keys.DRAFT_SERVER_URL] = url
            prefs[Keys.DRAFT_USERNAME] = username
            prefs[Keys.DRAFT_PASSWORD] = password
        }
    }

    // Every url/username/password combination ever submitted on the Setup Server form (see
    // saveServerSetupDraft above, which records one of these on every attempt regardless of
    // whether the login itself succeeds — same as the sticky draft it's saved alongside),
    // most-recent-first — lets MuleServerSetupScreen offer each field's own history dropdown,
    // and derive "picking this URL/username also fills in its last-used
    // username+password/password" (see HistoryTextField) without a separate lookup table:
    // distinctBy(url) / distinctBy(username) over this same list already yields each one's
    // most recent full combination, since the list itself is already most-recent-first.
    val serverCredentialHistory: Flow<List<ServerSetupDraft>> = dataStore.data.map { prefs ->
        decodeList(prefs[Keys.SERVER_CREDENTIAL_HISTORY])
    }

    private suspend fun addServerCredentialToHistory(url: String, username: String, password: String) {
        val trimmedUrl = url.trim()
        val trimmedUsername = username.trim()
        if (trimmedUrl.isEmpty() && trimmedUsername.isEmpty() && password.isEmpty()) return
        dataStore.edit { prefs ->
            val current = decodeList<ServerSetupDraft>(prefs[Keys.SERVER_CREDENTIAL_HISTORY])
            val entry = ServerSetupDraft(trimmedUrl, trimmedUsername, password)
            // Re-submitting the same url+username combo (e.g. a corrected password after a
            // typo'd login) replaces its old entry and moves it back to the front, rather than
            // leaving a stale duplicate behind.
            val deduped = current.filterNot { it.url == trimmedUrl && it.username == trimmedUsername }
            prefs[Keys.SERVER_CREDENTIAL_HISTORY] = json.encodeToString((listOf(entry) + deduped).take(MAX_HISTORY_ENTRIES))
        }
    }

    // Every race name ever saved from the Race Details form (both a brand-new race and a
    // rename), most-recent-first — lets that form's Race name field offer a history dropdown.
    // Picking one only ever fills the name field itself (see RaceDetailsScreen/HistoryTextField)
    // — course/first bib/runner count are unrelated to a race's name and stay exactly as
    // already entered.
    val raceNameHistory: Flow<List<String>> = stringHistoryFlow(Keys.RACE_NAME_HISTORY)

    suspend fun addRaceNameToHistory(name: String) = addToStringHistory(Keys.RACE_NAME_HISTORY, name)

    // Every course ever saved from the Race Details form, most-recent-first, followed by
    // [DEFAULT_COURSES] (only once each — a default the operator has since typed for real
    // graduates to the top of the real history instead of being listed twice) — same
    // independent-field behavior as raceNameHistory above: picking one only ever fills the
    // course field itself.
    val courseHistory: Flow<List<String>> = stringHistoryFlow(Keys.COURSE_HISTORY, DEFAULT_COURSES)

    suspend fun addCourseToHistory(course: String) = addToStringHistory(Keys.COURSE_HISTORY, course)

    // Every location ever saved from the Race Details form, most-recent-first, followed by
    // [DEFAULT_LOCATIONS] (same once-each behavior as courseHistory above) — same
    // independent-field behavior as raceNameHistory/courseHistory above: picking one only
    // ever fills the location field itself.
    val locationHistory: Flow<List<String>> = stringHistoryFlow(Keys.LOCATION_HISTORY, DEFAULT_LOCATIONS)

    suspend fun addLocationToHistory(location: String) = addToStringHistory(Keys.LOCATION_HISTORY, location)

    private fun stringHistoryFlow(key: Preferences.Key<String>, defaults: List<String> = emptyList()): Flow<List<String>> =
        dataStore.data.map { prefs ->
            val stored = decodeList<String>(prefs[key])
            stored + defaults.filterNot { it in stored }
        }

    private suspend fun addToStringHistory(key: Preferences.Key<String>, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { prefs ->
            val current = decodeList<String>(prefs[key])
            prefs[key] = json.encodeToString((listOf(trimmed) + current.filterNot { it == trimmed }).take(MAX_HISTORY_ENTRIES))
        }
    }

    private inline fun <reified T> decodeList(raw: String?): List<T> =
        raw?.let { runCatching { json.decodeFromString<List<T>>(it) }.getOrNull() }.orEmpty()

    // Explicit operator on/off switch for the background auto-sync loop, independent of
    // whether it's otherwise "armed" (logged in).
    val autoSyncStopped: Flow<Boolean> = dataStore.data.map { prefs -> prefs[Keys.AUTO_SYNC_STOPPED] ?: false }

    suspend fun setAutoSyncStopped(stopped: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.AUTO_SYNC_STOPPED] = stopped }
    }

    // Independent of autoSyncStopped above (which only pauses this device's own pull/push
    // loop) and of serverSyncOff below — this takes the whole Mule *radio* presence down: no
    // scanning, no advertising, not answerable by any other Mule over BLE. For an operator who
    // wants this phone to genuinely stop participating in device-to-device sync (e.g. to rule
    // it out while troubleshooting a crowded BLE environment), independent of whether it's
    // still pushing to the server.
    val bluetoothOff: Flow<Boolean> = dataStore.data.map { prefs -> prefs[Keys.BLUETOOTH_OFF] ?: false }

    suspend fun setBluetoothOff(off: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.BLUETOOTH_OFF] = off }
    }

    // The server-sync counterpart to bluetoothOff above — stops pushing to (and checking
    // status from) the server, independent of whether BLE device-to-device sync keeps
    // running. Lets an operator pause server connectivity specifically (e.g. no signal, or
    // deliberately keeping data local for now) without also taking down discovery/relay to
    // other nearby Mules, and vice versa.
    val serverSyncOff: Flow<Boolean> = dataStore.data.map { prefs -> prefs[Keys.SERVER_SYNC_OFF] ?: false }

    suspend fun setServerSyncOff(off: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.SERVER_SYNC_OFF] = off }
    }

    // Bounds MuleRepository.pushToServer's own per-tick reconciliation (see its own doc) to
    // only races Mule has touched within this many days — without a cutoff, every sync
    // attempt re-checks the server's stored state for every race label this device has ever
    // held, forever, which is the deliberate trade that catches a server-side file
    // disappearing (see MuleSyncEngine.pushIfNeeded's own doc) but has no reason to keep
    // re-checking a race that wrapped up days ago. Exposed on the Setup Server form.
    val serverSyncMaxAgeDays: Flow<Int> =
        dataStore.data.map { prefs -> prefs[Keys.SERVER_SYNC_MAX_AGE_DAYS] ?: DEFAULT_SERVER_SYNC_MAX_AGE_DAYS }

    suspend fun setServerSyncMaxAgeDays(days: Int) {
        dataStore.edit { prefs -> prefs[Keys.SERVER_SYNC_MAX_AGE_DAYS] = days }
    }
}
