package com.carlom.klardrop.common.communication

import FakeLocalPropertiesRepository
import TestCoroutines
import com.carlom.klardrop.common.communication.di.CommunicationModule
import com.carlom.klardrop.common.communication.router.IncomingAuthorizer
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.mdns.FakeVisibleDevices
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.trust.InMemoryTrustStorage
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.utils.Clock
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Repro for the case where [Server.handleConnection]'s FIRST [ByteReadChannel.readByteArrayMessage]
 * — the protocol-detection read — has NO timeout. A peer that completes the TCP 3-way handshake
 * (so the server `accept()`s the socket) but then sends NOTHING leaves the server blocked forever
 * in `readFully(lengthBytes)`, holding the file descriptor and the per-connection coroutine for the
 * life of the process.
 *
 * The server's mirror-image client path already bounds this with `withTimeout(TCP_CONNECT_TIMEOUT_MS)`
 * (see [TCP_CONNECT_TIMEOUT_MS] usages in `Client.kt`); the server side does not. The fix is to wrap
 * the detection read in `withTimeout(TCP_CONNECT_TIMEOUT_MS)` and, on timeout, close the socket and
 * return so the handler cannot leak.
 *
 * ## How this test reproduces the leak observably
 *
 * It stands up a real [Server] (built through [CommunicationModule], exactly like
 * [KlardropIntegrationTest]) on a loopback port, then opens a raw Ktor TCP connection and sends
 * nothing at all. It then tries to read from that connection:
 *
 *  - **With the fix**: the server times out at ≈[TCP_CONNECT_TIMEOUT_MS], closes the socket, and the
 *    client's read observes EOF / a closed channel quickly (well under the test budget).
 *  - **Without the fix (current code)**: the server never times out, never closes the socket, so the
 *    client's read blocks. The outer real-time [withTimeout] below fires and the test fails — that is
 *    the RED state proving the leak.
 *
 * All socket work runs on [TestCoroutines.ioDispatcher] (`Dispatchers.IO`, REAL time), so the
 * production `withTimeout(TCP_CONNECT_TIMEOUT_MS)` (once added) fires against real wall-clock, the
 * same as in production.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerDetectionReadTimeoutTest {

  private val coroutines = TestCoroutines(dispatcher = UnconfinedTestDispatcher())
  private val clock = Clock()

  private val autoAcceptAuthorizer = object : IncomingAuthorizer(
    com.carlom.klardrop.common.trust.TrustManager(
      crypto = com.carlom.klardrop.common.trust.TrustCrypto(),
      storage = InMemoryTrustStorage(),
      clock = clock,
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("server01")),
    )
  ) {
    override suspend fun authorize(
      fromDeviceId: String,
      kind: TransferKind,
      headers: List<Message>,
      receiveFlow: MutableStateFlow<ReceiveMessageUpdate>,
      notifyAwaitingUser: suspend () -> Unit,
    ): Boolean = true
  }

  private val serverModule = CommunicationModule(
    coroutines = coroutines,
    visibleDevices = FakeVisibleDevices(),
    protoBuf = ProtoBuf,
    clock = clock,
    fileManager = InMemoryTestFileManager(),
    currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("server01")),
    messageRepository = FakeMessageRepository(),
    clipboardManager = FakeClipboardManager(),
    trustStorage = InMemoryTrustStorage(),
    incomingAuthorizerOverride = autoAcceptAuthorizer,
  )

  @Test
  fun silentPeerDoesNotStallTheDetectionReadForever() = runTest(
    coroutines.dispatcher,
    timeout = 60.seconds,
  ) {
    // Run the real socket I/O on Dispatchers.IO (real time) — the server's per-connection
    // coroutine runs there too, and the production timeout (once added) is a real-time
    // withTimeout(TCP_CONNECT_TIMEOUT_MS).
    withContext(coroutines.ioDispatcher) {
      val server = serverModule.server()
      val serverConfig = server.startServer()

      val selectorManager = SelectorManager(coroutines.ioDispatcher)
      val clientSocket = aSocket(selectorManager).tcp()
        .connect(InetSocketAddress("127.0.0.1", serverConfig.port))

      // Deliberately send NOTHING: complete the TCP handshake, then stay silent. This is exactly
      // the half-open / backlog-stalled / black-holed peer the server must defend against.
      val readChannel = clientSocket.openReadChannel()

      // Budget = TCP_CONNECT_TIMEOUT_MS (the bound the server SHOULD apply) + generous slack for a
      // contended CI runner. With the fix the server closes us at ≈TCP_CONNECT_TIMEOUT_MS, so our
      // read returns (EOF/closed) far inside this window. Without the fix the read blocks forever
      // and this withTimeout fires.
      val outerBudgetMs = TCP_CONNECT_TIMEOUT_MS + 10_000L

      val mark = TimeSource.Monotonic.markNow()
      try {
        withTimeout(outerBudgetMs) {
          // On the fixed server this throws (channel closed / premature EOF) once the server times
          // out and closes the socket — that's the cleanup we want. On the buggy server it never
          // returns because the server never closes us.
          try {
            readChannel.readByteArrayMessage()
          } catch (_: Throwable) {
            // Expected on the FIXED path: server closed the connection after its detection-read
            // timeout, so our read hits EOF. Swallow — reaching here means cleanup happened.
          }
        }
      } catch (e: TimeoutCancellationException) {
        clientSocket.close()
        selectorManager.close()
        server.stopServer()
        fail(
          "Server never closed a silent peer's connection within ${outerBudgetMs}ms. The protocol-" +
            "detection read in Server.handleConnection is not bounded by " +
            "withTimeout(TCP_CONNECT_TIMEOUT_MS) (${TCP_CONNECT_TIMEOUT_MS}ms), so a peer that " +
            "completes TCP accept but sends nothing holds the FD + coroutine forever.",
        )
      }

      val elapsedMs = mark.elapsedNow().inWholeMilliseconds
      clientSocket.close()
      selectorManager.close()
      server.stopServer()

      // Extra guard against an over-generous fix: the server must close the silent peer at roughly
      // its connect-timeout budget, not after some unrelated, much longer timeout.
      if (elapsedMs > TCP_CONNECT_TIMEOUT_MS + 7_000L) {
        fail(
          "Server eventually closed the silent peer, but only after ${elapsedMs}ms — far past " +
            "TCP_CONNECT_TIMEOUT_MS (${TCP_CONNECT_TIMEOUT_MS}ms). The detection read should be " +
            "bounded by withTimeout(TCP_CONNECT_TIMEOUT_MS).",
        )
      }
    }
  }
}
