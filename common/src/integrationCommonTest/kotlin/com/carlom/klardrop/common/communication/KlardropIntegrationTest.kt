package com.carlom.klardrop.common.communication

import FakeLocalPropertiesRepository
import TestCoroutines
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.TurbineContext
import app.cash.turbine.turbineScope
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileTransfer
import com.carlom.klardrop.common.communication.MessengerSendProgress.Completed
import com.carlom.klardrop.common.communication.di.CommunicationModule
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.router.IncomingAuthorizer
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceConnection.DeviceConnectionType
import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.mdns.FakeVisibleDevices
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.trust.InMemoryTrustStorage
import com.carlom.klardrop.common.utils.Clock
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

expect fun testClipboardReaderWriter(): ClipboardReaderWriter

expect fun createTestPlatformFile(fileName: String, data: ByteArray): PlatformFile

fun FakeClipboardManager() = com.carlom.klardrop.common.features.ClipboardManager(
  coroutines = TestCoroutines(),
  readerWriter = testClipboardReaderWriter()
)

class KlardropIntegrationTest {

  private val coroutines = TestCoroutines()
  private val clock = Clock()
  private val clientVisibleDevices = FakeVisibleDevices()
  private val clientDeviceId = "client01"
  private val serverDeviceId = "server01"

  private val testContext = KlardropTestContext(
    coroutines = coroutines,
    clock = clock,
    clientVisibleDevices = clientVisibleDevices,
    clientDeviceId = clientDeviceId,
    serverDeviceId = serverDeviceId
  )

  @Test
  fun startServerAndSendTextMessage() = runTest(coroutines.dispatcher) {
    testContext.setupServerAndClient()

    turbineScope {
      with(testContext) {
        sendAndVerifyMessage("klardrop protocol test")
      }
    }
  }

  @Test
  fun testSendTwoMessagesForKlardrop() = runTest(coroutines.dispatcher, timeout = 60.seconds) {
    testContext.setupServerAndClient(DeviceConnectionType.KLARDROP)

    turbineScope(timeout = 30.seconds) {
      with(testContext) {
        sendAndVerifyMessage("This is the first message")
        sendAndVerifyMessage("This is a second message!")
      }
    }
  }

  @Test
  fun testSendTwoMessagesForNearby() = runTest(coroutines.dispatcher, timeout = 60.seconds) {
    testContext.setupServerAndClient(DeviceConnectionType.NEARBY)

    turbineScope(timeout = 30.seconds) {
      with(testContext) {
        sendAndVerifyMessage("This is the first message")
        sendAndVerifyMessage("This is a second message!")
      }
    }
  }

  @Test
  fun testMessengerReconnectionFromClient() = testMessengerReconnection(clientDropsConnection = true, serverDropsConnection = false)

  @Test
  fun testMessengerReconnectionFromServer() = testMessengerReconnection(clientDropsConnection = false, serverDropsConnection = true)

  @Test
  fun testMessengerReconnectionFromBothSides() = testMessengerReconnection(clientDropsConnection = true, serverDropsConnection = true)

  @Test
  fun testSendFileFromClientToServer() = runTest(coroutines.dispatcher, timeout = 60.seconds) {
    testContext.setupServerAndClient(DeviceConnectionType.KLARDROP)

    turbineScope(timeout = 30.seconds) {
      with(testContext) {
        val testData = ByteArray(1024) { (it % 256).toByte() }
        sendAndVerifyFile("test-document.txt", testData, "text/plain")
      }
    }
  }

  @Test
  fun testSendLargeFileRequiringMultipleChunks() = runTest(coroutines.dispatcher, timeout = 120.seconds) {
    testContext.setupServerAndClient(DeviceConnectionType.KLARDROP)

    turbineScope(timeout = 60.seconds) {
      with(testContext) {
        // 700KB ensures multiple FILE_CHUNK frames at the 256KB chunk size.
        val testData = ByteArray(700_000) { (it % 256).toByte() }
        sendAndVerifyFile("large-file.bin", testData, "application/octet-stream")
      }
    }
  }

