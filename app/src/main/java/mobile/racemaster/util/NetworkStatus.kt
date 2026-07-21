package mobile.racemaster.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

// NET_CAPABILITY_VALIDATED (not just NET_CAPABILITY_INTERNET) is what actually confirms the
// active network's path out to the internet works — e.g. WiFi associated to a router with no
// upstream still reports NET_CAPABILITY_INTERNET but not VALIDATED. Used by Mule Mode's
// Setup Server prompt (see MuleModeViewModel/Screen) to recommend "With server" vs "Without
// server" before the operator picks — a plain, synchronous, checked-once read (matching how
// this screen already checks isLoggedIn once on entry), not a live subscription: it's a
// one-time hint at the moment of choosing, not something that needs to react to the signal
// changing while the operator is mid-decision.
fun hasInternetConnectivity(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
