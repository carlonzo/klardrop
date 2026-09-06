package com.carlom.klardrop.common.qrshare

import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.time.Duration.Companion.seconds

actual class PlatformLanAddressSelector actual constructor() : LanAddressSelector {

  actual override suspend fun selectIpv4(): String? = withContext(Dispatchers.IO) {
    selectLanAddress(enumerateInterfaces())
  }

  actual override fun observeChanges(): Flow<String?> = flow {
    var previous = selectLanAddress(enumerateInterfaces())
    emit(previous)
    while (true) {
      delay(POLL_INTERVAL)
      val current = selectLanAddress(enumerateInterfaces())
      if (current != previous) {
        previous = current
        emit(current)
      }
    }
  }.distinctUntilChanged().flowOn(Dispatchers.IO)

  private fun enumerateInterfaces(): List<Pair<String, String>> {
    return runCatching {
      val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
      buildList {
        for (iface in ifaces) {
          if (iface.isLoopback) continue
          val isUp = runCatching { iface.isUp }.getOrDefault(false)
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
    val POLL_INTERVAL = 5.seconds
  }
}
