package mobile.racemaster.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun dataStoreOverFile(file: File, scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun appModeAndActiveRaceIdRoundTripAcrossSimulatedRestart() = runTest {
        val file = tempFolder.newFile("test.preferences_pb")

        val firstScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val firstInstance = SettingsRepository(dataStoreOverFile(file, firstScope))
        assertNull(firstInstance.appMode.first())
        assertNull(firstInstance.activeRaceId.first())

        firstInstance.setAppMode(AppMode.BIBS)
        firstInstance.setActiveRaceId(42L)

        // Simulate a process restart: release the first DataStore's file lock before
        // reopening a new one over the same file (DataStore enforces one instance per file).
        firstScope.cancel()

        val secondScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val secondInstance = SettingsRepository(dataStoreOverFile(file, secondScope))
        assertEquals(AppMode.BIBS, secondInstance.appMode.first())
        assertEquals(42L, secondInstance.activeRaceId.first())
        secondScope.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun getOrCreateDeviceNameGeneratesOnceThenPersists() = runTest {
        val file = tempFolder.newFile("test.preferences_pb")
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val repository = SettingsRepository(dataStoreOverFile(file, scope))

        assertNull(repository.deviceName.first())
        val generated = repository.getOrCreateDeviceName()
        assertEquals(generated, repository.deviceName.first())
        // A second call must not regenerate a different name.
        assertEquals(generated, repository.getOrCreateDeviceName())
        scope.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun setDeviceNameOverridesTheGeneratedOne() = runTest {
        val file = tempFolder.newFile("test.preferences_pb")
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val repository = SettingsRepository(dataStoreOverFile(file, scope))

        repository.getOrCreateDeviceName()
        repository.setDeviceName("custom-name")
        assertEquals("custom-name", repository.deviceName.first())
        // A rename must stick, not be overwritten by another get-or-create call.
        assertEquals("custom-name", repository.getOrCreateDeviceName())
        scope.cancel()
    }

    // raceNameHistory — see RaceDetailsScreen's Race name field.

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun raceNameHistoryIsMostRecentFirstWithoutDuplicates() = runTest {
        val file = tempFolder.newFile("test.preferences_pb")
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val repository = SettingsRepository(dataStoreOverFile(file, scope))

        repository.addRaceNameToHistory("Alpha")
        repository.addRaceNameToHistory("Beta")
        assertEquals(listOf("Beta", "Alpha"), repository.raceNameHistory.first())

        // Re-adding an existing name moves it back to the front rather than leaving a
        // duplicate entry behind.
        repository.addRaceNameToHistory("Alpha")
        assertEquals(listOf("Alpha", "Beta"), repository.raceNameHistory.first())
        scope.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun blankRaceNameIsNeverAddedToHistory() = runTest {
        val file = tempFolder.newFile("test.preferences_pb")
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val repository = SettingsRepository(dataStoreOverFile(file, scope))

        repository.addRaceNameToHistory("   ")
        assertEquals(emptyList<String>(), repository.raceNameHistory.first())
        scope.cancel()
    }

    // serverCredentialHistory — see MuleServerSetupScreen's url/username/password fields.

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun serverCredentialHistoryIsMostRecentFirstKeyedByUrlAndUsername() = runTest {
        val file = tempFolder.newFile("test.preferences_pb")
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val repository = SettingsRepository(dataStoreOverFile(file, scope))

        repository.saveServerSetupDraft("https://a.example", "user-a", "pass-1")
        repository.saveServerSetupDraft("https://b.example", "user-b", "pass-2")
        assertEquals(
            listOf(
                ServerSetupDraft("https://b.example", "user-b", "pass-2"),
                ServerSetupDraft("https://a.example", "user-a", "pass-1"),
            ),
            repository.serverCredentialHistory.first(),
        )

        // Re-submitting the same url+username combo (e.g. a corrected password) replaces the
        // old entry and moves it back to the front, rather than leaving a stale duplicate.
        repository.saveServerSetupDraft("https://a.example", "user-a", "pass-3")
        assertEquals(
            listOf(
                ServerSetupDraft("https://a.example", "user-a", "pass-3"),
                ServerSetupDraft("https://b.example", "user-b", "pass-2"),
            ),
            repository.serverCredentialHistory.first(),
        )
        scope.cancel()
    }
}