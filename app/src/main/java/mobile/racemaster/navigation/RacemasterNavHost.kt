package mobile.racemaster.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import mobile.racemaster.ui.modepicker.NameDeviceScreen
import mobile.racemaster.ui.mulemode.MuleModeScreen
import mobile.racemaster.ui.mulemode.MuleServerSetupScreen
import mobile.racemaster.ui.racedetails.RaceDetailsScreen
import mobile.racemaster.ui.racehistory.MuleSourceDetailScreen
import mobile.racemaster.ui.racehistory.RaceHistoryDetailScreen
import mobile.racemaster.ui.racehistory.RaceHistoryScreen
import mobile.racemaster.ui.timemode.TimeModeScreen
import java.net.URLDecoder

@Composable
fun RacemasterNavHost(modifier: Modifier = Modifier) {
    val appEntryViewModel: AppEntryViewModel = viewModel(factory = AppEntryViewModel.Factory)
    val startDestinationState by appEntryViewModel.startDestinationState.collectAsStateWithLifecycle()

    when (val state = startDestinationState) {
        is StartDestinationState.Loading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is StartDestinationState.Ready -> {
            val navController = rememberNavController()

            // No back-press guard, no screen pinning: both were tried and both caused real
            // disruption on actual hardware (a system confirmation dialog interrupting race
            // start, and the OS kicking the user to the home/lock screen when a race stops).
            // Exiting mid-race is now just relying on the operator's own discipline.
            //
            // The mode picker is always the actual navigation root (not whichever mode was
            // last active) so that back-press from any mode screen has somewhere to land —
            // if a mode is already active, a one-time LaunchedEffect immediately forwards
            // into it instead, leaving the picker underneath on the back stack.
            var hasAutoForwarded by rememberSaveable { mutableStateOf(false) }

            NavHost(navController = navController, startDestination = Routes.MODE_PICKER, modifier = modifier) {
                composable(Routes.MODE_PICKER) {
                    LaunchedEffect(Unit) {
                        if (!hasAutoForwarded && state.mode != null) {
                            hasAutoForwarded = true
                            navController.navigate(state.mode.toRoute())
                        }
                    }
                    ModePickerScreen(
                        onModeSelected = { mode ->
                            navController.navigate(mode.toRoute()) {
                                popUpTo(Routes.MODE_PICKER) { inclusive = false }
                            }
                        },
                        onNewRaceNeeded = { mode -> navController.navigate(Routes.raceDetails(mode, raceId = null)) },
                        onReviewPastRaces = { navController.navigate(Routes.RACE_HISTORY) },
                        onHelp = { navController.navigate(Routes.HELP) },
                        onNameDevice = { navController.navigate(Routes.NAME_DEVICE) },
                    )
                }
                composable(Routes.HELP) {
                    HelpScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.NAME_DEVICE) {
                    NameDeviceScreen(onDone = { navController.popBackStack() })
                }
                composable(Routes.TIME_MODE) {
                    TimeModeScreen(
                        onChangeMode = { navController.navigateToModePicker() },
                        onNewRace = { navController.navigate(Routes.raceDetails(AppMode.TIME, raceId = null)) },
                        onEditRace = { raceId -> navController.navigate(Routes.raceDetails(AppMode.TIME, raceId)) },
                    )
                }
                composable(Routes.BIBS_MODE) {
                    BibsModeScreen(
                        onChangeMode = { navController.navigateToModePicker() },
                        onNewRace = { navController.navigate(Routes.raceDetails(AppMode.BIBS, raceId = null)) },
                        onEditRace = { raceId -> navController.navigate(Routes.raceDetails(AppMode.BIBS, raceId)) },
                    )
                }
                composable(Routes.MULE_MODE) {
                    MuleModeScreen(
                        onChangeMode = { navController.navigateToModePicker() },
                        onSetupServer = { navController.navigate(Routes.MULE_SERVER_SETUP) },
                    )
                }
                composable(Routes.MULE_SERVER_SETUP) {
                    MuleServerSetupScreen(onDone = { navController.popBackStack() })
                }
                composable(
                    route = Routes.RACE_DETAILS,
                    arguments = listOf(
                        navArgument("mode") { type = NavType.StringType },
                        navArgument("raceId") { type = NavType.LongType },
                    ),
                ) { backStackEntry ->
                    val mode = AppMode.valueOf(backStackEntry.arguments?.getString("mode") ?: return@composable)
                    val raceIdArg = backStackEntry.arguments?.getLong("raceId") ?: -1L
                    RaceDetailsScreen(
                        mode = mode,
                        existingRaceId = raceIdArg.takeIf { it >= 0 },
                        onSaved = {
                            navController.navigate(mode.toRoute()) {
                                popUpTo(Routes.MODE_PICKER) { inclusive = false }
                            }
                        },
                        onCancel = { navController.popBackStack() },
                    )
                }
                composable(Routes.RACE_HISTORY) {
                    RaceHistoryScreen(
                        onBack = { navController.popBackStack() },
                        onRaceSelected = { raceId -> navController.navigate(Routes.raceHistoryDetail(raceId)) },
                        onMuleSourceSelected = { raceLabel ->
                            navController.navigate(Routes.muleSourceDetail(raceLabel))
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
                    arguments = listOf(navArgument("raceLabel") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val encodedRaceLabel = backStackEntry.arguments?.getString("raceLabel") ?: return@composable
                    MuleSourceDetailScreen(
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