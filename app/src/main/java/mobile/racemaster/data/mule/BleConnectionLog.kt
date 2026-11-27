package mobile.racemaster.data.mule

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mobile.racemaster.data.settings.SettingsRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One snapshot of [BleConnectionLog]'s own file — just enough for Race History's list row
 *  (size, last touched) to stay reactive without re-stat()-ing the file on every recomposition. */
data class BleLogStatus(val sizeBytes: Long, val lastModifiedAtMillis: Long)

/** Toggleable (default off — [SettingsRepository.bluetoothLoggingEnabled]), file-backed trace of
 *  BLE connection-level events. TODO.md: "add a bluetooth logging option (similar to the
 *  web-app, default off) so connection issues can be viewed and resolved (so not relying on the
 *  logcat buffer that can overflow), the log needs to be visible as an item on the history files
 *  list with a delete option" — added specifically to help chase down real multi-phone collision
 *  delays (TODO.md's own "this collision issue needs to be resolved").
 *
 *  Mirrors the web app's own bleLog/bleWarn split (js/mule-ble.js): [log] is routine connect/
 *  pull/advertise trace, gated behind the toggle; [warn] is a genuine failure worth having on
 *  record regardless — it bypasses the toggle entirely, the same way the web app's bleError
 *  always prints. One instance, shared off [mobile.racemaster.di.AppContainer] by every BLE
 *  engine (MuleSyncEngine, MulePullClient, PeerSyncEngine, PeripheralSyncService) — a single
 *  file/toggle, not one per engine.
 *
 *  All writes run serialized on a single background thread ([Dispatchers.IO]'s own
 *  `limitedParallelism(1)`, not a Mutex) so [log]/[warn] stay plain fire-and-forget calls usable
 *  from anywhere — including a raw BLE callback (PeripheralSyncService's GATT/advertising
 *  callbacks aren't suspend functions) — without ever blocking the caller on file I/O, while
 *  still guaranteeing lines land in call order and never interleave. [MAX_FILE_SIZE_BYTES] bounds
 *  the file so a whole race day of logging can't fill the device's storage — checked on every
 *  write (cheap: these are connection-level events, nowhere near chunk-send frequency) and halved
 *  by dropping its own oldest lines whenever exceeded, rather than a numbered-rotation scheme
 *  (racemaster's own server/logging.js) that would need a second file this app's history list has
 *  nowhere to show. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleConnectionLog(private val context: Context, settingsRepository: SettingsRepository) {
    private val file: File = File(context.filesDir, FILE_NAME)
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var enabled = false

    private val _status = MutableStateFlow(currentStatus(file))
    val status: StateFlow<BleLogStatus?> = _status.asStateFlow()

    init {
        writeScope.launch { settingsRepository.bluetoothLoggingEnabled.collect { enabled = it } }
    }

    /** Routine connect/pull/advertise trace — a no-op (not even a background hop) unless the
     *  operator has turned logging on via Setup Options. */
    fun log(tag: String, message: String) {
        if (!enabled) return
        write(tag, message)
    }

    /** A genuine failure worth having on record regardless of the toggle — mirrors the web app's
     *  own bleError, which always prints. Reserved for things that actually went wrong (a connect
     *  timing out, a collision, advertising failing to start), not routine activity — this file
     *  never grows from calling it unless something already has. */
    fun warn(tag: String, message: String) = write(tag, message)

    private fun write(tag: String, message: String) {
        writeScope.launch {
            runCatching {
                file.appendText("${timestampFormat.format(Date())}  [$tag] $message\n")
                if (file.length() > MAX_FILE_SIZE_BYTES) trimOldestHalf(file)
                _status.value = currentStatus(file)
            }
        }
    }

    fun delete() {
        file.delete()
        _status.value = null
    }

    /** Null if nothing's been recorded yet (nothing to share) — same shape as
     *  RangeSampleLogger.shareIntent, reusing the same FileProvider authority/paths. */
    fun shareIntent(): Intent? {
        if (!file.exists()) return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, "Share Bluetooth connection log")
    }

    companion object {
        const val FILE_NAME = "ble-connection-log.txt"
        const val MAX_FILE_SIZE_BYTES = 2L * 1024 * 1024
    }
}

private fun currentStatus(file: File): BleLogStatus? =
    if (file.exists()) BleLogStatus(file.length(), file.lastModified()) else null

// Pulled apart from the instance method for the same JUnit-testability-without-Context reason
// RangeSampleLogger's own file functions are free functions. Drops the file's own oldest half
// (by line count, not byte count — keeps whole lines intact) once it's grown past
// BleConnectionLog.MAX_FILE_SIZE_BYTES.
internal fun trimOldestHalf(file: File) {
    val lines = file.readLines()
    val kept = lines.drop(lines.size / 2)
    file.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
}
