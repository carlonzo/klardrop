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

internal actual class PlatformLanAddressSelector actual constructor() : LanAddressSelector {

  private var context: Context? = null

  constructor(context: Context?) : this() {
    this.context = context
  }

  actual override suspend fun selectIpv4(): String? = withContext(Dispatchers.IO) {
    val candidates = enumerateInterfaces()
    val picked = selectLanAddressOf(candidates)
    log("LanAddressSelector", "candidates=$candidates picked=$picked")
    picked
  }

  actual override suspend fun selectManualLanAddress(): ManualLanAddress? = withContext(Dispatchers.IO) {
    selectManualLanAddress(enumerateInterfaces())
  }

  actual override fun observeChanges(): Flow<String?> = callbackFlow {
    var previous = selectLanAddressOf(enumerateInterfaces())
    trySend(previous)

    val cm = context?.let {
      runCatching {
        it.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      }.getOrNull()
    }

    fun checkAndUpdate() {
      val current = selectLanAddressOf(enumerateInterfaces())
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

  private fun enumerateInterfaces(): List<LanInterfaceAddress> {
    val nicAddrs = runCatching {
      val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching emptyList()
      buildList {
        for (iface in ifaces) {
          if (iface.isLoopback) continue
          val isUp = runCatching { iface.isUp }.getOrElse { false }
          if (!isUp) continue
          for (ia in iface.interfaceAddresses) {
            val addr = ia.address
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
              val host = addr.hostAddress ?: continue
              val prefix = ia.networkPrefixLength.toInt().takeIf { it in 0..32 }
              add(LanInterfaceAddress(iface.name, host, prefix))
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
  private fun wifiOrEthernetFromConnectivityManager(): List<LanInterfaceAddress> {
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
      val prefix = la.prefixLength.takeIf { it in 0..32 }
      LanInterfaceAddress(ifaceName, host, prefix)
    }
  }

  private companion object {
    val POLL_INTERVAL = 2.seconds
  }
}

internal fun LanAddressSelector(context: Context): LanAddressSelector = PlatformLanAddressSelector(context)
