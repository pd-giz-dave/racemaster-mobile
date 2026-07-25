package mobile.racemaster.ui.mulemode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import mobile.racemaster.BuildConfig
import mobile.racemaster.data.settings.ServerSetupDraft
import mobile.racemaster.ui.components.HistoryTextField
import mobile.racemaster.ui.theme.ServerOfflineRed
import mobile.racemaster.ui.theme.SyncedGreen
import mobile.racemaster.util.withClickSound

/** Device-wide Racemaster server URL + login — reached via "Setup Server" in Mule Mode's
 *  title bar, not per-race. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuleServerSetupScreen(
    onDone: () -> Unit,
    viewModel: MuleServerSetupViewModel = viewModel(factory = MuleServerSetupViewModel.Factory),
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val currentServerUrl by viewModel.currentServerUrl.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val credentialHistory by viewModel.credentialHistory.collectAsStateWithLifecycle()
    val maxAgeDaysSetting by viewModel.maxAgeDays.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // See RaceDetailsScreen's own doc for why this is needed: the keyboard has no physical
    // Tab key, so without an explicit ImeAction.Next + KeyboardActions.onNext there's no way
    // to advance through this form's fields at all.
    val focusManager = LocalFocusManager.current
    val nextFieldAction = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })

    // credentialHistory is already most-recent-first (see SettingsRepository's own doc), so
    // distinctBy keeps each url/username's most recently submitted full combination — that's
    // what picking one auto-fills the related field(s) from below.
    val urlHistory by remember { derivedStateOf { credentialHistory.filter { it.url.isNotBlank() }.distinctBy { it.url } } }
    val usernameHistory by remember { derivedStateOf { credentialHistory.filter { it.username.isNotBlank() }.distinctBy { it.username } } }
    val passwordHistory by remember {
        derivedStateOf { credentialHistory.map { it.password }.filter { it.isNotBlank() }.distinct() }
    }

    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var maxAgeDaysText by remember { mutableStateOf("") }
    // Pre-fill exactly once from whatever's already saved — later emissions must not stomp
    // on what the operator is already typing. draft starts as null before the underlying
    // DataStore read completes (distinct from a *loaded* draft with genuinely blank fields,
    // e.g. a fresh install) — returning early on that lets the real values land once they
    // arrive, rather than permanently locking in blank fields the instant this composes.
    var prefilled by remember { mutableStateOf(false) }
    // The raw persisted draft exactly as it stood the moment this screen opened, before any
    // BuildConfig dev-default fallback and before anything typed/saved this visit — "last known
    // good" for Cancel to restore to (see its own onClick below). Deliberately the raw loaded
    // draft, not the possibly-fallback-filled url/username/password state below: an empty draft
    // (nothing ever saved) must revert to genuinely empty, not to the dev-default auto-fill.
    var lastKnownGoodDraft by remember { mutableStateOf<ServerSetupDraft?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // null while the one-shot check is still resolving (renders nothing that one frame,
    // rather than flashing a wrong answer first) — see
    // MuleServerSetupViewModel.hasInternetConnectivity's own doc for why this is a one-shot
    // check, not a live subscription.
    var hasInternet by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { hasInternet = viewModel.hasInternetConnectivity() }

    LaunchedEffect(draft, currentServerUrl, maxAgeDaysSetting) {
        if (prefilled) return@LaunchedEffect
        val loadedDraft = draft ?: return@LaunchedEffect
        // Falls back to the confirmed session's URL only when there's no draft URL yet — an
        // install that logged in before this sticky-form feature existed has a real
        // currentServerUrl but no draft, and shouldn't show a blank URL field.
        // BuildConfig.DEV_SERVER_* is the last resort, only reachable when there's neither —
        // i.e. a genuinely fresh debug install — so a real saved login (of any kind, local or
        // production) is never overwritten by the local dev default. Empty in release builds,
        // so this is a no-op there (ifBlank falls through to the existing blank string).
        url = loadedDraft.url.ifBlank { currentServerUrl.orEmpty().ifBlank { BuildConfig.DEV_SERVER_URL } }
        username = loadedDraft.username.ifBlank { BuildConfig.DEV_SERVER_USERNAME }
        password = loadedDraft.password.ifBlank { BuildConfig.DEV_SERVER_PASSWORD }
        maxAgeDaysText = maxAgeDaysSetting.toString()
        lastKnownGoodDraft = loadedDraft
        prefilled = true
    }

    val maxAgeDaysValue = maxAgeDaysText.toIntOrNull()
    val canSave = !isSaving && url.isNotBlank() && username.isNotBlank() && password.isNotBlank() &&
        maxAgeDaysValue != null && maxAgeDaysValue >= 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup Server") },
                navigationIcon = {
                    TextButton(
                        onClick = withClickSound {
                            // A failed "Save & Log In" still updates the sticky draft (see
                            // MuleServerSetupViewModel.save's own doc — deliberate, for a quick
                            // in-place retry) while leaving the actually-active session
                            // untouched. Cancelling out instead of retrying must not leave that
                            // bad/half-edited draft behind masking the credentials sync is still
                            // really using — revert it back to what it held on entry first.
                            val snapshot = lastKnownGoodDraft
                            if (snapshot == null) {
                                onDone()
                            } else {
                                scope.launch {
                                    viewModel.revertDraft(snapshot)
                                    onDone()
                                }
                            }
                        },
                    ) { Text("Cancel") }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        // MainActivity's outer Scaffold already reserves the nav bar's bottom inset for
        // every screen — without this, this inner Scaffold reserves it a second time.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (isLoggedIn) "Currently logged in to: ${currentServerUrl.orEmpty()}" else "Not logged in",
                style = MaterialTheme.typography.bodyMedium,
            )
            hasInternet?.let { available ->
                Text(
                    if (available) "Internet connection available" else "No internet connection detected — logging in will likely fail until you have signal or WiFi",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (available) SyncedGreen else ServerOfflineRed,
                )
            }
            HistoryTextField(
                value = url,
                onValueChange = { url = it },
                onPick = { picked ->
                    url = picked
                    // Also fills in that URL's own last-used username/password — the operator
                    // picked "which server", so the credentials that go with it come along too.
                    urlHistory.firstOrNull { it.url == picked }?.let {
                        username = it.username
                        password = it.password
                    }
                },
                label = "Racemaster server URL",
                history = urlHistory.map { it.url },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = nextFieldAction,
                modifier = Modifier.fillMaxWidth(),
            )
            HistoryTextField(
                value = username,
                onValueChange = { username = it },
                onPick = { picked ->
                    username = picked
                    // Only the password comes along — the operator is naming "which login",
                    // not "which server", so the URL field is left exactly as it was.
                    usernameHistory.firstOrNull { it.username == picked }?.let { password = it.password }
                },
                label = "Username",
                history = usernameHistory.map { it.username },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = nextFieldAction,
                modifier = Modifier.fillMaxWidth(),
            )
            HistoryTextField(
                value = password,
                onValueChange = { password = it },
                label = "Password",
                history = passwordHistory,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                keyboardActions = nextFieldAction,
                extraTrailingIcon = {
                    IconButton(onClick = withClickSound { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = maxAgeDaysText,
                onValueChange = { maxAgeDaysText = it.filter(Char::isDigit).take(3) },
                singleLine = true,
                label = { Text("Server sync: skip races older than (days)") },
                supportingText = { Text("Races Mule hasn't touched in this many days are no longer checked against the server.") },
                // Last field — "Done" dismisses the keyboard.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
            errorMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = withClickSound {
                        isSaving = true
                        errorMessage = null
                        scope.launch {
                            val result = runCatching { viewModel.save(url, username, password, requireNotNull(maxAgeDaysValue)) }
                            isSaving = false
                            result.fold(
                                onSuccess = { onDone() },
                                onFailure = { e -> errorMessage = "Login failed: ${e.message}" },
                            )
                        }
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                ) { Text("Log-in") }
                // "Reset to no server": clears both the confirmed session and the sticky draft
                // (see MuleServerSetupViewModel.clearServer's own doc), then exits the form
                // exactly like a successful Log-in does — this is a legitimate, direct choice
                // now (mirroring the removed Mule Mode intro screen's own "Without server"
                // option), not a separate destructive action needing its own confirmation step.
                OutlinedButton(
                    onClick = withClickSound {
                        isSaving = true
                        scope.launch {
                            viewModel.clearServer()
                            isSaving = false
                            onDone()
                        }
                    },
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f),
                ) { Text("No Server") }
            }
        }
    }
}
