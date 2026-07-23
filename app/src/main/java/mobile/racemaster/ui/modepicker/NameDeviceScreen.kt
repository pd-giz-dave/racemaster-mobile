package mobile.racemaster.ui.modepicker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mobile.racemaster.util.withClickSound

/** Lets the operator see and rename this phone's memorable identity — the name every split
 *  and bib entry it records gets tagged with. Auto-generated the first time this screen (or
 *  anything else that needs it) touches [NameDeviceViewModel.deviceName]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameDeviceScreen(
    onDone: () -> Unit,
    viewModel: NameDeviceViewModel = viewModel(factory = NameDeviceViewModel.Factory),
) {
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val hasActiveRace by viewModel.hasActiveRace.collectAsStateWithLifecycle()

    var nameText by remember { mutableStateOf("") }
    // Pre-fill exactly once from the loaded/generated name — later emissions (e.g. the
    // generation side effect landing after first composition) must not stomp on what the
    // operator is already typing.
    var prefilled by remember { mutableStateOf(false) }

    LaunchedEffect(deviceName) {
        val loaded = deviceName ?: return@LaunchedEffect
        if (prefilled) return@LaunchedEffect
        nameText = loaded
        prefilled = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Name Device") },
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
                "This name identifies this phone and tags everything it records — pick " +
                    "something short and easy to say out loud.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (hasActiveRace) {
                Text(
                    "Device name can't be changed while a race is active — stop and reset it first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val editingEnabled = prefilled && !hasActiveRace
            OutlinedTextField(
                value = nameText,
                onValueChange = { nameText = it },
                singleLine = true,
                enabled = editingEnabled,
                label = { Text("Device name") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = withClickSound { nameText = viewModel.generateAnother() },
                    enabled = editingEnabled,
                    modifier = Modifier.weight(1f),
                ) { Text("Suggest Name") }
                Button(
                    onClick = withClickSound {
                        viewModel.save(nameText)
                        onDone()
                    },
                    enabled = editingEnabled && nameText.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
        }
    }
}
