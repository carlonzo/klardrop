@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.discovery

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_SNDTIMEO
import platform.posix.close
import platform.posix.connect
import platform.posix.htonl
import platform.posix.htons
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval

private const val PROBE_TIMEOUT_SEC = 1L
private const val INADDR_LOOPBACK_VALUE = 0x7F000001u

/**
 * Linux native probe: open a loopback TCP connection to 127.0.0.1:<port> via POSIX socket.
 * Connection refused / timeout / any failure means nothing listens there.
 */
actual fun verifyAdvertisedPortAlive(port: Int): Boolean = memScoped {
  val fd = socket(AF_INET, SOCK_STREAM, 0)
  if (fd < 0) return false
  try {
    val timeout = alloc<timeval>().apply {
      tv_sec = PROBE_TIMEOUT_SEC
      tv_usec = 0
    }
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, timeout.ptr, sizeOf<timeval>().toUInt())
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().toUInt())

    val addr = alloc<sockaddr_in>().apply {
      sin_family = AF_INET.toUShort()
      sin_port = htons(port.toUShort())
      sin_addr.s_addr = htonl(INADDR_LOOPBACK_VALUE)
    }

    val ret = connect(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().toUInt())
    ret == 0
  } catch (_: Throwable) {
    false
  } finally {
    close(fd)
  }
}
