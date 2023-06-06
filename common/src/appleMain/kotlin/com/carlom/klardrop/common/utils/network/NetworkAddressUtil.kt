package com.carlom.klardrop.common.utils.network

import kotlinx.cinterop.*
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.sockaddr_in


actual class NetworkAddressUtil actual constructor() {
  actual fun getLocalAddresses(): Set<String> {

    memScoped {

      val interfaces = alloc<CPointerVarOf<CPointer<ifaddrs>>>()

      val success = getifaddrs(interfaces.ptr)
      if (success == 0) {
        var tempAddr = interfaces.value
        while (tempAddr != null) {
          val addr = tempAddr.pointed.ifa_addr
          if (addr?.pointed?.sa_family == AF_INET.toUByte()) {


            val socketAddr = addr.reinterpret<sockaddr_in>().pointed
            val name = socketAddr.sin_zero.toKString()
            if (name == "en0") {


              val ipAddr = socketAddr.sin_addr
              val ipBytes = ipAddr.s_addr.toUByte()
              return setOf(ipAddr.toString()) // todo wrong. every byte should be converted to a number joined with the dot
            }
          }
          tempAddr = tempAddr.pointed.ifa_next
        }
      }

      return emptySet()
    }

  }
}