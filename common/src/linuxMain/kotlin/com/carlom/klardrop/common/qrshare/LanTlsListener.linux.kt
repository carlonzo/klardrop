@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package com.carlom.klardrop.common.qrshare

import com.carlom.klardrop.common.qrshare.openssl.EVP_PKCS82PKEY
import com.carlom.klardrop.common.qrshare.openssl.EVP_PKEY_free
import com.carlom.klardrop.common.qrshare.openssl.PKCS8_PRIV_KEY_INFO_free
import com.carlom.klardrop.common.qrshare.openssl.SSL
import com.carlom.klardrop.common.qrshare.openssl.SSL_CTX_free
import com.carlom.klardrop.common.qrshare.openssl.SSL_CTX_ctrl
import com.carlom.klardrop.common.qrshare.openssl.SSL_CTX_new
import com.carlom.klardrop.common.qrshare.openssl.SSL_CTX_use_PrivateKey
import com.carlom.klardrop.common.qrshare.openssl.SSL_CTX_use_certificate_ASN1
import com.carlom.klardrop.common.qrshare.openssl.SSL_accept
import com.carlom.klardrop.common.qrshare.openssl.SSL_ERROR_SYSCALL
import com.carlom.klardrop.common.qrshare.openssl.SSL_ERROR_WANT_READ
import com.carlom.klardrop.common.qrshare.openssl.SSL_ERROR_WANT_WRITE
import com.carlom.klardrop.common.qrshare.openssl.SSL_free
import com.carlom.klardrop.common.qrshare.openssl.SSL_get_error
import com.carlom.klardrop.common.qrshare.openssl.SSL_new
import com.carlom.klardrop.common.qrshare.openssl.SSL_read
import com.carlom.klardrop.common.qrshare.openssl.SSL_set_fd
import com.carlom.klardrop.common.qrshare.openssl.SSL_shutdown
import com.carlom.klardrop.common.qrshare.openssl.SSL_write
import com.carlom.klardrop.common.qrshare.openssl.TLS_server_method
import com.carlom.klardrop.common.qrshare.openssl.d2i_PKCS8_PRIV_KEY_INFO
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.common.utils.sharedNativeIoDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.INADDR_ANY
import platform.posix.SHUT_RDWR
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.SO_SNDTIMEO
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.htonl
import platform.posix.htons
import platform.posix.listen
import platform.posix.posix_errno
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.timeval

/**
 * OpenSSL (libssl/libcrypto 3.x) cinterop implementation of the TLS server socket the
 * commonMain QR-share code expects. The listen/accept socket itself is a plain POSIX socket
 * (same pattern as [com.carlom.klardrop.common.discovery.verifyAdvertisedPortAlive]); OpenSSL
 * only wraps the already-accepted fd for the handshake and record layer.
 *
 * All blocking syscalls (accept/SSL_accept/SSL_read/SSL_write) run on raw Dispatchers.IO
 * (NOT the shared capped pool — each loop parks its thread for the connection lifetime, and
 * with 2 capped slots one active connection would already starve the accept loop).
 */
// Writing to a socket the peer has already RST'd raises SIGPIPE, whose default disposition
// kills the whole process — not just the connection. TLS 1.3 makes this easy to trigger even
// on a clean-looking handshake: the server can push a NewSessionTicket during/after SSL_accept
// before the app ever calls SSL_write, and a client that closes its socket without draining
// its receive buffer turns its close() into an RST. That's exactly what crashed the
// linuxX64Test binary with SIGPIPE when run in isolation. Same fix Exec.kt already applies for
// subprocess pipes (`ignoreSigpipeOnce`), duplicated here since that one is private to Exec.kt:
// EPIPE from a failed write()/SSL_write() is enough for this code (SSL_write already reports
// failure via its return value), so the process should never die from it.
private val ignoreSigpipeOnce: Unit by lazy {
  platform.posix.signal(platform.posix.SIGPIPE, platform.posix.SIG_IGN)
  Unit
}

