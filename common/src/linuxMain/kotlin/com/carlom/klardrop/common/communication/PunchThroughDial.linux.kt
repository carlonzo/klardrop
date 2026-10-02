@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.utils.sharedNativeIoDispatcher
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.SocketAddress
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ReaderJob
import io.ktor.utils.io.WriterJob
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.reader
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource
import platform.posix.AF_INET
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.MSG_NOSIGNAL
import platform.posix.O_NONBLOCK
import platform.posix.POLLERR
import platform.posix.POLLOUT
import platform.posix.SHUT_RDWR
import platform.posix.SHUT_WR
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_REUSEADDR
import platform.posix.SO_REUSEPORT
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.fcntl
import platform.posix.getsockname
import platform.posix.getsockopt
import platform.posix.htonl
import platform.posix.htons
import platform.posix.ntohl
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.posix_errno
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socklen_tVar
import platform.posix.socket

// Linux-native T10 punch-through dial: POSIX bind(SO_REUSEADDR+SO_REUSEPORT) to
// (local address routing to the peer, our own listening port) + non-blocking connect with a
// poll() timeout, wrapped in a ktor Socket (no reflection — ktor 3.x has no public local-bind
// connect, and the JVM actual's SocketImpl reflection has no Native equivalent). Same burst
// contract as PunchThroughDial.desktopJvm.kt: null on any failure, never throw.
internal actual suspend fun punchThroughConnect(
  selectorManager: SelectorManager,
  remoteAddress: InetSocketAddress,
  localBindPort: Int,
): Socket? = runCatching {
  withContext(sharedNativeIoDispatcher) {
    val remoteIp = parseIpv4HostOrder(remoteAddress.hostname)
      ?: error("punch-through needs a literal IPv4 peer address, got ${remoteAddress.hostname}")
    val localIp = routeLocalIp(remoteIp, remoteAddress.port)
      ?: error("punch-through could not route to ${remoteAddress.hostname}")
    if (localIp == INADDR_ANY_VALUE) error("punch-through resolved only 0.0.0.0 as the route to peer")
    val fd = boundConnectFd(localIp, localBindPort, remoteIp, remoteAddress.port)
    PosixTcpSocket(fd, formatIpv4(localIp), localBindPort, remoteAddress.hostname, remoteAddress.port)
  }
}.getOrNull()

// The bound dial above is implemented; an individual attempt may still fail and return null.
internal actual val punchThroughSupported: Boolean = true

private const val INADDR_ANY_VALUE = 0u
private const val PUMP_BUFFER_SIZE = 32 * 1024

private fun parseIpv4HostOrder(host: String): UInt? {
  val parts = host.split('.')
  if (parts.size != 4) return null
  var addr = 0u
  for (part in parts) {
    val byte = part.toUIntOrNull() ?: return null
    if (byte > 255u) return null
    addr = (addr shl 8) or byte
  }
  return addr
}

private fun formatIpv4(networkOrderAddr: UInt): String {
  val h = ntohl(networkOrderAddr)
  return "${(h shr 24) and 0xFFu}.${(h shr 16) and 0xFFu}.${(h shr 8) and 0xFFu}.${h and 0xFFu}"
}

/**
 * The local address that routes to the peer, via the UDP-connect trick (no packets are sent —
 * connect() on a datagram socket only binds the route). Mirrors the JVM actual's DatagramSocket
 * probe; same pattern as AdvertisedPortProbe's POSIX connect + LanTlsListener's getsockname.
 * Returns the address in network byte order, or null on failure.
 */
private fun routeLocalIp(remoteIpHostOrder: UInt, remotePort: Int): UInt? = memScoped {
  val fd = socket(AF_INET, SOCK_DGRAM, 0)
  if (fd < 0) return null
  try {
    val remote = alloc<sockaddr_in>().apply {
      sin_family = AF_INET.convert()
      sin_port = htons(remotePort.toUShort())
      sin_addr.s_addr = htonl(remoteIpHostOrder)
    }
    if (connect(fd, remote.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) != 0) return null
    val local = alloc<sockaddr_in>()
    val len = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
    if (getsockname(fd, local.ptr.reinterpret<sockaddr>(), len.ptr) != 0) return null
    local.sin_addr.s_addr
  } finally {
    close(fd)
  }
}

