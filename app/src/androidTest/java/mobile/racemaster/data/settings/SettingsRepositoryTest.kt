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
}