package dev.atlas.nym

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class NetworkRoute(val label: String = "Checking connection…", val online: Boolean = false, val vpn: Boolean = false)
class NetworkMonitor(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private fun snapshot(): NetworkRoute {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return NetworkRoute("Offline · waiting for network", false, false)
        val vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val transport = when {
            vpn -> "VPN · system route"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi · system route"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data · system route"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet · system route"
            else -> "System network"
        }
        return NetworkRoute(if (online) transport else "$transport · connectivity unverified", online, vpn)
    }
    val flow = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(snapshot()) }
            override fun onLost(network: Network) { trySend(snapshot()) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(snapshot()) }
        }
        manager.registerDefaultNetworkCallback(callback)
        trySend(snapshot())
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
