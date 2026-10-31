package mobile.racemaster.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Bluetooth scan/connect/advertise permissions Mule Mode's whole BLE subsystem needs — both
 *  the peripheral/hosting role (PeripheralSyncService) and the central/pulling role
 *  (MuleSyncEngine's scan) refuse to start at all without every one of these, on whichever set
 *  applies to this OS version: the BLUETOOTH_* trio from API 31 on, or the legacy
 *  ACCESS_FINE_LOCATION BLE scanning needed before that permission group existed. */
fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** POST_NOTIFICATIONS, needed from API 33 on for PeripheralSyncService's ongoing sync
 *  notification to keep refreshing its text (its initial post is exempt on every level) — a
 *  soft dependency, unlike [bluetoothPermissions]: missing this doesn't stop syncing, the
 *  notification just goes stale. Empty (nothing to request/check) below API 33. */
fun notificationPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

fun Context.hasAllPermissions(permissions: Array<String>): Boolean =
    permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

/** True once the system will no longer show its own request dialog for any of [permissions] —
 *  confirmed live on the XCover7 (API 36): once an operator picks "Don't allow" on that dialog,
 *  Android flags the permission so a later request call just returns denied immediately, with
 *  no UI at all, leaving a plain Retry silently doing nothing forever. Safe to treat
 *  shouldShowRequestPermissionRationale()'s other false case (never asked yet) as unreachable
 *  here: MainActivity.requestBluetoothPermissionsIfNeeded already asks for every one of these
 *  once at first launch, so by the time any status row's Retry is tappable at all, the one
 *  legitimate "ask" has already happened. */
fun Context.isPermissionRequestDialogSuppressed(permissions: Array<String>): Boolean {
    val activity = this as? Activity ?: return false
    return permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED } &&
        permissions.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
}

/** Opens this app's own permission settings — there's no API for an app to revoke a runtime
 *  permission from itself (only to request one), so this is the nearest a "Revoke" action can
 *  get: Android has no reliable cross-OEM deep link to one specific permission toggle, only to
 *  this screen, where the operator flips Bluetooth/Notifications off themselves in a couple of
 *  taps — mainly useful for re-testing the recovery path (Setup Device's own status rows)
 *  without needing adb. */
fun Context.openAppPermissionSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    runCatching { startActivity(intent) }
}