  /**
   * Stress test for the chunked-framing change: send a 1 MB file (4 chunks at 256 KB) AND
   * pump a TextMessage between chunks AND let heartbeats run. With the old unframed-payload
   * model the writer mutex would have been held for the whole transfer and the text send
   * would have been serialized behind it; with chunked framing the text frame interleaves
   * between chunks and arrives quickly. This also exercises the writeLock contention path
   * end-to-end (heartbeat + outgoing send + incoming-reply writes all on the same socket).
   *
   * 4 chunks is the smallest size that still validates the interleaving — anything fewer
   * doesn't leave enough room between chunks for the text frame to slot in. We deliberately
   * stay under 2 MB because slow CI runners have noticeably tighter socket throughput and
   * larger transfers push real-time ACK round-trips past the virtual-time ACK timeouts.
   */
  @Test
  fun testFileTransferAllowsConcurrentTextAndHeartbeat() = runTest(coroutines.dispatcher, timeout = 180.seconds) {
    testContext.setupServerAndClient(DeviceConnectionType.KLARDROP)

    turbineScope(timeout = 120.seconds) {
      with(testContext) {
        val fileBytes = ByteArray(1 * 1024 * 1024) { (it % 256).toByte() }
        val fileName = "stress.bin"
        val platformFile = createTestPlatformFile(fileName, fileBytes)
        clientFileManager.fileDataToServe[platformFile.path] = fileBytes
        val fileMessage = FileMessage(fileName = fileName, fileSize = fileBytes.size.toLong(), mimeType = "application/octet-stream")

        val messageReceiver = serverCommunicationModule.messageReceiver()
        val clientMessenger = clientCommunicationModule.messenger()
        val receiverChannel = messageReceiver.messageReceivedNotifier.testIn(this@turbineScope)

        coroutines.dispatcher.scheduler.runCurrent()
        coroutines.dispatcher.scheduler.advanceTimeBy(100)
        coroutines.dispatcher.scheduler.runCurrent()

        // Kick off the file send first.
        val fileSendFlow = clientMessenger.send(serverDeviceId, fileMessage.toSendRequest(platformFile))
        val fileSenderChannel = fileSendFlow.testIn(this@turbineScope)

        // Give the header + first couple of chunks a chance to land before injecting the text.
        pumpVirtualAndRealTime(iterations = 4, virtualStepMs = 100, realSleepMs = 50)

        // Send a TextMessage on the same connection while the file transfer is in flight.
        val textRequest = textSendRequest("interleaved-during-transfer")
        val textSendFlow = clientMessenger.send(serverDeviceId, textRequest)
        val textSenderChannel = textSendFlow.testIn(this@turbineScope)

        // Both sends must complete; the text doesn't have to wait for the file. Use
        // awaitForPumping (not awaitFor) — the file transfer is ~8 chunks of 256 KB and
        // chunks/text/heartbeats all interleave on the same socket. Slow CI runners can't
        // finish the transfer in any fixed-size pre-pump, and a plain awaitItem suspends
        // the test dispatcher so virtual-time progress (heartbeats, ACK retries) stalls.
        textSenderChannel.awaitForPumping { it is Completed }
        fileSenderChannel.awaitForPumping { it is Completed }

        // Receiver must have observed both the text and the file (at least one Completed each).
        var sawText = false
        var sawFile = false
        while (!(sawText && sawFile)) {
          val update = receiverChannel.awaitForPumping { it.status is ReceiveMessageStatus.Completed }
          val msg = update.messages.firstOrNull()
          if (msg is TextMessage && msg.text == "interleaved-during-transfer") sawText = true
          if (msg is FileMessage && msg.fileName == fileName) sawFile = true
        }

        // File bytes arrived intact (chunk reassembly correct).
        val received = serverFileManager.receivedFiles[fileName]
        assertNotNull(received, "Server should have received file: $fileName")
        assertTrue(fileBytes.contentEquals(received), "Reassembled file must match original")

        textSenderChannel.cancelAndIgnoreRemainingEvents()
        fileSenderChannel.cancelAndIgnoreRemainingEvents()
        receiverChannel.cancelAndIgnoreRemainingEvents()
      }
    }
  }

