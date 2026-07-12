package mobile.racemaster.ui.mulemode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    // Pre-fill exactly once from whatever's already saved — later emissions must not stomp
    // on what the operator is already typing. draft starts as null before the underlying
    // DataStore read completes (distinct from a *loaded* draft with genuinely blank fields,
    // e.g. a fresh install) — returning early on that lets the real values land once they
    // arrive, rather than permanently locking in blank fields the instant this composes.
    var prefilled by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(draft, currentServerUrl) {
        if (prefilled) return@LaunchedEffect
        val loadedDraft = draft ?: return@LaunchedEffect
        // Falls back to the confirmed session's URL only when there's no draft URL yet — an
        // install that logged in before this sticky-form feature existed has a real
        // currentServerUrl but no draft, and shouldn't show a blank URL field.
        url = loadedDraft.url.ifBlank { currentServerUrl.orEmpty() }
        username = loadedDraft.username
        password = loadedDraft.password
        prefilled = true
    }

    val canSave = !isSaving && url.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup Server") },
                navigationIcon = { TextButton(onClick = withClickSound(onDone)) { Text("Cancel") } },
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
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                label = { Text("Racemaster server URL") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                singleLine = true,
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                label = { Text("Password") },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = withClickSound { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            errorMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = withClickSound {
                    isSaving = true
                    errorMessage = null
                    scope.launch {
                        val result = runCatching { viewModel.save(url, username, password) }
                        isSaving = false
                        result.fold(
                            onSuccess = { onDone() },
                            onFailure = { e -> errorMessage = "Login failed: ${e.message}" },
                        )
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save & Log In") }
        }
    }
}
