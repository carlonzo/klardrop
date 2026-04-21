package com.carlom.klardrop.common.communication

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.nio.channels.SocketChannel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies Ktor's `connect(...) { keepAlive = true }` actually enables
 * SO_KEEPALIVE on the underlying NIO socket, and that the default leaves it
 * off (the bug we fix in Client.kt and Server.kt).
 *
 * Reaches the underlying SocketChannel via reflection because Ktor's
 * SocketImpl is `internal`.
 */
class SocketKeepAliveTest {

  private val selectorManager = SelectorManager(Dispatchers.IO)
  private var serverSocket: io.ktor.network.sockets.ServerSocket? = null
  private var client: Socket? = null
  private var accepted: Socket? = null

  @AfterTest
  fun tearDown() {
    runCatching { client?.close() }
    runCatching { accepted?.close() }
    runCatching { serverSocket?.close() }
    runCatching { selectorManager.close() }
  }

  @Test
  fun connectWithKeepAliveEnablesSoKeepAliveOnUnderlyingNioSocket() = runBlocking {
    val server = aSocket(selectorManager).tcp().bind("127.0.0.1", 0)
    serverSocket = server
    val port = (server.localAddress as InetSocketAddress).port

    val acceptDeferred = async(Dispatchers.IO) { server.accept() }

    val clientSocket = aSocket(selectorManager).tcp().connect("127.0.0.1", port) {
      keepAlive = true
    }
    client = clientSocket
    accepted = acceptDeferred.await()

    assertTrue(
      clientSocket.underlyingNioSocket().keepAlive,
      "SO_KEEPALIVE should be set when configured with keepAlive = true",
    )
  }

  @Test
  fun connectWithoutKeepAliveLeavesSoKeepAliveOff() = runBlocking {
    val server = aSocket(selectorManager).tcp().bind("127.0.0.1", 0)
    serverSocket = server
    val port = (server.localAddress as InetSocketAddress).port

    val acceptDeferred = async(Dispatchers.IO) { server.accept() }

    val clientSocket = aSocket(selectorManager).tcp().connect("127.0.0.1", port)
    client = clientSocket
    accepted = acceptDeferred.await()

    // Sanity check that captures the pre-fix default.
    assertFalse(
      clientSocket.underlyingNioSocket().keepAlive,
      "Default Ktor connect() should NOT set SO_KEEPALIVE (the pre-fix bug)",
    )
  }

  private fun Socket.underlyingNioSocket(): java.net.Socket {
    var cls: Class<*>? = this::class.java
    while (cls != null) {
      val field = cls.declaredFields.firstOrNull { SocketChannel::class.java.isAssignableFrom(it.type) }
      if (field != null) {
        field.isAccessible = true
        return (field.get(this) as SocketChannel).socket()
      }
      cls = cls.superclass
    }
    error("Could not find SocketChannel field on ${this::class.java}")
  }
}