  /**
   * Reusing the same connection for many sequential sends after a file transfer. Validates
   * the connection survives a multi-MB transfer (heartbeat doesn't kill it mid-transfer thanks
   * to writeLock-release-per-chunk) and remains usable for further messages afterwards.
   *
   * Generous timeouts (vs. 60–90s elsewhere) — on slow CI runners the 1MB transfer plus two
   * follow-up text round-trips hovers near the previous 120s/90s limits.
   */
  @Test
  fun testTextSendsAfterFileTransferReuseSameConnection() = runTest(coroutines.dispatcher, timeout = 240.seconds) {
    testContext.setupServerAndClient(DeviceConnectionType.KLARDROP)

    turbineScope(timeout = 180.seconds) {
      with(testContext) {
        val fileBytes = ByteArray(1 * 1024 * 1024) { (it % 256).toByte() }
        sendAndVerifyFile("post-transfer.bin", fileBytes, "application/octet-stream")
        sendAndVerifyMessage("after-transfer-1")
        sendAndVerifyMessage("after-transfer-2")
      }
    }
  }

  @Test
  fun testSendTextAndFileInSequence() = runTest(coroutines.dispatcher, timeout = 120.seconds) {
    testContext.setupServerAndClient(DeviceConnectionType.KLARDROP)

    turbineScope(timeout = 60.seconds) {
      with(testContext) {
        sendAndVerifyMessage("Hello before file transfer")

        val fileData = "Hello from file!".encodeToByteArray()
        sendAndVerifyFile("greeting.txt", fileData, "text/plain")

        sendAndVerifyMessage("Text after file transfer works too!")
      }
    }
  }

  @Test
  fun testAckCorrelationRaceCondition() = runTest(coroutines.dispatcher, timeout = 60.seconds) {
    // This test verifies our fix for the ACK correlation race condition
    testContext.setupServerAndClient()

    turbineScope(timeout = 30.seconds) {
      with(testContext) {
        val messageReceiver = serverCommunicationModule.messageReceiver()
        val clientMessenger = clientCommunicationModule.messenger()

        // Send multiple messages sequentially to test ACK correlation
        val messages = (1..3).map { textSendRequest("ACK correlation test message $it") }

        val receiverChannel = messageReceiver.messageReceivedNotifier.testIn(this@turbineScope)

        for ((_, message) in messages.withIndex()) {
          // Send one message at a time
          val senderFlow = clientMessenger.send(serverDeviceId, message)
          val senderChannel = senderFlow.testIn(this@turbineScope)

          // The ACK withTimeout (2s) runs on mainDispatcher = StandardTestDispatcher
          // (virtual time), while the socket roundtrip runs on ioDispatcher =
          // Dispatchers.IO (real time). A single large virtual-time advance can fire
          // the ACK timeout before the real IO has delivered the ACK. Interleave
          // small virtual-time steps with short real-time sleeps so the IO threads
          // can make progress between virtual-time timeout checks, matching the
          // pattern used by sendAndVerifyFile / testMessengerReconnection.
          pumpVirtualAndRealTime(iterations = 10, virtualStepMs = 500, realSleepMs = 100)

          // Verify send completed successfully (no ACK timeout)
          val result = senderChannel.awaitFor { it is Completed }
          assertEquals(Completed, result)

          // Verify message was received. The receiverChannel is shared across
          // iterations, so an earlier iteration's Completed event may still be
          // queued — match on message text rather than status alone, otherwise we
          // can pick up stale events from a previous send.
          val expectedText = (message.message as TextMessage).text
          val update = receiverChannel.awaitFor {
            it.status is ReceiveMessageStatus.Completed &&
              (it.messages.firstOrNull() as? TextMessage)?.text == expectedText
          }
          assertIs<ReceiveMessageStatus.Completed>(update.status)
          assertEquals(1, update.messages.size)
          assertEquals(expectedText, (update.messages.first() as TextMessage).text)

          senderChannel.cancelAndIgnoreRemainingEvents()
        }

        receiverChannel.cancelAndIgnoreRemainingEvents()
      }
    }
  }

