package mobile.racemaster.data.mule

import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the device's Bluetooth radio is currently on — checked before starting a Kable
 *  scan, since scanning with it off throws (com.juul.kable.UnmetRequirementException)
 *  instead of just failing, and Kable's own reconnection handling doesn't cover "the radio
 *  itself is off" the same way it covers a dropped peripheral connection. */
class BluetoothStateRepository(private val context: Context) {
    fun isEnabled(): Boolean {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        return manager.adapter?.isEnabled == true
    }

    // Tracks whether PeripheralSyncService's own BLE advertising (this device being visible to
    // *other* phones as a Mule) has been failing repeatedly — confirmed in the field on a
    // device whose BLE chipset firmware got wedged such that startAdvertising() kept being
    // rejected by the OS even across a manual Bluetooth off/on toggle in Settings, and only a
    // full phone restart (power cycle) actually recovered it. That's below anything this app's
    // retry loop can reach, so the best it can do is stop failing silently and tell the
    // operator what actually worked in the field, rather than leave them retrying a Bluetooth
    // toggle that (for this class of failure) won't help.
    @Volatile
    private var consecutiveAdvertisingFailures = 0
    private val advertisingWarningFlow = MutableStateFlow<String?>(null)

    /** Non-null once advertising has failed [ADVERTISING_FAILURE_THRESHOLD] times in a row
     *  with no intervening success — see [recordAdvertisingFailure]'s own doc for why a plain
     *  Bluetooth toggle is called out by name here rather than left implicit. */
    val advertisingWarning: StateFlow<String?> = advertisingWarningFlow.asStateFlow()

    @Synchronized
    fun recordAdvertisingSuccess() {
        consecutiveAdvertisingFailures = 0
        advertisingWarningFlow.value = null
    }

    @Synchronized
    fun recordAdvertisingFailure() {
        consecutiveAdvertisingFailures++
        if (consecutiveAdvertisingFailures >= ADVERTISING_FAILURE_THRESHOLD) {
            advertisingWarningFlow.value =
                "Not visible to nearby devices — if turning Bluetooth off/on doesn't fix it, restart the phone"
        }
    }

    // Identified by either PullRequest.isSink or AckPayload.isSink (see recordWebAppSeen/
    // recordWebAppPush) — both are set only by the racemaster web app's own BLE client, never
    // by an ordinary phone-to-phone Mule. Reassigned to whichever address most recently
    // self-identified, so a browser reconnecting under a rotated MAC address (Web Bluetooth
    // periodically rotates it) is followed rather than left pointing at a now-stale address.
    @Volatile
    private var webAppAddress: String? = null

    private val lastWebAppSeenAtMillisFlow = MutableStateFlow<Long?>(null)

    /** Null until the web app has identified itself at least once this process's life (see
     *  [recordWebAppSeen]); the timestamp of the most recent contact after that. Bumped on
     *  every poll tick regardless of whether it turned up new data — the web app's own
     *  `pullFromConnectedPhone` writes a self-identifying PullRequest.isSink on every single
     *  tick, whether or not anything ends up being pulled (see [recordWebAppSeen]'s own doc) —
     *  unlike [lastWebAppPushedAtMillis] below, which only bumps when something actually went
     *  out. Mirrors [DiscoveredDevice.lastReachableAtMillis]'s own "successful contact, not
     *  necessarily new data" role for a nearby device row. */
    val lastWebAppSeenAtMillis: StateFlow<Long?> = lastWebAppSeenAtMillisFlow.asStateFlow()

    private val lastWebAppPushedAtMillisFlow = MutableStateFlow<Long?>(null)

    /** Null until this device's own data has actually reached a Bluetooth-connected sink
     *  (currently only ever the racemaster web app's own BLE client, identified by
     *  [AckPayload.isSink] — see its own doc) at least once this process's life; the timestamp
     *  of the most recent one after that. Named from this device's own point of view — the web
     *  app's `pullFromConnectedPhone` is doing the pulling on its end, but from here it's this
     *  device pushing its own data out, the same direction [MuleRepository.lastPushAttemptAtMillis]
     *  names for the server. Mirrors [DiscoveredDevice.lastPulledAtMillis]'s own role for a
     *  nearby device row (that one *is* named from a pull's own point of view, since that's
     *  genuinely this device pulling from someone else). */
    val lastWebAppPushedAtMillis: StateFlow<Long?> = lastWebAppPushedAtMillisFlow.asStateFlow()

    /** Called on every CONTROL write, from any central, with that request's own
     *  [PullRequest.isSink] — true only for the racemaster web app's own BLE client, and
     *  present on every single poll tick regardless of whether anything ends up being pulled
     *  (see that field's own doc for why this exists at all: without it, [address] could only
     *  ever be identified via [recordWebAppPush], which the web app skips entirely whenever it
     *  has nothing new to ack — leaving this permanently "never" for a phone that simply has no
     *  fresh data to push, even while the web app keeps polling it fine every few seconds).
     *  [isSink] true (re-)identifies [address] as the web app's own for future calls (covering
     *  a browser reconnecting under a rotated MAC address) and always bumps
     *  [lastWebAppSeenAtMillis]; false only bumps it if [address] was already identified — an
     *  ordinary phone-to-phone Mule's own PullRequest, which never sets isSink, must never bump
     *  this at all. */
    fun recordWebAppSeen(address: String, isSink: Boolean) {
        if (isSink) webAppAddress = address
        if (address != webAppAddress) return
        lastWebAppSeenAtMillisFlow.value = System.currentTimeMillis()
    }

    /** Called on every ack a connected central identifies itself as a genuine sink with (see
     *  [AckPayload.isSink]) — the racemaster web app's own `sendSinkAck` only ever writes one
     *  when it actually has new lines to report (an empty batch never reaches the wire — see
     *  its own doc), so every call here is itself proof this device's data genuinely reached
     *  it, with no separate "was it actually non-empty" check needed. Also (re-)identifies
     *  [address] as the web app's own, the same as a [PullRequest.isSink] CONTROL write does
     *  (see [recordWebAppSeen]) — redundant in the common case (the same tick's own CONTROL
     *  write already did this), but a useful backstop if that field is ever missing (an older
     *  build of the web app). */
    fun recordWebAppPush(address: String) {
        webAppAddress = address
        val now = System.currentTimeMillis()
        lastWebAppSeenAtMillisFlow.value = now
        lastWebAppPushedAtMillisFlow.value = now
    }

    private companion object {
        const val ADVERTISING_FAILURE_THRESHOLD = 5
    }
}
