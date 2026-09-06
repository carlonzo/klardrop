package com.carlom.klardrop.common.qrshare

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.time.Duration.Companion.seconds

actual class PlatformLanAddressSelector actual constructor() : LanAddressSelector {

  private var context: Context? = null

  constructor(context: Context?) : this() {
    this.context = context
  }

  actual override suspend fun selectIpv4(): String? = withContext(Dispatchers.IO) {
    val candidates = enumerateInterfaces()
    val picked = selectLanAddress(candidates)
    log("LanAddressSelector", "candidates=$candidates picked=$picked")
    picked
  }

  actual override fun observeChanges(): Flow<String?> = callbackFlow {
    var previous = selectLanAddress(enumerateInterfaces())
    trySend(previous)

    val cm = context?.let {
      runCatching {
        it.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      }.getOrNull()
    }

    fun checkAndUpdate() {
      val current = selectLanAddress(enumerateInterfaces())
      if (current != previous) {
        previous = current
        trySend(current)
      }
    }

    val callback = if (cm != null) {
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { checkAndUpdate() }
        override fun onLost(network: Network) { checkAndUpdate() }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { checkAndUpdate() }
      }
    } else null

    if (cm != null && callback != null) {
      runCatching {
        val request = NetworkRequest.Builder().build()
        cm.registerNetworkCallback(request, callback)
      }
    }

    val pollJob = launch(Dispatchers.IO) {
      while (isActive) {
        delay(POLL_INTERVAL)
        checkAndUpdate()
      }
    }

    awaitClose {
      pollJob.cancel()
      if (cm != null && callback != null) {
        runCatching { cm.unregisterNetworkCallback(callback) }
      }
    }
  }.distinctUntilChanged().flowOn(Dispatchers.IO)

  private fun enumerateInterfaces(): List<Pair<String, String>> {
    val nicAddrs = runCatching {
      val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching emptyList()
      buildList {
        for (iface in ifaces) {
          if (iface.isLoopback) continue
          val isUp = runCatching { iface.isUp }.getOrElse { false }
          if (!isUp) continue
          for (addr in iface.inetAddresses) {
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
              val host = addr.hostAddress ?: continue
              add(iface.name to host)
            }
          }
        }
      }
    }.getOrElse {
      log("LanAddressSelector", "interface enumeration failed: ${it.message}")
      emptyList()
    }
    return wifiOrEthernetFromConnectivityManager() + nicAddrs
  }

  /**
   * Active Wi-Fi / Ethernet IPv4 from ConnectivityManager, tagged as wlan0/eth0 so
   * ranking treats them as STA LAN (ahead of leftover hotspot 192.168.43.1).
   */
  private fun wifiOrEthernetFromConnectivityManager(): List<Pair<String, String>> {
    val cm = context?.let {
      runCatching {
        it.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      }.getOrNull()
    } ?: return emptyList()
    val network = cm.activeNetwork ?: return emptyList()
    val caps = cm.getNetworkCapabilities(network) ?: return emptyList()
    val ifaceName = when {
      caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wlan0"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth0"
      else -> return emptyList()
    }
    val lp = cm.getLinkProperties(network) ?: return emptyList()
    return lp.linkAddresses.mapNotNull { la ->
      val addr = la.address as? Inet4Address ?: return@mapNotNull null
      if (addr.isLoopbackAddress) return@mapNotNull null
      val host = addr.hostAddress ?: return@mapNotNull null
      ifaceName to host
    }
  }

  private companion object {
    val POLL_INTERVAL = 2.seconds
  }
}

fun LanAddressSelector(context: Context): LanAddressSelector = PlatformLanAddressSelector(context)
