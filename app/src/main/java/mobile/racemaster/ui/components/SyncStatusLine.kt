package mobile.racemaster.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import java.text.SimpleDateFormat
import java.util.Date

/** Shared "N unsynced · last synced HH:MM" caption shown in Time, Bibs, and Mule mode —
 *  sourced from [mobile.racemaster.data.mule.MuleRepository] in each mode's ViewModel. */
@Composable
fun SyncStatusLine(unsyncedCount: Int, lastSyncedAtMillis: Long?, modifier: Modifier = Modifier) {
    // Read via LocalConfiguration rather than Locale.getDefault() directly — the latter isn't
    // observable by Compose, so this composable wouldn't recompose if the user changes their
    // system locale mid-session.
    val locale = LocalConfiguration.current.locales[0]
    val lastSyncedText = if (lastSyncedAtMillis == null) {
        "never"
    } else {
        SimpleDateFormat("HH:mm", locale).format(Date(lastSyncedAtMillis))
    }
    Text(
        "$unsyncedCount unsynced · last synced $lastSyncedText",
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier,
    )
}
