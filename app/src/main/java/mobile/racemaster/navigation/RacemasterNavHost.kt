package mobile.racemaster.navigation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
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
import mobile.racemaster.ui.help.HelpScreen
import mobile.racemaster.ui.modepicker.ModePickerScreen
import mobile.racemaster.ui.mulemode.MuleModeScreen
import mobile.racemaster.ui.racehistory.MuleSourceDetailScreen
import mobile.racemaster.ui.racehistory.RaceHistoryDetailScreen
import mobile.racemaster.ui.racehistory.RaceHistoryScreen
import mobile.racemaster.ui.timemode.TimeModeScreen
import java.net.URLDecoder

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
            //
            // Deliberately not using startLockTask()/stopLockTask() (screen pinning) here even
            // though it would also block Home/Overview: confirmed on real hardware that both
            // ends of it cause serious disruption — starting it pops a system confirmation
            // dialog ("Start this app?") interrupting race start, and calling stopLockTask()
            // programmatically when a race stops causes the OS to kick the user back to the
            // home/lock screen on at least this Samsung device (screen pinning is designed as
            // a user-controlled mechanism to exit, not something the app toggles itself, and
            // exiting it is treated as a security-sensitive event by the OEM). This
            // BackHandler-only guard avoids both: it blocks the in-app back gesture during a
            // race with no OS-level side effects, and simply doesn't touch Home/Overview.
            val context = LocalContext.current
            BackHandler(enabled = raceInProgress) {
                Toast.makeText(context, "Can't exit while a race is in progress.", Toast.LENGTH_SHORT).show()
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
                        onHelp = { navController.navigate(Routes.HELP) },
                    )
                }
                composable(Routes.HELP) {
                    HelpScreen(onBack = { navController.popBackStack() })
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
                        onMuleSourceSelected = { deviceRole, raceLabel ->
                            navController.navigate(Routes.muleSourceDetail(deviceRole, raceLabel))
                        },
                    )
                }
                composable(
                    route = Routes.RACE_HISTORY_DETAIL,
                    arguments = listOf(navArgument("raceId") { type = NavType.LongType }),
                ) { backStackEntry ->
                    val raceId = backStackEntry.arguments?.getLong("raceId") ?: return@composable
                    RaceHistoryDetailScreen(raceId = raceId, onBack = { navController.popBackStack() })
                }
                composable(
                    route = Routes.MULE_SOURCE_DETAIL,
                    arguments = listOf(
                        navArgument("deviceRole") { type = NavType.StringType },
                        navArgument("raceLabel") { type = NavType.StringType },
                    ),
                ) { backStackEntry ->
                    val deviceRole = backStackEntry.arguments?.getString("deviceRole") ?: return@composable
                    val encodedRaceLabel = backStackEntry.arguments?.getString("raceLabel") ?: return@composable
                    MuleSourceDetailScreen(
                        deviceRole = deviceRole,
                        raceLabel = URLDecoder.decode(encodedRaceLabel, "UTF-8"),
                        onBack = { navController.popBackStack() },
                    )
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