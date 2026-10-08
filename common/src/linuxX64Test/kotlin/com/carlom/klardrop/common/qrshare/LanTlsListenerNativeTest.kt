@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.qrshare

// `SSL` comes from the cryptography provider's OpenSSL cinterop, not from our own
// `openssl` cinterop: both index the same system headers, so cinterop dedups it out
// of ours and into the provider's package. Same reason as in LanTlsListener.linux.kt.
import dev.whyoleg.cryptography.providers.openssl3.internal.cinterop.SSL
import com.carlom.klardrop.common.qrshare.openssl.SSL_CTX_free
import com.carlom.klardrop.common.qrshare.openssl.SSL_CTX_new
import com.carlom.klardrop.common.qrshare.openssl.SSL_CTX_set_verify
import com.carlom.klardrop.common.qrshare.openssl.SSL_connect
import com.carlom.klardrop.common.qrshare.openssl.SSL_free
import com.carlom.klardrop.common.qrshare.openssl.SSL_new
import com.carlom.klardrop.common.qrshare.openssl.SSL_read
import com.carlom.klardrop.common.qrshare.openssl.SSL_set_fd
import com.carlom.klardrop.common.qrshare.openssl.SSL_write
import com.carlom.klardrop.common.qrshare.openssl.TLS_client_method
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
import platform.posix.htons
import platform.posix.htonl
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// linuxX64 mirror of common/src/desktopJvmTest/.../qrshare/LanTlsListenerTest.kt: no JSSE on
// Kotlin/Native, so the client side here is a raw OpenSSL client via the same cinterop the
// server actual uses (SSL_VERIFY_NONE — this is a transport-level handshake+roundtrip check,
// not a cert-pinning test; pinning of the exact DER bytes is exercised in commonTest against
// QrTlsCertGenerator/QrTokenTable instead). // ponytail: no SAN/EKU field assertions here (would
// need a DER parser on the client side); JVM test already covers cert content, this test only
// proves the native listener terminates real TLS handshakes and streams bytes both ways.
class LanTlsListenerNativeTest {

  companion object {
    // Every test below wraps its whole body in withTimeout: a regression that leaves a native
    // thread blocked forever (like the accept()-vs-close() race LanTlsListener.linux.kt now
    // works around — see the SO_RCVTIMEO comment in bind()) must fail this one test fast
    // instead of hanging the entire linuxX64Test binary.
    private const val TEST_TIMEOUT_MS = 10_000L
  }

  private fun connectRawTls(port: Int): Pair<Int, kotlinx.cinterop.CPointer<SSL>> {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    check(fd >= 0) { "client socket() failed" }
    memScoped {
      val addr = alloc<sockaddr_in>().apply {
        sin_family = AF_INET.convert()
        sin_port = htons(port.toUShort())
        sin_addr.s_addr = htonl(0x7F000001u)
      }
      val r = connect(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert())
      check(r == 0) { "client connect() failed" }
    }
    val ctx = TLS_client_method()?.let { com.carlom.klardrop.common.qrshare.openssl.SSL_CTX_new(it) }
      ?: error("client SSL_CTX_new failed")
    SSL_CTX_set_verify(ctx, 0, null) // SSL_VERIFY_NONE: self-signed cert, no pinning in this test
    val ssl = SSL_new(ctx) ?: error("client SSL_new failed")
    SSL_set_fd(ssl, fd)
    check(SSL_connect(ssl) == 1) { "client SSL_connect (handshake) failed" }
    SSL_CTX_free(ctx)
    return fd to ssl
  }

  @Test
  fun bindReturnsNonZeroPortAndHandshakeSucceeds(): Unit = runBlocking {
    withTimeout(TEST_TIMEOUT_MS) {
      val listener = LanTlsListener()
      try {
        val bound = listener.bind("10.0.0.1", 0)
        assertTrue(bound.port > 0, "Bound port must be non-zero")

        val (fd, ssl) = connectRawTls(bound.port)
        SSL_free(ssl)
        close(fd)
      } finally {
        listener.close()
      }
    }
  }