  // simple method to parametize the test for reconnection issues. using a boolean to indicate if the client should drop the connection or the server
  @OptIn(ExperimentalTime::class)
  @Suppress("VisibleForTests")
  private fun testMessengerReconnection(clientDropsConnection: Boolean, serverDropsConnection: Boolean) =
    runTest(coroutines.dispatcher, timeout = 60.seconds) {
      testContext.setupServerAndClient()

      turbineScope(timeout = 30.seconds) {
        with(testContext) {
          // send first message between client and server
          sendAndVerifyMessage("firstMessage")

          if (clientDropsConnection) {
            dropClientConnections()
          }

          if (serverDropsConnection) {
            dropServerConnections()
          }

          // Wait a bit to ensure cleanup
          advanceToCompletion()

          // Give extra time for connection cleanup
          coroutines.dispatcher.scheduler.advanceTimeBy(500)
          coroutines.dispatcher.scheduler.runCurrent()

          val messageReceiver = serverCommunicationModule.messageReceiver()
          val clientMessenger = clientCommunicationModule.messenger()

          // Now try to send a second message - this should trigger reconnection
          val secondMessage = textSendRequest("reconnection messenger message")

          // Set up receiver before sending to avoid race conditions
          val secondReceiverChannel = messageReceiver.messageReceivedNotifier.testIn(this@turbineScope)

          // Allow receiver to properly set up
          coroutines.dispatcher.scheduler.runCurrent()
          coroutines.dispatcher.scheduler.advanceTimeBy(100)
          coroutines.dispatcher.scheduler.runCurrent()

          val secondSendFlow = clientMessenger.send(serverDeviceId, secondMessage)
          val secondSenderChannel = secondSendFlow.testIn(this@turbineScope)

          // The reconnection flow mixes virtual time (ACK withTimeout + retry backoff run on
          // mainDispatcher = StandardTestDispatcher) with real time (reconnect handshake and
          // ACK roundtrip run on ioDispatcher = Dispatchers.IO). Advancing virtual time in a
          // single big chunk fires the 2s ACK withTimeout on the retry attempt before the
          // real-time socket work has caught up, which exhausts retries and emits Error.
          // Instead, pump virtual time in small increments interleaved with short real-time
          // sleeps, matching the pattern used by sendAndVerifyFile above.
          pumpVirtualAndRealTime(iterations = 40, virtualStepMs = 250, realSleepMs = 100)

          // Wait for second message to be sent
          secondSenderChannel.awaitFor {
            it is Completed
          }

          // If it completes, verify the message was received. The receiver's pipeline also
          // has real-IO steps (read from socket, persist, notify), so pump the clock again
          // rather than relying on a single advanceUntilIdle.
          pumpVirtualAndRealTime(iterations = 10, virtualStepMs = 250, realSleepMs = 50)
          val secondCompletedUpdate = secondReceiverChannel.awaitFor { it.status is ReceiveMessageStatus.Completed }
          assertIs<ReceiveMessageStatus.Completed>(secondCompletedUpdate.status)
          assertEquals(1, secondCompletedUpdate.messages.size)
          assertEquals((secondMessage.message as TextMessage).text, (secondCompletedUpdate.messages.first() as TextMessage).text)
          secondReceiverChannel.cancelAndIgnoreRemainingEvents()

          secondSenderChannel.cancelAndIgnoreRemainingEvents()
        }
      }
    }

}

