package com.carlom.klardrop.common.utils.network

import java.net.NetworkInterface

actual class NetworkAddressUtil {
  actual fun getLocalAddresses(): Set<String> {
    return NetworkInterface.getNetworkInterfaces().asSequence()
      .filterNot { it.isLoopback || it.isVirtual }
      .flatMap { networkInterface ->
        networkInterface.inetAddresses.asSequence()
          .map { inet -> inet.hostAddress }.filterNot { address -> address.contains(char = ':') }
      }.toSet()
  }

}