  @Test
  fun handshakeAndByteExchangeRoundTrip(): Unit = runBlocking {
    withTimeout(TEST_TIMEOUT_MS) {
      val listener = LanTlsListener()
      try {
        val bound = listener.bind("10.0.0.1", 0)

        val connDeferred = CompletableDeferred<TlsConnection>()
        val collectJob = launch {
          listener.incoming().collect { conn -> connDeferred.complete(conn) }
        }

        val (fd, ssl) = connectRawTls(bound.port)

        val serverConn = withTimeout(5_000) { connDeferred.await() }
        assertEquals("127.0.0.1", serverConn.peerIpv4)

        // Client -> server
        val ping = "PING\n".encodeToByteArray()
        ping.usePinned { pinned -> SSL_write(ssl, pinned.addressOf(0), ping.size) }
        val line = withTimeout(5_000) { serverConn.input.readUTF8Line() }
        assertEquals("PING", line)

        // Server -> client
        serverConn.output.writeStringUtf8("PONG\n")
        serverConn.output.flush()
        val readBuf = ByteArray(64)
        var got = 0
        while (got < 5) {
          val n = readBuf.usePinned { pinned -> SSL_read(ssl, pinned.addressOf(got), readBuf.size - got) }
          check(n > 0) { "client SSL_read failed" }
          got += n
        }
        assertEquals("PONG\n", readBuf.decodeToString(0, got))

        SSL_free(ssl)
        close(fd)
        serverConn.close()
        collectJob.cancel()
      } finally {
        listener.close()
      }
    }
  }

  @Test
  fun closePreventsNewConnect(): Unit = runBlocking {
    withTimeout(TEST_TIMEOUT_MS) {
      val listener = LanTlsListener()
      val bound = listener.bind("10.0.0.1", 0)
      assertTrue(bound.port > 0)

      listener.close()

      var refused = false
      withTimeout(2_000) {
        while (!refused) {
          val fd = socket(AF_INET, SOCK_STREAM, 0)
          val connected = memScoped {
            val addr = alloc<sockaddr_in>().apply {
              sin_family = AF_INET.convert()
              sin_port = htons(bound.port.toUShort())
              sin_addr.s_addr = htonl(0x7F000001u)
            }
            connect(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0
          }
          close(fd)
          if (!connected) refused = true else delay(20)
        }
      }
      assertTrue(refused, "connect to closed listener should fail")
    }
  }

  @Test
  fun idleConnectionIsClosedAndPoolRecoversForANewConnection(): Unit = runBlocking {
    withTimeout(30_000) {
      val listener = LanTlsListener()
      // Short deadline injected for the test instead of the 30s production default — see
      // idleTimeoutMillis's kdoc on LanTlsListener.
      listener.idleTimeoutMillis = 500
      try {
        val bound = listener.bind("10.0.0.1", 0)

        // 10 clients that complete the handshake and then send nothing: each one's server-side
        // read loop used to block forever on SSL_read, leaking one thread per idle client —
        // enough idle clients would starve the daemon for every other connection.
        val idleClients = (1..10).map { connectRawTls(bound.port) }

        // Prove the server actually closes idle connections once idleTimeoutMillis elapses:
        // read on the client side of every one of them and expect EOF (0) rather than a hang.
        // Waiting for ALL 10 (not just the first) also doubles as the "no leaked loops"
        // proof: this only completes once every read loop has actually exited.
        for ((_, idleSsl) in idleClients) {
          val eofSeen = withTimeoutOrNull(15_000) {
            val buf = ByteArray(1)
            var n: Int
            do {
              delay(50)
              n = buf.usePinned { pinned -> SSL_read(idleSsl, pinned.addressOf(0), 1) }
            } while (n > 0)
            n <= 0
          }
          assertTrue(eofSeen == true, "server must close an idle connection after idleTimeoutMillis")
        }

        // No-leak check: a brand new connection must still complete a full
        // handshake + PING/PONG round trip promptly, proving the idle connections' loops
        // exited rather than leaked their threads.
        //
        // Every one of the 10 idle connections already got sent into connectionsChannel by
        // handleClient() (whether or not anything ever collects it — the channel is just
        // buffered), so the first 10 items this collector sees are those stale idle
        // connections, not the new one below; skip exactly that many within a single
        // continuous collect rather than re-subscribing (receiveAsFlow() is single-shot).
        val connDeferred = CompletableDeferred<TlsConnection>()
        var seen = 0
        val collectJob = launch {
          listener.incoming().collect { conn ->
            seen++
            if (seen > idleClients.size) connDeferred.complete(conn)
          }
        }
        val (fd, ssl) = connectRawTls(bound.port)
        val serverConn = withTimeout(5_000) { connDeferred.await() }

        val ping = "PING\n".encodeToByteArray()
        ping.usePinned { pinned -> SSL_write(ssl, pinned.addressOf(0), ping.size) }
        val line = withTimeout(5_000) { serverConn.input.readUTF8Line() }
        assertEquals("PING", line)

        SSL_free(ssl)
        close(fd)
        serverConn.close()
        collectJob.cancel()

        for ((cfd, cssl) in idleClients) {
          SSL_free(cssl)
          close(cfd)
        }
      } finally {
        listener.close()
      }
    }
  }
}