/**
 * Creates a TCP fd bound to ([localIpNetworkOrder], [localBindPort]) with SO_REUSEADDR (plus
 * SO_REUSEPORT, which is what permits the co-bind against our LISTENing server socket — Linux
 * rejects a REUSEADDR-only co-bind against a listener) and connects it to the peer with a
 * poll()-bounded non-blocking connect (a blocking connect() to a black-holed peer would pin
 * this IO thread past TCP SYN retries; the caller's withTimeout(TCP_CONNECT_TIMEOUT_MS) only
 * cancels the coroutine, not the syscall). Ownership of the fd transfers to the caller — every
 * failure path closes it before throwing.
 */
private fun boundConnectFd(
  localIpNetworkOrder: UInt,
  localBindPort: Int,
  remoteIpHostOrder: UInt,
  remotePort: Int,
): Int = memScoped {
  val fd = socket(AF_INET, SOCK_STREAM, 0)
  if (fd < 0) error("punch-through socket() failed")
  var owned = true
  try {
    val one = alloc<IntVar>().apply { value = 1 }
    if (setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert()) != 0) {
      error("punch-through setsockopt(SO_REUSEADDR) failed")
    }
    if (setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, one.ptr, sizeOf<IntVar>().convert()) != 0) {
      error("punch-through setsockopt(SO_REUSEPORT) failed")
    }
    val local = alloc<sockaddr_in>().apply {
      sin_family = AF_INET.convert()
      sin_port = htons(localBindPort.toUShort())
      sin_addr.s_addr = localIpNetworkOrder
    }
    if (bind(fd, local.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) != 0) {
      error("punch-through bind() to port $localBindPort failed (errno ${posix_errno()})")
    }

    val savedFlags = fcntl(fd, F_GETFL, 0)
    if (savedFlags < 0 || fcntl(fd, F_SETFL, savedFlags or O_NONBLOCK) != 0) {
      error("punch-through fcntl(O_NONBLOCK) failed")
    }
    try {
      val remote = alloc<sockaddr_in>().apply {
        sin_family = AF_INET.convert()
        sin_port = htons(remotePort.toUShort())
        sin_addr.s_addr = htonl(remoteIpHostOrder)
      }
      if (connect(fd, remote.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) != 0 &&
        posix_errno() != EINPROGRESS
      ) {
        error("punch-through connect() failed immediately (errno ${posix_errno()})")
      }
      // EINPROGRESS (or an instant loopback success): wait for writability, then read SO_ERROR
      // — POLLOUT fires for refused connects too, so the error check is what decides.
      val start = TimeSource.Monotonic.markNow()
      var connected = false
      val pollFd = alloc<pollfd>()
      pollFd.fd = fd
      pollFd.events = (POLLOUT or POLLERR).toShort()
      while (!connected) {
        val left = TCP_CONNECT_TIMEOUT_MS - start.elapsedNow().inWholeMilliseconds
        if (left <= 0) error("punch-through connect() timed out")
        when (poll(pollFd.ptr, 1u, left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())) {
          0 -> error("punch-through connect() timed out")
          -1 -> if (posix_errno() != EINTR) error("punch-through poll() failed")
          else -> {
            val err = alloc<IntVar>()
            val errLen = alloc<socklen_tVar>().apply { value = sizeOf<IntVar>().convert() }
            if (getsockopt(fd, SOL_SOCKET, SO_ERROR, err.ptr, errLen.ptr) != 0) {
              error("punch-through getsockopt(SO_ERROR) failed")
            }
            if (err.value != 0) error("punch-through connect() refused/unreachable (errno ${err.value})")
            connected = true
          }
        }
      }
    } finally {
      if (fcntl(fd, F_SETFL, savedFlags) != 0) error("punch-through fcntl() restore failed")
    }
    owned = false
    fd
  } finally {
    if (owned) close(fd)
  }
}

/**
 * ktor [Socket] over an already-connected POSIX fd. Pumps bridge the fd to the attached
 * ByteChannels with blocking recv()/send() on raw Dispatchers.IO — the same shape as
 * LanTlsListener's SSL_read/SSL_write loops, minus TLS. MSG_NOSIGNAL (not process-wide SIGPIPE
 * suppression) keeps a write to an RST'd peer a plain EPIPE error instead of killing the daemon.
 *
 * Lifetime: close() is idempotent and only requests teardown (shutdown wakes a pump blocked in
 * recv); the actual close(fd) runs once the last attached pump's job completes — or immediately
 * from close() when nothing was ever attached — so a recycled fd number can never alias a still
 * blocked pump (the accept()-vs-close() lesson from LanTlsListener).
 */
