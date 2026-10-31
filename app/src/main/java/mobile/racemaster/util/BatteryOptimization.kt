package mobile.racemaster.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/** Whether this app is currently exempt from the OS's Doze/app-standby battery optimization —
 *  see MainActivity's own doc for why that matters alongside FLAG_KEEP_SCREEN_ON for keeping
 *  the screen on throughout a race. */
fun Context.isIgnoringBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

/** Shows the system's one-tap "exempt this app" prompt. Safe to call repeatedly — a no-op if
 *  already granted, and this is the only way an operator can retry after declining it once.
 *  See AndroidManifest.xml's own REQUEST_IGNORE_BATTERY_OPTIMIZATIONS comment for the Play
 *  Store Content Policy justification lint's BatteryLife check is suppressed for. */
@SuppressLint("BatteryLife")
fun Context.requestIgnoreBatteryOptimizations() {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri())
    runCatching { startActivity(intent) }
}
