@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.network

import com.carlom.klardrop.common.qrshare.isDroppedInterface
import com.carlom.klardrop.common.utils.log
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.linux.freeifaddrs
import platform.linux.getifaddrs
import platform.linux.ifaddrs
import platform.posix.sockaddr_in

internal data class LinuxInterfaceInfo(
  val name: String,
  val isUp: Boolean,
  val isLoopback: Boolean,
  val ipv4Addresses: List<String>,
)

private class MutableInterfaceInfo(
  val name: String,
  val isUp: Boolean,
  val isLoopback: Boolean,
  val ipv4Addresses: MutableList<String> = mutableListOf(),
)

internal fun getLinuxNetworkInterfaces(): List<LinuxInterfaceInfo> = memScoped {
  return runCatching {
    val ifap = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(ifap.ptr) != 0) return emptyList()
    val ifaceMap = mutableMapOf<String, MutableInterfaceInfo>()
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
        if (name != null) {
          val info = ifaceMap.getOrPut(name) { MutableInterfaceInfo(name, isUp, isLoopback) }
          if (addr != null) {
            val family = addr.pointed.sa_family.toInt() and 0xFF
            if (family == AF_INET) {
              val saIn = addr.reinterpret<sockaddr_in>()
              val bytePtr = saIn.pointed.sin_addr.ptr.reinterpret<UByteVar>()
              val ip = "${bytePtr[0]}.${bytePtr[1]}.${bytePtr[2]}.${bytePtr[3]}"
              if (!ip.startsWith("127.")) {
                if (!info.ipv4Addresses.contains(ip)) {
                  info.ipv4Addresses.add(ip)
                }
              }
            }
          }
        }
        cursor = ifa.ifa_next
      }
    } finally {
      freeifaddrs(ifap.value)
    }
    ifaceMap.values.map { LinuxInterfaceInfo(it.name, it.isUp, it.isLoopback, it.ipv4Addresses.toList()) }
  }.getOrElse {
    log("LinuxNetworkInterfaces", "interface enumeration failed: ${it.message}")
    emptyList()
  }
}

internal fun isVirtualOrDroppedInterface(name: String): Boolean {
  val lower = name.lowercase().trim()
  return isDroppedInterface(lower) ||
    lower.startsWith("docker") ||
    lower.startsWith("veth") ||
    lower.startsWith("virbr") ||
    lower.startsWith("br-")
}

private const val AF_INET = 2