actual class LanTlsListener actual constructor() {

  companion object {
    private const val ACCEPT_POLL_INTERVAL_MS = 500L

    // Per-connection SO_RCVTIMEO/SO_SNDTIMEO tick — how often a blocked SSL_read/SSL_write on
    // an otherwise-idle connection wakes up to re-check the idle deadline below.
    private const val CLIENT_POLL_INTERVAL_MS = 1_000L
  }

  // A client that completes the TLS handshake and then never sends anything would otherwise
  // block its read loop's SSL_read forever, permanently pinning one IO thread per idle
  // connection — bounded by idleTimeoutMillis below, not by any dispatcher cap. Mutable
  // (not a companion const) so a test can inject a short deadline instead of waiting 30s.
  internal var idleTimeoutMillis: Long = 30_000L

  private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var listenFd: Int? = null
  private var acceptJob: Job? = null
  private var sslCtx: CPointer<com.carlom.klardrop.common.qrshare.openssl.SSL_CTX>? = null
  private var connectionsChannel = Channel<TlsConnection>(Channel.BUFFERED)
  private val activeConnections = mutableListOf<TlsConnection>()
  private val mutex = Mutex()

  actual suspend fun bind(ipv4: String, port: Int): Bound = withContext(sharedNativeIoDispatcher) {
    ignoreSigpipeOnce
    close()
    mutex.withLock {
      if (connectionsChannel.isClosedForSend) {
        connectionsChannel = Channel(Channel.BUFFERED)
      }
      scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    val certResult = QrTlsCertGenerator.generate(ipv4)
    val ctx = buildSslContext(certResult)
    sslCtx = ctx

    val fd = socket(AF_INET, SOCK_STREAM, 0)
    check(fd >= 0) { "socket() failed" }
    memScoped {
      val one = alloc<IntVar>().apply { value = 1 }
      setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())

      // A blocking accept() doesn't reliably wake up just because another thread closed (or
      // even shutdown()'d) the listening fd — that race is what left a permanently-blocked
      // accept() thread pinned in sharedNativeIoDispatcher's bounded pool after every test that
      // called bind()+close() here, eventually starving the pool and hanging the whole
      // linuxX64Test binary for hours. A receive timeout makes accept() return EAGAIN
      // periodically instead, so the loop below can re-check `listenFd` and exit within
      // ACCEPT_POLL_INTERVAL_MS of close() no matter what the kernel does with the close race.
      val timeout = alloc<timeval>().apply {
        tv_sec = 0
        tv_usec = ACCEPT_POLL_INTERVAL_MS * 1000L
      }
      setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())

      val addr = alloc<sockaddr_in>().apply {
        sin_family = AF_INET.convert()
        sin_port = htons(port.toUShort())
        sin_addr.s_addr = htonl(INADDR_ANY.convert())
      }
      val bindResult = bind(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert())
      if (bindResult != 0) {
        close(fd)
        error("bind() failed on port $port")
      }
      if (listen(fd, 50) != 0) {
        close(fd)
        error("listen() failed")
      }
    }

    val boundPort = memScoped {
      val addr = alloc<sockaddr_in>()
      val len = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
      getsockname(fd, addr.ptr.reinterpret<sockaddr>(), len.ptr)
      platform.posix.ntohs(addr.sin_port).toInt()
    }

    listenFd = fd
    log("LanTlsListener", "Listening on 0.0.0.0:$boundPort for advertised host $ipv4")

    acceptJob = startAcceptLoop(fd, ctx, scope)

    Bound(boundPort)
  }

  actual fun incoming(): Flow<TlsConnection> = connectionsChannel.receiveAsFlow()

  actual fun close() {
    val fd = listenFd
    listenFd = null
    if (fd != null) {
      // Only a shutdown here, not close(fd): the accept loop (startAcceptLoop's `finally`) is
      // the sole owner of actually closing the listen fd. Closing it from both close() AND the
      // accept loop raced two close(2) calls on the same fd number — if the kernel had already
      // recycled that number to an unrelated fd opened by another thread in between, the second
      // close() would tear down that unrelated fd instead. shutdown() safely wakes the accept
      // loop's blocked accept() (or it wakes on its own via SO_RCVTIMEO within
      // ACCEPT_POLL_INTERVAL_MS regardless), and joining the job below waits for that loop to
      // actually close(fd) itself before this function returns.
      try {
        shutdown(fd, SHUT_RDWR)
      } catch (_: Throwable) {
      }
    }

    val job = acceptJob
    acceptJob = null
    if (job != null) {
      kotlinx.coroutines.runBlocking {
        // Short timeout, not indefinite: SO_RCVTIMEO already bounds how long the accept loop
        // can be blocked, so this should always complete well within it — but close() must
        // never hang if some future change breaks that guarantee.
        withTimeoutOrNull(2 * ACCEPT_POLL_INTERVAL_MS + 1_000L) { job.join() }
      }
    }

    val connsToClose: List<TlsConnection>
    kotlinx.coroutines.runBlocking {
      connsToClose = mutex.withLock {
        val copy = activeConnections.toList()
        activeConnections.clear()
        copy
      }
    }
    for (conn in connsToClose) {
      try {
        conn.close()
      } catch (_: Throwable) {
      }
    }

    connectionsChannel.close()
    scope.cancel()

    sslCtx?.let { SSL_CTX_free(it) }
    sslCtx = null
  }

  private fun buildSslContext(
    certResult: QrTlsCertResult,
  ): CPointer<com.carlom.klardrop.common.qrshare.openssl.SSL_CTX> {
    val ctx = TLS_server_method()?.let { SSL_CTX_new(it) }
      ?: error("SSL_CTX_new failed")
    // SSL_CTX_set_min_proto_version is a C macro (SSL_CTX_ctrl call), not a real symbol cinterop
    // can bind directly, so this replicates it: SSL_CTRL_SET_MIN_PROTO_VERSION=123, TLS1_2_VERSION=0x0303.
    SSL_CTX_ctrl(ctx, 123, 0x0303, null)

    certResult.certDer.usePinned { pinned ->
      val ok = SSL_CTX_use_certificate_ASN1(
        ctx,
        certResult.certDer.size,
        pinned.addressOf(0).reinterpret(),
      )
      check(ok == 1) { "SSL_CTX_use_certificate_ASN1 failed" }
    }

    // The common code hands us the private key as PKCS#8 DER (SPKI-wrapped), not the
    // "traditional" EC-only DER that SSL_CTX_use_PrivateKey_ASN1 expects, so it's unwrapped
    // via PKCS8_PRIV_KEY_INFO -> EVP_PKEY first (mirrors java.security.spec.PKCS8EncodedKeySpec
    // on the JVM actual).
    certResult.privateKeyPkcs8Der.usePinned { pinned ->
      memScoped {
        val p = allocPointerTo<platform.posix.uint8_tVar>()
        p.value = pinned.addressOf(0).reinterpret()
        val p8inf = d2i_PKCS8_PRIV_KEY_INFO(null, p.ptr, certResult.privateKeyPkcs8Der.size.convert())
          ?: error("d2i_PKCS8_PRIV_KEY_INFO failed")
        val pkey = EVP_PKCS82PKEY(p8inf) ?: run {
          PKCS8_PRIV_KEY_INFO_free(p8inf)
          error("EVP_PKCS82PKEY failed")
        }
        PKCS8_PRIV_KEY_INFO_free(p8inf)
        val ok = SSL_CTX_use_PrivateKey(ctx, pkey)
        EVP_PKEY_free(pkey)
        check(ok == 1) { "SSL_CTX_use_PrivateKey failed" }
      }
    }

    return ctx
  }

  private fun formatIpv4(networkOrderAddr: UInt): String {
    val h = platform.posix.ntohl(networkOrderAddr)
    return "${(h shr 24) and 0xFFu}.${(h shr 16) and 0xFFu}.${(h shr 8) and 0xFFu}.${h and 0xFFu}"
  }

  private fun startAcceptLoop(
    fd: Int,
    ctx: CPointer<com.carlom.klardrop.common.qrshare.openssl.SSL_CTX>,
    acceptScope: CoroutineScope,
  ): Job {
    return acceptScope.launch(Dispatchers.IO) {
      try {
        // isActive AND listenFd (rather than just the `fd` parameter) so this loop notices
        // close() even if the SO_RCVTIMEO wakeup races with the fd being closed/reused
        // elsewhere — see the SO_RCVTIMEO comment in bind() for why accept() alone can't be
        // trusted to unblock on close().
        while (isActive && listenFd == fd) {
          var clientFd = -1
          var peerIpv4 = ""
          var stop = false
          memScoped {
            val addr = alloc<sockaddr_in>()
            val len = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            val cfd = accept(fd, addr.ptr.reinterpret<sockaddr>(), len.ptr)
            if (cfd < 0) {
              val err = posix_errno()
              stop = err != EAGAIN && err != EWOULDBLOCK && err != EINTR
            } else {
              clientFd = cfd
              peerIpv4 = formatIpv4(addr.sin_addr.s_addr)
            }
          }
          if (stop) break // a real accept() failure (e.g. EBADF after close()) — exit
          if (clientFd < 0) continue // SO_RCVTIMEO poll tick or EINTR — loop back, re-check listenFd

          launch(Dispatchers.IO) {
            handleClient(clientFd, peerIpv4, ctx)
          }
        }
      } finally {
        try {
          close(fd)
        } catch (_: Throwable) {
        }
      }
    }
  }

  private fun setSocketTimeouts(fd: Int, millis: Long) = memScoped {
    val tv = alloc<timeval>().apply {
      tv_sec = millis / 1000
      tv_usec = (millis % 1000) * 1000
    }
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, tv.ptr, sizeOf<timeval>().convert())
    Unit
  }

  /**
   * True if this SSL_read/SSL_write result was just a socket-timeout "tick" (SO_RCVTIMEO /
   * SO_SNDTIMEO expiring with nothing to do) rather than a real error or peer close — i.e. the
   * caller should re-check its idle deadline and retry, not tear the connection down.
   */
  private fun isTimeoutTick(ssl: CPointer<SSL>, result: Int): Boolean {
    val err = SSL_get_error(ssl, result)
    if (err == SSL_ERROR_WANT_READ || err == SSL_ERROR_WANT_WRITE) return true
    if (err == SSL_ERROR_SYSCALL) {
      val e = posix_errno()
      return e == EAGAIN || e == EWOULDBLOCK
    }
    return false
  }

  private suspend fun handleClient(
    clientFd: Int,
    peerIpv4: String,
    ctx: CPointer<com.carlom.klardrop.common.qrshare.openssl.SSL_CTX>,
  ) {
    // See CLIENT_POLL_INTERVAL_MS: bounds how long a blocked SSL_read/SSL_write can hold this
    // connection's dispatcher thread before it wakes up to re-check the idle deadline.
    setSocketTimeouts(clientFd, CLIENT_POLL_INTERVAL_MS)

    val ssl = SSL_new(ctx)
    if (ssl == null) {
      close(clientFd)
      return
    }
    SSL_set_fd(ssl, clientFd)

    val accepted = SSL_accept(ssl) == 1
    if (!accepted) {
      log("LanTlsListener", "Handshake dropped: SSL_accept error ${SSL_get_error(ssl, -1)}")
      SSL_free(ssl)
      close(clientFd)
      return
    }

    val inputChannel = ByteChannel(autoFlush = true)
    val outputChannel = ByteChannel(autoFlush = true)

    // The read loop (SSL_read) and write loop (SSL_write) below run as two independent
    // coroutines on Dispatchers.IO (a real multi-threaded pool), so they can be
    // blocked inside OpenSSL on two different native threads at the same time.
    //
    // That's fine for SSL_read/SSL_write themselves: OpenSSL (>=1.1.0) supports one thread
    // reading while another writes on the same SSL object, as long as there's never two
    // concurrent reads or two concurrent writes — true here (exactly one reader coroutine, one
    // writer coroutine). An earlier version of this code wrapped every SSL_read/SSL_write call
    // in a shared Mutex "to be safe"; that serialized them instead of letting them run
    // full-duplex, so once the read loop's blocking SSL_read (waiting for more client bytes
    // that never arrive, e.g. after a one-shot request) took the lock, the write loop could
    // never acquire it to send the response — a self-inflicted deadlock caught by
    // LanTlsListenerNativeTest.handshakeAndByteExchangeRoundTrip's hard timeout.
    //
    // What genuinely isn't safe is freeing `ssl` (SSL_free) or closing `clientFd` while the
    // *other* loop is still inside a blocking SSL_read/SSL_write on it — a use-after-free /
    // use-after-close that crashed the daemon with SIGSEGV in BIO_read during the smoke test
    // (one loop hit EOF and tore the connection down while the other was still blocked
    // reading). The fix: an external/early close() only requests a shutdown (unblocks
    // whichever loop is mid-syscall by tearing down the raw fd's read/write direction, which
    // *is* safe to do from another thread), and the actual SSL_free/close(fd) only happens
    // once BOTH loops have observably exited (finishLock-guarded join below).
    val finishLock = Mutex()
    var loopsFinished = 0

    suspend fun requestShutdown() {
      try {
        platform.posix.shutdown(clientFd, platform.posix.SHUT_RDWR)
      } catch (_: Throwable) {
      }
    }

    suspend fun onLoopFinished() {
      val bothDone = finishLock.withLock {
        loopsFinished += 1
        loopsFinished >= 2
      }
      if (!bothDone) return
      try {
        inputChannel.close()
      } catch (_: Throwable) {
      }
      try {
        outputChannel.close()
      } catch (_: Throwable) {
      }
      // Safe without a lock: by construction both the read and write loops have already
      // returned by the time bothDone is true, so nothing else touches `ssl` anymore.
      try {
        SSL_shutdown(ssl)
      } catch (_: Throwable) {
      }
      SSL_free(ssl)
      try {
        close(clientFd)
      } catch (_: Throwable) {
      }
    }

    val tlsConnection = TlsConnection(
      peerIpv4 = peerIpv4,
      input = inputChannel,
      output = outputChannel,
      close = {
        kotlinx.coroutines.runBlocking {
          requestShutdown()
          try {
            inputChannel.close()
          } catch (_: Throwable) {
          }
          try {
            outputChannel.close()
          } catch (_: Throwable) {
          }
        }
      },
    )

    mutex.withLock { activeConnections.add(tlsConnection) }

    scope.launch(Dispatchers.IO) {
      val buffer = ByteArray(8192)
      var idleSince = TimeSource.Monotonic.markNow()
      try {
        while (isActive && !inputChannel.isClosedForWrite) {
          val read = buffer.usePinned { pinned -> SSL_read(ssl, pinned.addressOf(0), buffer.size) }
          if (read > 0) {
            idleSince = TimeSource.Monotonic.markNow()
            inputChannel.writeFully(buffer, 0, read)
            continue
          }
          // read <= 0: a real EOF/error, or just this connection's SO_RCVTIMEO tick with
          // nothing to read yet — only the latter should keep the connection alive.
          if (!isTimeoutTick(ssl, read)) break
          if (idleSince.elapsedNow().inWholeMilliseconds >= idleTimeoutMillis) {
            log("LanTlsListener", "Closing idle connection from $peerIpv4 (no bytes for ${idleTimeoutMillis}ms)")
            break
          }
        }
      } catch (_: Throwable) {
      } finally {
        requestShutdown()
        onLoopFinished()
      }
    }

    scope.launch(Dispatchers.IO) {
      val buffer = ByteArray(8192)
      var idleSince = TimeSource.Monotonic.markNow()
      try {
        while (isActive && !outputChannel.isClosedForRead) {
          val read = outputChannel.readAvailable(buffer, 0, buffer.size)
          if (read == -1) break
          if (read == 0) continue
          idleSince = TimeSource.Monotonic.markNow()
          var offset = 0
          while (offset < read) {
            val written = buffer.usePinned { pinned ->
              SSL_write(ssl, pinned.addressOf(offset), read - offset)
            }
            if (written > 0) {
              offset += written
              idleSince = TimeSource.Monotonic.markNow()
              continue
            }
            if (!isTimeoutTick(ssl, written) ||
              idleSince.elapsedNow().inWholeMilliseconds >= idleTimeoutMillis
            ) {
              throw IllegalStateException("SSL_write failed or peer stopped reading")
            }
          }
        }
      } catch (_: Throwable) {
      } finally {
        requestShutdown()
        mutex.withLock { activeConnections.remove(tlsConnection) }
        onLoopFinished()
      }
    }

    connectionsChannel.send(tlsConnection)
  }
}
