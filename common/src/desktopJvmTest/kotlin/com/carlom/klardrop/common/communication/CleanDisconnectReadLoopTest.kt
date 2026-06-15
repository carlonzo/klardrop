package com.carlom.klardrop.common.communication

import TestCoroutines
import com.carlom.klardrop.common.FakeMessagesRouter
import com.carlom.klardrop.common.communication.message.MessageAcknowledgment
import com.carlom.klardrop.common.communication.message.PongMessage
import com.carlom.klardrop.common.utils.isExpectedNetworkNoise
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

/**
 * Repro for a live-discovered bug.
 *
 * When a sender finishes a transfer (ACK_RECEIVED already exchanged) and simply closes its TCP
 * socket WITHOUT a protocol-level GOODBYE, the receiver's [ConnectionMessenger.acceptIncomingMessages]
 * read loop hits a real `EOFException: Channel is already closed` from the production read
 * ([ByteReadChannel.readByteArrayMessage] -> `readFully`). The live smoke logs show this surfaced as
 * an ERROR-level log with a full stack trace:
 *
 *   [Klardrop]: ConnectionMessenger: Error while listening for messages from e84b8667. Closing connection.
 *   java.io.EOFException: Channel is already closed
 *       at io.ktor.utils.io.ByteReadChannelOperationsKt.readFully(...)
 *       ...
 *
 * (See /Users/carlo/Projects/klardrop/.reliability/logs/cli-linux-smoke/nodeB-linux-listen.log.)
 *
 * This is wrong: a peer closing the socket after a completed exchange is a CLEAN/EXPECTED disconnect,
 * not a network failure. The classifier [Throwable.isExpectedNetworkNoise] already recognises this
 * exact `EOFException("Channel is already closed")` as expected noise; the read loop just doesn't
 * consult it. The fix is to reclassify an expected read-loop exit and log it quietly (no error-level
 * stack trace) instead of through the error logger.
 *
 * Observable signal: on desktopJvm the error logger ([log] -> nativeLoggerException) writes the
 * message to `System.err` and prints the throwable's full stack trace there, while the local/quiet
 * logger ([logLocal]) does not print a stack trace and the message is not framed as an error. We
 * capture `System.err` across the read-loop teardown and assert no `EOFException` stack trace and no
 * "Error while listening" error line is emitted for this expected close.
 *
 * RED on current code: the read loop unconditionally calls the error logger, so the captured stderr
 * contains the EOFException stack trace.
 */
class CleanDisconnectReadLoopTest {

  // A router whose onMessageIncoming performs the PRODUCTION read off the wire. When the peer closes
  // the socket this raises the real `EOFException: Channel is already closed` — exactly what the live
  // receiver hit — rather than a synthesised exception.
  private class ReadingRouter(
    private val serializer: MessageSerializer,
  ) : FakeMessagesRouter() {
    override suspend fun onMessageIncoming(
      fromDeviceId: String,
      writeChannel: ByteWriteChannel,
      readChannel: ByteReadChannel,
      ackCallback: (suspend (MessageAcknowledgment) -> Unit),
      pongCallback: (suspend (PongMessage) -> Unit),
      writeLock: Mutex,
      cipher: FrameCipher,
    ) {
      // Blocks on the framed read; when the peer closes mid-read this throws
      // EOFException("Channel is already closed").
      readChannel.readMessage(serializer, cipher)
    }
  }

  @Test
  fun peerCloseAfterCompletedExchangeIsNotLoggedAsError() = runBlocking(Dispatchers.IO) {
    // Sanity: the exact live exception IS classified as expected noise. This is the signal the read
    // loop should consult. (Guards the premise of the fix.)
    assertTrue(
      EOFException("Channel is already closed").isExpectedNetworkNoise(),
      "Precondition: the live 'Channel is already closed' EOF must be expected network noise",
    )

    val selectorManager = SelectorManager(Dispatchers.IO)
    val serverSocket = aSocket(selectorManager).tcp().bind("127.0.0.1", 0)
    val port = (serverSocket.localAddress as InetSocketAddress).port
    val acceptDeferred = async(Dispatchers.IO) { serverSocket.accept() }
    val clientSocket = aSocket(selectorManager).tcp().connect("127.0.0.1", port)
    val serverAccepted = acceptDeferred.await()

    val clientRead = clientSocket.openReadChannel()
    val clientWrite = clientSocket.openWriteChannel(autoFlush = true)

    val coroutines = TestCoroutines(dispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher())
    val serializer = MessageSerializer(ProtoBuf, coroutines)

    val messenger = ConnectionMessenger(
      coroutines = coroutines,
      connection = Connection.Tcp(clientSocket, "peer-clean"),
      messagesRouter = ReadingRouter(serializer),
      readChannel = clientRead,
      writeChannel = clientWrite,
      ackTimeoutMs = 5_000L,
    )

    // Capture System.err: the error logger (log(tag, msg, throwable)) prints the stack trace here;
    // the quiet local logger does not.
    val originalErr = System.err
    val captured = ByteArrayOutputStream()
    System.setErr(PrintStream(captured, true, "UTF-8"))

    try {
      // Start the receiver read loop. With no data ever sent it blocks inside the router's read.
      val readLoop = launch(Dispatchers.IO) {
        runCatching { messenger.acceptIncomingMessages() }
      }

      // Give the loop a moment to enter the blocking read.
      delay(200)

      // The "sender" finishes and closes its TCP socket WITHOUT any protocol-level GOODBYE — exactly
      // this scenario. The receiver's blocking readFully then throws EOFException.
      serverAccepted.close()

      // Wait (real time) for the read loop to observe EOF, classify, log and tear down.
      val deadline = TimeSource.Monotonic.markNow() + 5.seconds
      while (!messenger.isClosed() && deadline.hasNotPassedNow()) {
        delay(50)
      }
      readLoop.cancel()
    } finally {
      System.setErr(originalErr)
    }

    val stderr = captured.toString("UTF-8")
    // Echo for debugging when this fails.
    originalErr.println("=== captured stderr from read-loop teardown ===\n$stderr\n=== end ===")

    assertFalse(
      stderr.contains("EOFException"),
      "Peer close after a completed exchange is a CLEAN disconnect and must not log an EOFException " +
        "stack trace at error level. Captured stderr:\n$stderr",
    )
    assertFalse(
      stderr.contains("Error while listening"),
      "A clean/expected peer close must not be logged as an error ('Error while listening'). " +
        "Captured stderr:\n$stderr",
    )

    // The connection should still have been torn down cleanly.
    assertTrue(messenger.isClosed(), "Connection should be closed after the peer disconnects")

    runCatching { clientSocket.close() }
    runCatching { serverAccepted.close() }
    runCatching { serverSocket.close() }
    runCatching { selectorManager.close() }
    Unit
  }
}
