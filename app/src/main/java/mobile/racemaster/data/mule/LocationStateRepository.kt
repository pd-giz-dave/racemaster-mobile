package mobile.racemaster.data.mule

import android.content.Context
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat

/** Whether Android's system Location toggle (Settings > Location — distinct from this app's own
 *  ACCESS_FINE/COARSE_LOCATION runtime permission grant, which [mobile.racemaster.util.RuntimePermissions]
 *  already covers) is currently on — checked the same plain, on-demand way as
 *  [BluetoothStateRepository.isEnabled]. Android silently drops every BLE scan result fleet-wide
 *  while this is off (this app's own manifest can't declare `neverForLocation`, since
 *  [mobile.racemaster.data.beacon.BeaconScanDecoder]'s distance-estimation use of a scan's RSSI
 *  depends on location-derived scan results not being exempted) — with no exception, no logcat
 *  entry, nothing at all, just a scan that silently finds nothing. Confirmed in the field as the
 *  likely explanation for one phone showing zero nearby same-group peers while a second phone
 *  right next to it discovered that first phone just fine (its own BLE *advertising*, a wholly
 *  separate code path, is unaffected by this toggle either way) — see
 *  [mobile.racemaster.data.peersync.PeerSyncEngine]'s own discovery loop, which this silently
 *  starves. [LocationManagerCompat.isLocationEnabled] (not the API 28+ only
 *  `LocationManager.isLocationEnabled`) is what covers this app's `minSdk = 24` down to API 24
 *  itself, via the older `Settings.Secure.LOCATION_MODE` check internally on those releases. */
class LocationStateRepository(private val context: Context) {
    fun isEnabled(): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }
}
