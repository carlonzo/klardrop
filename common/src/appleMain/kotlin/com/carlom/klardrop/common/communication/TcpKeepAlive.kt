package com.carlom.klardrop.common.communication

import io.ktor.network.sockets.Socket

/**
 * No-op on Apple targets: Ktor's native socket impl doesn't expose the
 * underlying file descriptor, and we cannot set SO_KEEPALIVE without it.
 * The application-level heartbeat covers this case.
 */
internal actual fun Socket.enableTcpKeepAlive() {
  // intentionally empty
}