internal class InMemoryTestFileManager : FileManager {
  val receivedFiles = mutableMapOf<String, ByteArray>()
  val fileDataToServe = mutableMapOf<String, ByteArray>()

  override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer {
    return InMemoryFileTransfer(fileName)
  }

  override fun getReadStreamFrom(file: PlatformFile): RawSource {
    val data = fileDataToServe[file.path]
      ?: error("No test data registered for file: ${file.path}")
    return Buffer().apply { write(data) }
  }

  override suspend fun openFile(filePath: String): Boolean = false
  override suspend fun openUrl(url: String): Boolean = false

  inner class InMemoryFileTransfer(
    private val fileName: String
  ) : FileTransfer {
    // The production code calls bufferedSink.use { } which closes the sink after writing.
    // Buffer.close() discards data, so we copy bytes into a separate collection
    // via a RawSink wrapper that survives close().
    private val chunks = mutableListOf<ByteArray>()

    override val bufferedSink: Sink = object : RawSink {
      override fun write(source: Buffer, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
          val toRead = minOf(remaining, 8192L).toInt()
          val bytes = ByteArray(toRead)
          val read = source.readAtMostTo(bytes, 0, toRead)
          if (read <= 0) break
          chunks.add(if (read < toRead) bytes.copyOf(read) else bytes)
          remaining -= read
        }
      }
      override fun flush() {}
      override fun close() {}
    }.buffered()

    override suspend fun onTransferCompleted(): Path? {
      val totalSize = chunks.sumOf { it.size }
      val result = ByteArray(totalSize)
      var offset = 0
      for (chunk in chunks) {
        chunk.copyInto(result, offset)
        offset += chunk.size
      }
      receivedFiles[fileName] = result
      return null
    }

    override suspend fun onTransferFailed() {}
  }
}

