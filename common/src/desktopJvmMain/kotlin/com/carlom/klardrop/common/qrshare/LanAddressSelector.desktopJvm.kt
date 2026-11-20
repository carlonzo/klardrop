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

internal actual class PlatformLanAddressSelector actual constructor() : LanAddressSelector {

  actual override suspend fun selectIpv4(): String? = withContext(Dispatchers.IO) {
    selectLanAddressOf(enumerateInterfaces())
  }

  actual override suspend fun selectManualLanAddress(): ManualLanAddress? = withContext(Dispatchers.IO) {
    selectManualLanAddress(enumerateInterfaces())
  }

  actual override fun observeChanges(): Flow<String?> = flow {
    var previous = selectLanAddressOf(enumerateInterfaces())
    emit(previous)
    while (true) {
      delay(POLL_INTERVAL)
      val current = selectLanAddressOf(enumerateInterfaces())
      if (current != previous) {
        previous = current
        emit(current)
      }
    }
  }.distinctUntilChanged().flowOn(Dispatchers.IO)

  private fun enumerateInterfaces(): List<LanInterfaceAddress> {
    return runCatching {
      val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
      buildList {
        for (iface in ifaces) {
          if (iface.isLoopback) continue
          val isUp = runCatching { iface.isUp }.getOrDefault(false)
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
  }

  private companion object {
    val POLL_INTERVAL = 5.seconds
  }
}
