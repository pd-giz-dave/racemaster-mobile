package mobile.racemaster.ui.mulemode

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import mobile.racemaster.util.withClickSound

// Placeholder for now. Future work (not built in this phase): Classic Bluetooth
// (RFCOMM/SPP) transfer that pulls recorded data from the Time and Bibs mode
// phones and hands it off to the HQ laptop. That will live in a future
// data/mule/ package (BluetoothMuleService, MuleRepository) plus a
// MuleModeViewModel reading from TimeModeRepository/BibsModeRepository/RaceRepository.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuleModeScreen(onChangeMode: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mule Mode") },
                actions = {
                    TextButton(onClick = withClickSound(onChangeMode)) { Text("Mode") }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text("Mule mode coming soon", style = MaterialTheme.typography.titleMedium)
        }
    }
}