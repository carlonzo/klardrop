@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.qrshare

import com.carlom.klardrop.common.utils.log
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.sockaddr_in
import kotlin.time.Duration.Companion.seconds

actual class PlatformLanAddressSelector actual constructor() : LanAddressSelector {

  actual override suspend fun selectIpv4(): String? =
    selectLanAddress(enumerateAppleInterfaces())

  actual override fun observeChanges(): Flow<String?> = flow {
    var previous = selectLanAddress(enumerateAppleInterfaces())
    emit(previous)
    while (true) {
      delay(POLL_INTERVAL)
      val current = selectLanAddress(enumerateAppleInterfaces())
      if (current != previous) {
        previous = current
        emit(current)
      }
    }
  }.distinctUntilChanged()

  private companion object {
    val POLL_INTERVAL = 2.seconds
  }
}

internal fun enumerateAppleInterfaces(): List<Pair<String, String>> = memScoped {
  return runCatching {
    val ifap = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(ifap.ptr) != 0) return emptyList()
    val result = mutableListOf<Pair<String, String>>()
    try {
      var cursor: CPointer<ifaddrs>? = ifap.value
      while (cursor != null) {
        val ifa = cursor.pointed
        val name = ifa.ifa_name?.toKString()
        val addr = ifa.ifa_addr
        val flags = ifa.ifa_flags.toInt()
        // IFF_UP = 0x1, IFF_LOOPBACK = 0x8
        val isUp = (flags and 0x1) != 0
        val isLoopback = (flags and 0x8) != 0
        if (name != null && addr != null && isUp && !isLoopback) {
          val sa = addr.pointed
          val family = sa.sa_family.toInt() and 0xFF
          if (family == AF_INET) {
            val saIn = addr.reinterpret<sockaddr_in>()
            val bytePtr = saIn.pointed.sin_addr.ptr.reinterpret<UByteVar>()
            val ip = "${bytePtr[0]}.${bytePtr[1]}.${bytePtr[2]}.${bytePtr[3]}"
            result.add(name to ip)
          }
        }
        cursor = ifa.ifa_next
      }
    } finally {
      freeifaddrs(ifap.value)
    }
    result
  }.getOrElse {
    log("LanAddressSelector", "Apple interface enumeration failed: ${it.message}")
    emptyList()
  }
}

private const val AF_INET = 2
