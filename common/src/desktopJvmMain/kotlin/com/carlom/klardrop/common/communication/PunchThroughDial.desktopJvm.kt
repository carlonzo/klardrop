package com.carlom.klardrop.common.communication

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import kotlinx.coroutines.delay
import java.net.DatagramSocket
import java.net.InetSocketAddress as JavaInetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.SocketChannel

internal actual suspend fun punchThroughConnect(
  selectorManager: SelectorManager,
  remoteAddress: InetSocketAddress,
  localBindPort: Int,
): Socket? = runCatching {
  val remoteJava = JavaInetSocketAddress(remoteAddress.hostname, remoteAddress.port)

  // The listener holds the wildcard (0.0.0.0:<port>), so the dial socket must bind a
  // SPECIFIC local address for SO_REUSEADDR to permit the co-bind. Derive the local
  // address that routes to the peer with the UDP-connect trick — no packets are sent.
  val localIp = runCatching {
    DatagramSocket().use { datagram ->
      datagram.connect(remoteJava)
      datagram.localAddress.hostAddress
    }
  }.getOrNull()?.takeUnless { it == "0.0.0.0" } ?: return@runCatching null

  val channel = SocketChannel.open()
  try {
    // SO_REUSEPORT (not just REUSEADDR) is what lets this dial socket co-bind the port our
    // LISTENing server socket holds — Linux rejects REUSEADDR-only co-binds against a
    // listener. The dial socket never listens, so SYNs still go only to the server.
    channel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
    channel.setOption(StandardSocketOptions.SO_REUSEPORT, true)
    channel.bind(JavaInetSocketAddress(localIp, localBindPort))
    channel.configureBlocking(false)
    if (!channel.connect(remoteJava)) {
      val deadlineNanos = System.nanoTime() + TCP_CONNECT_TIMEOUT_MS * 1_000_000
      while (!channel.finishConnect()) {
        if (System.nanoTime() >= deadlineNanos) error("Punch-through connect to $remoteJava timed out")
        delay(50)
      }
    }
    // Bridge into ktor's internal SocketImpl so the shared handshake/pool path works
    // unchanged — ktor 3.x has no public local-bind connect. ponytail: reflection pins
    // this to ktor-network-jvm 3.5.x internals; if the constructor moves, this returns
    // null and punch-through degrades to a normal failed dial (upgrade path: a public
    // ktor local-bind connect, or our own NIOSocketImpl-compatible wrapper).
    Class.forName("io.ktor.network.sockets.SocketImpl")
      .getConstructor(
        SocketChannel::class.java,
        SelectorManager::class.java,
        Class.forName("io.ktor.network.sockets.SocketOptions\$TCPClientSocketOptions"),
      )
      .newInstance(channel, selectorManager, null) as Socket
  } catch (t: Throwable) {
    runCatching { channel.close() }
    throw t
  }
}.getOrNull()

// The bound dial is implemented; an individual attempt may still fail and return null.
internal actual val punchThroughSupported: Boolean = true
