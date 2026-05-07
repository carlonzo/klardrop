package com.carlom.klardrop.common.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

actual class NetworkLifecycleMonitor(private val context: Context) {

  private val connectivityManager by lazy {
    context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  }

  actual fun observe(): Flow<NetworkChangeEvent> = callbackFlow {
    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        log("NetworkLifecycleMonitor", "onAvailable: $network")
        trySend(NetworkChangeEvent.Changed)
      }

      override fun onLost(network: Network) {
        log("NetworkLifecycleMonitor", "onLost: $network")
        trySend(NetworkChangeEvent.Changed)
      }

      override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        log("NetworkLifecycleMonitor", "onCapabilitiesChanged: $network")
        trySend(NetworkChangeEvent.Changed)
      }

      override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        log("NetworkLifecycleMonitor", "onLinkPropertiesChanged: $network")
        trySend(NetworkChangeEvent.Changed)
      }
    }

    val request = NetworkRequest.Builder()
      .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
      .build()

    connectivityManager.registerNetworkCallback(request, callback)
    log("NetworkLifecycleMonitor", "registered network callback")

    awaitClose {
      runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        .onFailure { log("NetworkLifecycleMonitor", "unregister failed: ${it.message}") }
    }
  }
}
