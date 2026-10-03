@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.utils.sharedNativeIoDispatcher
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.AF_INET
import platform.posix.AF_UNIX
import platform.posix.EBADF
import platform.posix.F_GETFL
import platform.posix.SHUT_RDWR
import platform.posix.SHUT_WR
import platform.posix.SOCK_STREAM
import platform.posix.bind
import platform.posix.close
import platform.posix.fcntl
import platform.posix.getsockname
import platform.posix.htonl
import platform.posix.ntohs
import platform.posix.posix_errno
import platform.posix.shutdown
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socklen_tVar
import platform.posix.socket
import platform.posix.socketpair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// linuxX64 mirror of PunchThroughDialTest.directDialTimeoutIsFollowedBySuccessfulPunchThroughDial
// (desktopJvmTest): pins that the POSIX bound dial co-binds our own listening port (the
// REUSEPORT listener shape mirrors Server.bindListener) and that the resulting ktor Socket
// carries bytes both ways. Every test wraps its body in withTimeout — same hard-timeout
// discipline as LanTlsListenerNativeTest, so a stuck native thread fails one test instead of
// hanging the shared linuxX64Test binary.
class PunchThroughDialNativeTest {

  companion object {
    private const val TEST_TIMEOUT_MS = 10_000L
  }

  @Test
  fun punchThroughIsSupported() {
    assertTrue(punchThroughSupported, "linuxX64 implements the bound dial")
  }

  @Test
  fun boundDialFromOwnListeningPortExchangesBytes(): Unit = runBlocking {
    withTimeout(TEST_TIMEOUT_MS) {
      val selector = SelectorManager(sharedNativeIoDispatcher)
      try {
        // "Our" listener — same reuseAddress+reusePort shape as Server.bindListener. The dial
        // below co-binds this port; that co-bind (not a same-port connect) is the REUSEPORT
        // behavior under test. (Dialing from P to P is deliberately NOT tested: on loopback the
        // kernel completes that as a TCP self-connect to the dial socket itself, so the
        // listener's accept would never fire and the test would be nondeterministic.)
        val ownListener = aSocket(selector).tcp().bind("127.0.0.1", 0) {
          reuseAddress = true
          reusePort = true
        }
        val ownPort = (ownListener.localAddress as InetSocketAddress).port
        assertTrue(ownPort > 0)
        // The "peer": a plain listener on a different port, like PunchThroughDialTest's
        // java.net.ServerSocket peer — no reuse flags needed on their side.
        val peerListener = aSocket(selector).tcp().bind("127.0.0.1", 0)
        val peerPort = (peerListener.localAddress as InetSocketAddress).port
        assertTrue(peerPort > 0)
        try {
          val accepted = async { peerListener.accept() }
          val socket = assertNotNull(
            punchThroughConnect(selector, InetSocketAddress("127.0.0.1", peerPort), ownPort),
            "bound dial to a listening peer must succeed",
          )
          try {
            assertEquals(
              ownPort,
              (socket.localAddress as InetSocketAddress).port,
              "dial socket must be bound to our own listening port",
            )
            assertEquals(
              peerPort,
              (socket.remoteAddress as InetSocketAddress).port,
              "dial socket must be connected to the peer's port",
            )
            assertFalse(socket.isClosed, "freshly dialed socket must not report closed")

            val serverSocket = withTimeout(5_000) { accepted.await() }
            try {
              val serverRead = serverSocket.openReadChannel()
              val serverWrite = serverSocket.openWriteChannel(autoFlush = true)
              val clientWrite = socket.openWriteChannel(autoFlush = true)
              val clientRead = socket.openReadChannel()

              val ping = "PING".encodeToByteArray()
              clientWrite.writeFully(ping, 0, ping.size)
              assertEquals("PING", serverRead.readExactBytes(4).decodeToString())

              val pong = "PONG".encodeToByteArray()
              serverWrite.writeFully(pong, 0, pong.size)
              assertEquals("PONG", clientRead.readExactBytes(4).decodeToString())
            } finally {
              serverSocket.close()
            }
          } finally {
            socket.close()
          }
          socket.socketContext.join()
          assertTrue(socket.isClosed, "closed socket must report closed (ConnectionsPool relies on this)")
        } finally {
          peerListener.close()
          ownListener.close()
        }
      } finally {
        selector.close()
      }
    }
  }

