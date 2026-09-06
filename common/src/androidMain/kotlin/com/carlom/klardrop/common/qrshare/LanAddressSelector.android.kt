package com.carlom.klardrop.common.qrshare

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
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
    selectLanAddress(enumerateInterfaces())
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
    return runCatching {
      val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
      buildList {
        for (iface in ifaces) {
          if (iface.isLoopback) continue
          val isUp = runCatching { iface.isUp }.getOrElse { true }
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
  }

  private companion object {
    val POLL_INTERVAL = 2.seconds
  }
}

fun LanAddressSelector(context: Context): LanAddressSelector = PlatformLanAddressSelector(context)