internal class KlardropTestContext(
  val coroutines: TestCoroutines,
  private val clock: Clock,
  private val clientVisibleDevices: FakeVisibleDevices,
  private val clientDeviceId: String,
  private val serverDeviceId: String
) {

  val clientFileManager = InMemoryTestFileManager()
  val serverFileManager = InMemoryTestFileManager()

  // Auto-accepting authorizer used on both sides — these integration tests assert on
  // transport-level transfer behavior, so we bypass the per-message accept/reject prompt
  // (covered by IncomingAuthorizerTest) rather than wiring up two trust stores with
  // matching ECDSA key pairs.
  private val autoAcceptAuthorizer = object : IncomingAuthorizer(
    com.carlom.klardrop.common.trust.TrustManager(
      crypto = com.carlom.klardrop.common.trust.TrustCrypto(),
      storage = InMemoryTrustStorage(),
      clock = clock,
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(clientDeviceId)),
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

  val clientCommunicationModule = CommunicationModule(
    coroutines = coroutines,
    visibleDevices = clientVisibleDevices,
    protoBuf = ProtoBuf,
    clock = clock,
    fileManager = clientFileManager,
    currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(clientDeviceId)),
    messageRepository = FakeMessageRepository(),
    clipboardManager = FakeClipboardManager(),
    trustStorage = InMemoryTrustStorage(),
    incomingAuthorizerOverride = autoAcceptAuthorizer,
  )

  val serverCommunicationModule = CommunicationModule(
    coroutines = coroutines,
    visibleDevices = FakeVisibleDevices(),
    protoBuf = ProtoBuf,
    clock = clock,
    fileManager = serverFileManager,
    currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(serverDeviceId)),
    messageRepository = FakeMessageRepository(),
    clipboardManager = FakeClipboardManager(),
    trustStorage = InMemoryTrustStorage(),
    incomingAuthorizerOverride = autoAcceptAuthorizer,
  )

  data class ServerContext(
    val server: Server,
    val port: Int
  )


  suspend fun setupServerAndClient(clientConnectionType: DeviceConnectionType = DeviceConnectionType.KLARDROP): ServerContext {
    val server = serverCommunicationModule.server()
    val serverStatus = server.startServer()

    // Give server time to fully initialize, especially important for Nearby Share
    coroutines.dispatcher.scheduler.runCurrent()
    coroutines.dispatcher.scheduler.advanceTimeBy(200)
    coroutines.dispatcher.scheduler.runCurrent()
    coroutines.dispatcher.scheduler.advanceUntilIdle()

    when (clientConnectionType) {
      DeviceConnectionType.NEARBY -> clientVisibleDevices.addNearbyDevice(serverDeviceId, "localhost", serverStatus.port)
      DeviceConnectionType.KLARDROP -> clientVisibleDevices.addKlardropDevice(serverDeviceId, "localhost", serverStatus.port)
      DeviceConnectionType.BLE -> error("BLE is not exercised in the TCP integration harness")
    }

    // Give time for device discovery to propagate
    coroutines.dispatcher.scheduler.runCurrent()
    coroutines.dispatcher.scheduler.advanceTimeBy(100)
    coroutines.dispatcher.scheduler.runCurrent()
    coroutines.dispatcher.scheduler.advanceUntilIdle()

    return ServerContext(server, serverStatus.port)
  }

  fun advanceToCompletion() {
    coroutines.dispatcher.scheduler.runCurrent()
    coroutines.dispatcher.scheduler.advanceUntilIdle()
  }

  fun advanceTimeAndComplete(timeMs: Long) {
    coroutines.dispatcher.scheduler.runCurrent()
    coroutines.dispatcher.scheduler.advanceTimeBy(timeMs)
    coroutines.dispatcher.scheduler.runCurrent()
    coroutines.dispatcher.scheduler.advanceUntilIdle()
  }

  // Bridges virtual time (mainDispatcher = TestDispatcher) and real time (ioDispatcher =
  // Dispatchers.IO, where sockets actually run). Advancing virtual time in a single large
  // chunk fires virtual-time timeouts (e.g. ACK withTimeout) before the real IO work has a
  // chance to progress. Small virtual steps interleaved with Thread.sleep let the real
  // socket threads catch up between virtual-time events.
  fun pumpVirtualAndRealTime(iterations: Int, virtualStepMs: Long, realSleepMs: Long) {
    repeat(iterations) {
      coroutines.dispatcher.scheduler.runCurrent()
      coroutines.dispatcher.scheduler.advanceUntilIdle()
      coroutines.dispatcher.scheduler.advanceTimeBy(virtualStepMs)
      Thread.sleep(realSleepMs)
      coroutines.dispatcher.scheduler.runCurrent()
      coroutines.dispatcher.scheduler.advanceUntilIdle()
    }
  }

  @Suppress("VisibleForTests")
  suspend fun dropClientConnections() {
    val connectionsPool = clientCommunicationModule.connectionsPool()
    connectionsPool.closeAllConnections()
  }

  @Suppress("VisibleForTests")
  suspend fun dropServerConnections() {
    val connectionsPool = serverCommunicationModule.connectionsPool()
    connectionsPool.closeAllConnections()
  }

  fun textSendRequest(text: String = "klardrop protocol test"): SendMessageRequest {
    return SimpleSendMessageRequest(TextMessage("Test Title", text = text))
  }

  suspend fun <T> ReceiveTurbine<T>.awaitFor(block: ((T) -> Boolean)): T {
    var item: T
    do {
      item = awaitItem()
    } while (!block(item))
    return item
  }

  /**
   * Polls for a matching item while interleaving small virtual-time and real-time pumps. Use
   * this (instead of [awaitFor]) for end-to-end paths whose progress depends on real I/O AND
   * virtual-time scheduling — `awaitItem` suspends the test dispatcher, so virtual time stops
   * moving and any flow waiting on a virtual-time delay (heartbeats, ACK retries, progress
   * emission throttles) stalls until the surrounding `turbineScope` timeout fires.
   *
   * Implementation notes:
   * - Uses `runCurrent` (NOT `advanceUntilIdle`) to avoid chasing all scheduled delays in a
   *   single jump and firing ACK/heartbeat timeouts long before real socket I/O can advance.
   * - Default cadence is 1:1 virtual:real time. Going faster (e.g. 4× virtual) burns through
   *   the 5–10 s virtual ACK timeouts before slow CI runners have a chance to deliver the
   *   real ACK, causing spurious retries / stuck transfers.
   * - The [maxRealTimeMs] cap is real wall-clock time; pick something well under the
   *   surrounding `turbineScope`/`runTest` timeout so the failure surfaces here with a useful
   *   message instead of as a generic Turbine timeout.
   */
  suspend fun <T> ReceiveTurbine<T>.awaitForPumping(
    maxRealTimeMs: Long = 90_000,
    pollVirtualStepMs: Long = 100,
    pollRealSleepMs: Long = 100,
    block: (T) -> Boolean,
  ): T {
    val channel = asChannel()
    val deadline = System.currentTimeMillis() + maxRealTimeMs
    var seenCount = 0
    var lastSeen: Any? = null
    while (System.currentTimeMillis() < deadline) {
      coroutines.dispatcher.scheduler.runCurrent()
      coroutines.dispatcher.scheduler.advanceTimeBy(pollVirtualStepMs)
      coroutines.dispatcher.scheduler.runCurrent()
      Thread.sleep(pollRealSleepMs)
      coroutines.dispatcher.scheduler.runCurrent()

      while (true) {
        val result = channel.tryReceive()
        if (result.isSuccess) {
          val item = result.getOrThrow()
          seenCount++
          lastSeen = item
          if (block(item)) return item
        } else if (result.isClosed) {
          error(
            "Channel closed before predicate matched after $seenCount items (last=$lastSeen): " +
              "${result.exceptionOrNull()}"
          )
        } else {
          break
        }
      }
    }
    error(
      "awaitForPumping timed out after ${maxRealTimeMs}ms; saw $seenCount items, " +
        "last=$lastSeen, none matched predicate"
    )
  }


  suspend fun TurbineContext.sendAndVerifyMessage(text: String) {
    val message = textSendRequest(text)

    val messageReceiver = serverCommunicationModule.messageReceiver()
    val clientMessenger = clientCommunicationModule.messenger()

    // Set up receiver flow BEFORE initiating send to avoid race conditions
    val receiverChannel = messageReceiver.messageReceivedNotifier.testIn(this)

    // Give the receiver some time to properly set up
    coroutines.dispatcher.scheduler.runCurrent()
    coroutines.dispatcher.scheduler.advanceTimeBy(100)
    coroutines.dispatcher.scheduler.runCurrent()

    val senderFlow = clientMessenger.send(serverDeviceId, message)
    val senderChannel = senderFlow.testIn(this)

    // Interleave small virtual-time advances with real-time pauses so the
    // receiver's IO thread can deliver the ACK before the virtual ACK timeout
    // fires. See pumpVirtualAndRealTime() doc.
    pumpVirtualAndRealTime(iterations = 15, virtualStepMs = 200, realSleepMs = 100)

    // Wait for send completion
    senderChannel.awaitFor { it is Completed }

    // Verify message received
    coroutines.dispatcher.scheduler.advanceUntilIdle()
    val completedUpdate = receiverChannel.awaitFor { it.status is ReceiveMessageStatus.Completed }
    assertIs<ReceiveMessageStatus.Completed>(completedUpdate.status)
    assertEquals(1, completedUpdate.messages.size)
    assertEquals((message.message as TextMessage).text, (completedUpdate.messages.first() as TextMessage).text)

    senderChannel.cancelAndIgnoreRemainingEvents()
    receiverChannel.cancelAndIgnoreRemainingEvents()
  }

  suspend fun TurbineContext.sendAndVerifyFile(
    fileName: String,
    fileData: ByteArray,
    mimeType: String = "application/octet-stream"
  ) {
    val platformFile = createTestPlatformFile(fileName, fileData)
    clientFileManager.fileDataToServe[platformFile.path] = fileData

    val fileMessage = FileMessage(
      fileName = fileName,
      fileSize = fileData.size.toLong(),
      mimeType = mimeType
    )
    val sendRequest = fileMessage.toSendRequest(platformFile)

    val messageReceiver = serverCommunicationModule.messageReceiver()
    val clientMessenger = clientCommunicationModule.messenger()

    val receiverChannel = messageReceiver.messageReceivedNotifier.testIn(this)

    coroutines.dispatcher.scheduler.runCurrent()
    coroutines.dispatcher.scheduler.advanceTimeBy(100)
    coroutines.dispatcher.scheduler.runCurrent()

    val senderFlow = clientMessenger.send(serverDeviceId, sendRequest)
    val senderChannel = senderFlow.testIn(this)

    // See pumpVirtualAndRealTime() doc — file transfer IO happens on real threads.
    pumpVirtualAndRealTime(iterations = 15, virtualStepMs = 500, realSleepMs = 100)

    senderChannel.awaitFor { it is Completed }

    coroutines.dispatcher.scheduler.advanceUntilIdle()
    val completedUpdate = receiverChannel.awaitFor { it.status is ReceiveMessageStatus.Completed }
    assertIs<ReceiveMessageStatus.Completed>(completedUpdate.status)
    assertEquals(1, completedUpdate.messages.size)
    val receivedMessage = completedUpdate.messages.first()
    assertIs<FileMessage>(receivedMessage)
    assertEquals(fileName, receivedMessage.fileName)
    assertEquals(fileData.size.toLong(), receivedMessage.fileSize)
    assertEquals(mimeType, receivedMessage.mimeType)

    val receivedBytes = serverFileManager.receivedFiles[fileName]
    assertNotNull(receivedBytes, "Server should have received file: $fileName")
    assertTrue(fileData.contentEquals(receivedBytes), "File content should match")

    senderChannel.cancelAndIgnoreRemainingEvents()
    receiverChannel.cancelAndIgnoreRemainingEvents()
  }
}

