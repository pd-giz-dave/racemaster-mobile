package mobile.racemaster.navigation

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import mobile.racemaster.data.settings.AppMode
import mobile.racemaster.ui.bibsmode.BibsModeScreen
import mobile.racemaster.ui.modepicker.ModePickerScreen
import mobile.racemaster.ui.mulemode.MuleModeScreen
import mobile.racemaster.ui.racehistory.RaceHistoryDetailScreen
import mobile.racemaster.ui.racehistory.RaceHistoryScreen
import mobile.racemaster.ui.timemode.TimeModeScreen

@Composable
fun RacemasterNavHost(modifier: Modifier = Modifier) {
    val appEntryViewModel: AppEntryViewModel = viewModel(factory = AppEntryViewModel.Factory)
    val startDestinationState by appEntryViewModel.startDestinationState.collectAsStateWithLifecycle()
    val raceInProgress by appEntryViewModel.raceInProgress.collectAsStateWithLifecycle()

    when (val state = startDestinationState) {
        is StartDestinationState.Loading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is StartDestinationState.Ready -> {
            val navController = rememberNavController()
            val startDestination = state.mode.toRoute()

            // Registered before the NavHost below, so the NavHost's own back handling (pop
            // its stack) still takes priority; this only fires once there's nothing left to
            // pop, i.e. exactly the case that would otherwise exit the app.
            val context = LocalContext.current
            BackHandler(enabled = raceInProgress) {
                Toast.makeText(context, "Can't exit while a race is in progress.", Toast.LENGTH_SHORT).show()
            }

            // Screen-pin the app while a race is running so Home/Overview can't back out of
            // it either (the user needs to have enabled screen pinning once in Android
            // Settings > Security for this to take effect; it's a no-op otherwise).
            val activity = context as? Activity
            LaunchedEffect(raceInProgress) {
                if (activity == null) return@LaunchedEffect
                // Whether this succeeds, no-ops, or throws depends on device/OS version and
                // whether the user has ever enabled screen pinning — none of that is
                // something we can detect ahead of time, so best-effort try/catch it.
                try {
                    if (raceInProgress) activity.startLockTask() else activity.stopLockTask()
                } catch (e: Exception) {
                    Log.w("RacemasterNavHost", "Lock task mode unavailable", e)
                }
            }

            NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
                composable(Routes.MODE_PICKER) {
                    ModePickerScreen(
                        onModeSelected = { mode ->
                            navController.navigate(mode.toRoute()) {
                                popUpTo(Routes.MODE_PICKER) { inclusive = true }
                            }
                        },
                        onReviewPastRaces = { navController.navigate(Routes.RACE_HISTORY) },
                    )
                }
                composable(Routes.TIME_MODE) {
                    TimeModeScreen(onChangeMode = { navController.navigateToModePicker() })
                }
                composable(Routes.BIBS_MODE) {
                    BibsModeScreen(onChangeMode = { navController.navigateToModePicker() })
                }
                composable(Routes.MULE_MODE) {
                    MuleModeScreen(onChangeMode = { navController.navigateToModePicker() })
                }
                composable(Routes.RACE_HISTORY) {
                    RaceHistoryScreen(
                        onBack = { navController.popBackStack() },
                        onRaceSelected = { raceId -> navController.navigate(Routes.raceHistoryDetail(raceId)) },
                    )
                }
                composable(
                    route = Routes.RACE_HISTORY_DETAIL,
                    arguments = listOf(navArgument("raceId") { type = NavType.LongType }),
                ) { backStackEntry ->
                    val raceId = backStackEntry.arguments?.getLong("raceId") ?: return@composable
                    RaceHistoryDetailScreen(raceId = raceId, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

private fun AppMode?.toRoute(): String = when (this) {
    AppMode.TIME -> Routes.TIME_MODE
    AppMode.BIBS -> Routes.BIBS_MODE
    AppMode.MULE -> Routes.MULE_MODE
    null -> Routes.MODE_PICKER
}

private fun NavHostController.navigateToModePicker() {
    navigate(Routes.MODE_PICKER) {
        popUpTo(0) { inclusive = true }
    }
}