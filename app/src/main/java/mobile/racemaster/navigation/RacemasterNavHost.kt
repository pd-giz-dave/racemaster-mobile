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
import mobile.racemaster.ui.cpmode.CpModeScreen
import mobile.racemaster.ui.editentry.EditEntryScreen
import mobile.racemaster.ui.help.HelpScreen
import mobile.racemaster.ui.modepicker.ModePickerScreen
import mobile.racemaster.ui.modepicker.NameDeviceScreen
import mobile.racemaster.ui.modepicker.SetupDeviceScreen
import mobile.racemaster.ui.mulemode.MuleModeScreen
import mobile.racemaster.ui.mulemode.MuleServerSetupScreen
import mobile.racemaster.ui.mulemode.SetupOptionsScreen
import mobile.racemaster.ui.racedetails.RaceDetailsScreen
import mobile.racemaster.ui.racehistory.MuleSourceDetailScreen
import mobile.racemaster.ui.racehistory.RaceHistoryDetailScreen
import mobile.racemaster.ui.racehistory.RaceHistoryScreen
import mobile.racemaster.ui.timemode.EditSplitScreen
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
                        // The one-shot opportunity is spent the first time this runs at all —
                        // not only when state.mode happened to be non-null that first time.
                        // On a genuinely fresh install (no mode ever selected yet), the very
                        // first composition sees state.mode == null; setting the flag only
                        // inside the navigate branch left it unconsumed, so the *next* time
                        // Mode Picker was reached (e.g. pressing "Mode" from Time/Bibs Mode
                        // after the operator had since picked one) this fired for the first
                        // time then instead, immediately forwarding right back into the mode
                        // just being left — needing a second press to actually land here
                        // (confirmed in the field, fresh-install only).
                        if (!hasAutoForwarded) {
                            hasAutoForwarded = true
                            state.mode?.let { navController.navigate(it.toRoute()) }
                        }
                    }
                    ModePickerScreen(
                        onModeSelected = { mode ->
                            navController.navigate(mode.toRoute()) {
                                popUpTo(Routes.MODE_PICKER) { inclusive = false }
                            }
                        },
                        onNewRaceNeeded = { mode -> navController.navigate(Routes.raceDetails(mode, raceId = null)) },
                        onMuleModeSelected = { navController.navigate(Routes.MULE_MODE) },
                        onMuleSetupNeeded = { navController.navigate(Routes.SETUP_OPTIONS) },
                        onReviewPastRaces = { navController.navigate(Routes.RACE_HISTORY) },
                        onHelp = { navController.navigate(Routes.HELP) },
                        onSetupDevice = { navController.navigate(Routes.SETUP_DEVICE) },
                    )
                }
                composable(Routes.HELP) {
                    HelpScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.SETUP_DEVICE) {
                    SetupDeviceScreen(
                        onDone = { navController.popBackStack() },
                        onSetupName = { navController.navigate(Routes.NAME_DEVICE) },
                        onSetupServer = { navController.navigate(Routes.MULE_SERVER_SETUP) },
                        onOptions = { navController.navigate(Routes.SETUP_OPTIONS) },
                    )
                }
                composable(Routes.NAME_DEVICE) {
                    NameDeviceScreen(onDone = { navController.popBackStack() })
                }
                composable(
                    route = Routes.SETUP_OPTIONS_PATTERN,
                    arguments = listOf(navArgument("fromMule") { type = NavType.BoolType; defaultValue = false }),
                ) { backStackEntry ->
                    val fromMule = backStackEntry.arguments?.getBoolean("fromMule") ?: false
                    SetupOptionsScreen(
                        onDone = { navController.popBackStack() },
                        // See SetupOptionsScreen's own onMuleModeEnabled doc — pops Options off
                        // the back stack (same as Cancel/onDone would) on the way to the
                        // dashboard, rather than leaving it sitting underneath, so Back from
                        // there lands on the Mode Picker like every other mode's own screen does.
                        onMuleModeEnabled = {
                            navController.navigate(Routes.MULE_MODE) {
                                popUpTo(Routes.MODE_PICKER) { inclusive = false }
                            }
                        },
                        // Only wired when fromMule (see Routes.setupOptions/SetupOptionsScreen's
                        // own onMuleModeDisabled doc) — pops both this screen and the now-idle
                        // Mule Mode dashboard underneath it in one go, landing back on the
                        // already-existing Mode Picker instance rather than pushing a fresh one.
                        onMuleModeDisabled = {
                            if (fromMule) navController.popBackStack(Routes.MODE_PICKER, false)
                        },
                    )
                }
                composable(Routes.TIME_MODE) {
                    TimeModeScreen(
                        onChangeMode = { navController.navigateToModePicker() },
                        onNewRace = { navController.navigate(Routes.raceDetails(AppMode.TIME, raceId = null)) },
                        onEditRace = { raceId -> navController.navigate(Routes.raceDetails(AppMode.TIME, raceId)) },
                        onEditSplit = { splitId -> navController.navigate(Routes.editSplit(splitId)) },
                    )
                }
                composable(Routes.BIBS_MODE) {
                    BibsModeScreen(
                        onChangeMode = { navController.navigateToModePicker() },
                        onNewRace = { navController.navigate(Routes.raceDetails(AppMode.BIBS, raceId = null)) },
                        onEditRace = { raceId -> navController.navigate(Routes.raceDetails(AppMode.BIBS, raceId)) },
                        onEditEntry = { entryId -> navController.navigate(Routes.editEntry(AppMode.BIBS, entryId)) },
                    )
                }
                composable(Routes.CP_MODE) {
                    CpModeScreen(
                        onChangeMode = { navController.navigateToModePicker() },
                        onNewRace = { navController.navigate(Routes.raceDetails(AppMode.CP, raceId = null)) },
                        onEditRace = { raceId -> navController.navigate(Routes.raceDetails(AppMode.CP, raceId)) },
                        onEditEntry = { entryId -> navController.navigate(Routes.editEntry(AppMode.CP, entryId)) },
                    )
                }
                composable(
                    route = Routes.EDIT_SPLIT,
                    arguments = listOf(navArgument("splitId") { type = NavType.LongType }),
                ) { backStackEntry ->
                    val splitId = backStackEntry.arguments?.getLong("splitId") ?: return@composable
                    EditSplitScreen(
                        splitId = splitId,
                        onSaved = { navController.popBackStack() },
                        onCancel = { navController.popBackStack() },
                    )
                }
                composable(
                    route = Routes.EDIT_ENTRY,
                    arguments = listOf(
                        navArgument("mode") { type = NavType.StringType },
                        navArgument("entryId") { type = NavType.LongType },
                    ),
                ) { backStackEntry ->
                    val mode = AppMode.valueOf(backStackEntry.arguments?.getString("mode") ?: return@composable)
                    val entryId = backStackEntry.arguments?.getLong("entryId") ?: return@composable
                    EditEntryScreen(
                        mode = mode,
                        entryId = entryId,
                        onSaved = { navController.popBackStack() },
                        onCancel = { navController.popBackStack() },
                    )
                }
                composable(Routes.MULE_MODE) {
                    MuleModeScreen(
                        onChangeMode = { navController.navigateToModePicker() },
                        // fromMule=true — see Routes.setupOptions/SetupOptionsScreen's own
                        // onMuleModeDisabled doc for what this enables once there.
                        onOptions = { navController.navigate(Routes.setupOptions(fromMule = true)) },
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
                        onMuleSourceSelected = { raceLabel, sourceDeviceId ->
                            navController.navigate(Routes.muleSourceDetail(raceLabel, sourceDeviceId))
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
                        navArgument("raceLabel") { type = NavType.StringType },
                        navArgument("sourceDeviceId") { type = NavType.StringType },
                    ),
                ) { backStackEntry ->
                    val encodedRaceLabel = backStackEntry.arguments?.getString("raceLabel") ?: return@composable
                    val encodedSourceDeviceId = backStackEntry.arguments?.getString("sourceDeviceId") ?: return@composable
                    MuleSourceDetailScreen(
                        raceLabel = URLDecoder.decode(encodedRaceLabel, "UTF-8"),
                        sourceDeviceId = URLDecoder.decode(encodedSourceDeviceId, "UTF-8"),
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

// Mule syncing is no longer part of AppMode (see SettingsRepository.muleSyncEnabled's own doc),
// so it has no route here — this only ever routes a *recording* mode, and Routes.MULE_MODE is
// reached directly via ModePickerScreen's own onMuleModeSelected instead, independent of this.
private fun AppMode?.toRoute(): String = when (this) {
    AppMode.TIME -> Routes.TIME_MODE
    AppMode.BIBS -> Routes.BIBS_MODE
    AppMode.CP -> Routes.CP_MODE
    null -> Routes.MODE_PICKER
}

private fun NavHostController.navigateToModePicker() {
    navigate(Routes.MODE_PICKER) {
        popUpTo(0) { inclusive = true }
    }
}