package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.utils.log
import io.ktor.network.sockets.Socket
import java.nio.channels.SocketChannel

internal actual fun Socket.enableTcpKeepAlive() {
  runCatching {
    var cls: Class<*>? = this::class.java
    while (cls != null) {
      val field = cls.declaredFields.firstOrNull { SocketChannel::class.java.isAssignableFrom(it.type) }
      if (field != null) {
        field.isAccessible = true
        (field.get(this) as SocketChannel).socket().keepAlive = true
        return
      }
      cls = cls.superclass
    }
  }.onFailure { log("Server", "Failed to enable TCP keep-alive on accepted socket: ${it.message}") }
}