internal class PosixTcpSocket(
  private val fd: Int,
  localIp: String,
  localPort: Int,
  remoteIp: String,
  remotePort: Int,
) : Socket {
  override val localAddress: SocketAddress = InetSocketAddress(localIp, localPort)
  override val remoteAddress: SocketAddress = InetSocketAddress(remoteIp, remotePort)

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  override val coroutineContext: CoroutineContext get() = scope.coroutineContext
  override val socketContext: Job get() = scope.coroutineContext[Job]!!

  // Written once by attach, read by close() from any thread. Promptness hints only: shutdown +
  // scope.cancel() already drive every exit path, and each pump closes its own channel — the
  // same plain-var sharing LanTlsListener uses for listenFd.
  private var readSide: ByteChannel? = null
  private var writeSide: ByteChannel? = null
  private val attached = AtomicInt(0)
  private val finished = AtomicInt(0)
  private val teardown = AtomicInt(0)
  private val fdClosed = AtomicInt(0)

  override fun attachForReading(channel: ByteChannel): WriterJob {
    check(teardown.value == 0) { "punch-through socket is closed" }
    readSide = channel
    attached.incrementAndGet()
    // Public writer() builder (the WriterJob/ReaderJob constructors are ktor-internal):
    // pumps the fd into the caller's channel and closes it at EOF, like a normal socket.
    // Completion is tracked via the underlying job — not a finally in the block — so a pump
    // cancelled before its first statement still releases the fd (launch skips the block then).
    val writerJob = scope.writer(Dispatchers.IO, channel = channel) { readPump(channel) }
    writerJob.job.invokeOnCompletion { onPumpExit() }
    return writerJob
  }

  override fun attachForWriting(channel: ByteChannel): ReaderJob {
    check(teardown.value == 0) { "punch-through socket is closed" }
    writeSide = channel
    attached.incrementAndGet()
    val readerJob = scope.reader(Dispatchers.IO, channel = channel) { writePump(channel) }
    readerJob.job.invokeOnCompletion { onPumpExit() }
    return readerJob
  }

  override fun close() {
    if (!teardown.compareAndSet(0, 1)) return
    runCatching { shutdown(fd, SHUT_RDWR) }
    readSide?.let { runCatching { it.close() } }
    writeSide?.let { runCatching { it.close() } }
    scope.cancel()
    if (finished.value == attached.value) closeFdOnce()
  }

  private fun onPumpExit() {
    if (finished.incrementAndGet() == attached.value && teardown.value == 1) closeFdOnce()
  }

  private fun closeFdOnce() {
    if (fdClosed.compareAndSet(0, 1)) runCatching { close(fd) }
  }

  private suspend fun readPump(channel: ByteChannel) {
    val buffer = ByteArray(PUMP_BUFFER_SIZE)
    try {
      while (true) {
        val received = buffer.usePinned { pinned ->
          recv(fd, pinned.addressOf(0), buffer.size.toULong(), 0)
        }
        if (received <= 0) break // 0 = orderly FIN, <0 = RST/error/close()-wake
        channel.writeFully(buffer, 0, received.toInt())
        channel.flush()
      }
    } catch (_: Throwable) {
      // Reader gone or scope cancelled — fall through to teardown below.
    } finally {
      runCatching { channel.close() }
      runCatching { shutdown(fd, SHUT_RDWR) }
    }
  }

  private suspend fun writePump(channel: ByteChannel) {
    val buffer = ByteArray(PUMP_BUFFER_SIZE)
    try {
      while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        if (read == 0) continue
        var offset = 0
        while (offset < read) {
          val sent = buffer.usePinned { pinned ->
            send(fd, pinned.addressOf(offset), (read - offset).toULong(), MSG_NOSIGNAL)
          }
          if (sent <= 0) return // EPIPE/EBADF/RST — readPump's shutdown or a dead peer
          offset += sent.toInt()
        }
      }
    } catch (_: Throwable) {
      // Writer gone or scope cancelled — fall through to teardown below.
    } finally {
      // Half-close: the peer sees EOF on our stream but can still send (mirrors ktor's
      // write-side shutdown on channel close).
      runCatching { shutdown(fd, SHUT_WR) }
    }
  }
}