  @Test
  fun bothPumpsCompletingBeforeCloseStillReleasesOwnedDescriptor(): Unit = runBlocking {
    withTimeout(TEST_TIMEOUT_MS) {
      val (clientFd, peerFd) = memScoped {
        val fds = allocArray<IntVar>(2)
        check(socketpair(AF_UNIX, SOCK_STREAM, 0, fds) == 0) { "socketpair() failed" }
        fds[0] to fds[1]
      }
      var socket: PosixTcpSocket? = null
      var bothPumpsCompleted = false
      try {
        assertTrue(clientFd >= 0 && peerFd >= 0, "socketpair fds must be valid")
        assertTrue(fcntl(clientFd, F_GETFL, 0) >= 0, "clientFd must be open initially")

        socket = PosixTcpSocket(clientFd, "127.0.0.1", 12345, "127.0.0.1", 54321)
        val readChannel = ByteChannel(autoFlush = true)
        val writeChannel = ByteChannel(autoFlush = true)

        val writerJob = socket.attachForReading(readChannel)
        val readerJob = socket.attachForWriting(writeChannel)

        // Register CompletableDeferred completion barriers AFTER attachForReading/attachForWriting
        // registered their internal onPumpExit handlers. Since invokeOnCompletion runs in registration
        // order, awaiting these barriers guarantees onPumpExit has finished and incremented finished.
        val readDone = CompletableDeferred<Unit>()
        val writeDone = CompletableDeferred<Unit>()
        writerJob.job.invokeOnCompletion { readDone.complete(Unit) }
        readerJob.job.invokeOnCompletion { writeDone.complete(Unit) }

        // Trigger EOF on readPump by shutting down peer's write end.
        // recv(clientFd, ...) returns 0, readPump completes, and its onPumpExit runs.
        shutdown(peerFd, SHUT_WR)

        // Trigger EOF on writePump by closing writeChannel.
        // writePump reads -1 from channel, completes, and its onPumpExit runs.
        writeChannel.close()

        // Wait for both completion barriers, then join both jobs before calling close()
        readDone.await()
        writeDone.await()
        writerJob.job.join()
        readerJob.job.join()
        bothPumpsCompleted = true

        // At this point both pumps have completed their try/finally blocks and invoked onPumpExit.
        // Before socket.close(), clientFd must remain open.
        assertTrue(fcntl(clientFd, F_GETFL, 0) >= 0, "clientFd must remain open while socket is not yet closed")

        // Now call close(). Under the unpatched code (attached.value == 0), close() skipped closeFdOnce()
        // because attached.value == 2. With the fix (finished.value == attached.value),
        // close() releases the descriptor.
        socket.close()
        socket.socketContext.join()
        assertTrue(socket.isClosed, "closed socket must report closed")

        // Verify owned descriptor is released
        assertEquals(-1, fcntl(clientFd, F_GETFL, 0), "descriptor must be closed after socket.close()")
        assertEquals(EBADF, posix_errno(), "errno must be EBADF for closed descriptor")
      } finally {
        withContext(NonCancellable) {
          runCatching { shutdown(peerFd, SHUT_RDWR) }
          close(peerFd)
          socket?.let { s ->
            s.close()
            withTimeoutOrNull(TEST_TIMEOUT_MS) {
              s.socketContext.join()
            }
          }
          if ((socket == null || bothPumpsCompleted) && fcntl(clientFd, F_GETFL, 0) >= 0) {
            close(clientFd)
          }
        }
      }
    }
  }

  @Test
  fun dialToClosedPortReturnsNull(): Unit = runBlocking {
    withTimeout(TEST_TIMEOUT_MS) {
      // Two distinct reserved-then-closed loopback ports: binding the dial socket to one and
      // dialing the other proves a refused connect reports null. (Same-port dial is excluded:
      // with no listener the kernel completes it as a TCP self-connect to the dial socket.)
      val localPort = reserveDeadPort()
      val deadPort = reserveDeadPort()
      val selector = SelectorManager(sharedNativeIoDispatcher)
      try {
        assertNull(
          punchThroughConnect(selector, InetSocketAddress("127.0.0.1", deadPort), localPort),
          "dial to a closed port must return null",
        )
      } finally {
        selector.close()
      }
    }
  }

  private fun reserveDeadPort(): Int = memScoped {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    check(fd >= 0) { "socket() failed" }
    val addr = alloc<sockaddr_in>().apply {
      sin_family = AF_INET.toUShort()
      sin_port = 0u
      sin_addr.s_addr = htonl(0x7F000001u)
    }
    check(bind(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().toUInt()) == 0) {
      "bind() failed"
    }
    val bound = alloc<sockaddr_in>()
    val len = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().toUInt() }
    getsockname(fd, bound.ptr.reinterpret<sockaddr>(), len.ptr)
    val port = ntohs(bound.sin_port).toInt()
    close(fd)
    port
  }

  private suspend fun ByteReadChannel.readExactBytes(count: Int): ByteArray {
    val out = ByteArray(count)
    var offset = 0
    while (offset < count) {
      val read = readAvailable(out, offset, count - offset)
      if (read == -1) error("peer closed mid-message")
      offset += read
    }
    return out
  }
}
