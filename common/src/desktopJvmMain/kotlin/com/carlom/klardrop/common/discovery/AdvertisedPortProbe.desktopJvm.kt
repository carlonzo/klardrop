package com.carlom.klardrop.common.discovery

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Desktop JVM probe: open a loopback TCP connection to 127.0.0.1:<port>.
 * Connection refused / timeout / any failure means nothing listens there.
 */
actual fun verifyAdvertisedPortAlive(port: Int): Boolean = try {
  Socket().use { socket ->
    socket.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS)
  }
  true
} catch (_: Exception) {
  false
}

private const val PROBE_TIMEOUT_MS = 1000
