package com.carlom.klardrop.common.communication

import TestCoroutines
import com.carlom.klardrop.common.FakeMessagesRouter
import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConnectionMessengerTest {

  private val coroutines = TestCoroutines()
  private val opened = mutableListOf<AutoCloseableHandle>()

  @AfterTest
  fun tearDown() {
    opened.forEach { runCatching { it.close() } }
    opened.clear()
  }

  @Test
  fun sendThrowsIllegalStateExceptionWhenAckNeverArrives() = runTest(coroutines.dispatcher, timeout = 30.seconds) {
    val handle = openLoopbackClientSocket()
    val messenger = ConnectionMessenger(
      coroutines = coroutines,
      connection = Connection(handle.clientSocket, "peer01"),
      messagesRouter = FakeMessagesRouter(),
      readChannel = handle.readChannel,
      writeChannel = handle.writeChannel,
      ackTimeoutMs = 200L,
    )

    val progress = MutableSharedFlow<MessengerSendProgress>(extraBufferCapacity = 10)
    val request = SimpleSendMessageRequest(TextMessage(text = "hi"))

    val error = assertFailsWith<IllegalStateException> {
      messenger.send(request, progress)
    }
    assertTrue(
      error.message?.contains("ACK timeout") == true,
      "Expected ACK timeout message, got: ${error.message}",
    )
  }

  @Test
  fun sendUsesNoPayloadAckTimeoutForTextMessages() = runTest(coroutines.dispatcher, timeout = 30.seconds) {
    val handle = openLoopbackClientSocket()
    // noPayload timeout = 200ms (short), receivedAckTimeout = 30s (would never fire in this test).
    val config = AckTimeoutConfig(
      noPayloadAckTimeout = 200.milliseconds,
      receivedAckTimeout = 30.seconds,
      readyAckTimeout = 30.seconds,
    )
    val messenger = ConnectionMessenger(
      coroutines = coroutines,
      connection = Connection(handle.clientSocket, "peer01"),
      messagesRouter = FakeMessagesRouter(),
      readChannel = handle.readChannel,
      writeChannel = handle.writeChannel,
      ackTimeoutConfig = config,
    )

    val progress = MutableSharedFlow<MessengerSendProgress>(extraBufferCapacity = 10)
    val request = SimpleSendMessageRequest(TextMessage(text = "hi"))

    // If timeoutFor() picked the wrong duration, this would hang for 30s and the runTest
    // 30s overall timeout would fire instead of the IllegalStateException.
    val error = assertFailsWith<IllegalStateException> {
      messenger.send(request, progress)
    }
    assertTrue(
      error.message?.contains("ACK timeout") == true,
      "Expected ACK timeout message, got: ${error.message}",
    )
  }

  private suspend fun openLoopbackClientSocket(): LoopbackHandle {
    val selectorManager = SelectorManager(Dispatchers.IO)
    val serverSocket = aSocket(selectorManager).tcp().bind("127.0.0.1", 0)
    val port = (serverSocket.localAddress as InetSocketAddress).port

    val acceptDeferred = coroutines.appScope.async(Dispatchers.IO) { serverSocket.accept() }
    val clientSocket = aSocket(selectorManager).tcp().connect("127.0.0.1", port)
    val serverAccepted = acceptDeferred.await()

    val handle = LoopbackHandle(
      clientSocket = clientSocket,
      readChannel = clientSocket.openReadChannel(),
      writeChannel = clientSocket.openWriteChannel(autoFlush = true),
      cleanup = {
        runCatching { clientSocket.close() }
        runCatching { serverAccepted.close() }
        runCatching { serverSocket.close() }
        runCatching { selectorManager.close() }
      },
    )
    opened += handle
    return handle
  }

  private interface AutoCloseableHandle { fun close() }

  private class LoopbackHandle(
    val clientSocket: Socket,
    val readChannel: ByteReadChannel,
    val writeChannel: ByteWriteChannel,
    private val cleanup: () -> Unit,
  ) : AutoCloseableHandle {
    override fun close() = cleanup()
  }
}