internal class FakeMessageRepository : com.carlom.klardrop.common.persistence.MessageRepository {
  override suspend fun insertMessage(
    remoteDeviceId: String,
    content: String,
    isSender: Boolean,
    messageType: com.carlom.klardrop.common.persistence.MessageType,
    fileTransferId: Long?,
    isRead: Boolean,
    mimeType: String
  ) {
  }

  override suspend fun insertFileTransfer(
    fileName: String,
    filePath: String,
    totalSize: Long,
    status: com.carlom.klardrop.common.persistence.FileTransferStatus,
    mimeType: String
  ): Long = 1L

  override suspend fun updateFileTransferStatus(id: Long, status: com.carlom.klardrop.common.persistence.FileTransferStatus) {}
  override suspend fun updateFileTransferFilePath(id: Long, filePath: String) {}
  override suspend fun markStaleInProgressAsFailed() {}
  override suspend fun markMessagesAsRead(remoteDeviceId: String) {}
  override suspend fun getUnreadCountForDevice(remoteDeviceId: String): Long = 0L
  override fun getAllDevicesWithUnreadCounts(): kotlinx.coroutines.flow.Flow<Map<String, Long>> = kotlinx.coroutines.flow.flowOf(emptyMap())
  override fun getMessagesForDevice(
    remoteDeviceId: String,
    limit: Long
  ): kotlinx.coroutines.flow.Flow<List<com.carlom.klardrop.common.database.Messages>> = kotlinx.coroutines.flow.flowOf(emptyList())

  override fun getFileTransferById(id: Long): kotlinx.coroutines.flow.Flow<com.carlom.klardrop.common.database.File_transfers?> =
    kotlinx.coroutines.flow.flowOf(null)
}
