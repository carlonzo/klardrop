package com.carlom.klardrop.common.communication

import FakeLocalPropertiesRepository
import TestCoroutines
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.TurbineContext
import app.cash.turbine.turbineScope
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileTransfer
import com.carlom.klardrop.common.communication.MessengerSendProgress.Completed
import com.carlom.klardrop.common.communication.MessengerSendProgress.Error
import com.carlom.klardrop.common.communication.di.CommunicationModule
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.router.IncomingAuthorizer
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceConnection.DeviceConnectionType
import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.mdns.FakeVisibleDevices
import com.carlom.klardrop.common.mdns.NearbyDisconnectionObserver
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.trust.InMemoryTrustStorage
import com.carlom.klardrop.common.utils.Clock
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

expect fun testClipboardReaderWriter(): ClipboardReaderWriter

expect fun createTestPlatformFile(fileName: String, data: ByteArray): PlatformFile

fun FakeClipboardManager() = com.carlom.klardrop.common.features.ClipboardManager(
  coroutines = TestCoroutines(),
  readerWriter = testClipboardReaderWriter()
)

@OptIn(ExperimentalCoroutinesApi::class)
class KlardropIntegrationTest {

  // Rebuilt per retry attempt by [integrationTest] (hence `var`): a fresh dispatcher,
  // device list and server/sockets so a failed attempt can't poison the next.
  private var coroutines = TestCoroutines(dispatcher = UnconfinedTestDispatcher())
  private val clock = Clock()
  private var clientVisibleDevices = FakeVisibleDevices()
  private val clientDeviceId = "client01"
  private val serverDeviceId = "server01"

  private var testContext = newTestContext()

  private fun newTestContext() = KlardropTestContext(
    coroutines = coroutines,
    clock = clock,
    clientVisibleDevices = clientVisibleDevices,
    clientDeviceId = clientDeviceId,
    serverDeviceId = serverDeviceId,
  )

  /**
   * Runs an end-to-end integration test with a bounded retry.
   *
   * These tests drive real loopback sockets (on [kotlinx.coroutines.Dispatchers.IO])
   * interleaved with virtual test time. On a heavily contended CI runner a transfer can
   * occasionally blow [KlardropTestContext.awaitForPumping]'s real-time budget — a
   * load-induced flake that's rare (~1/240) and does not reproduce locally. A genuinely
   * broken test still fails every attempt (so this doesn't mask real regressions); only
   * flakes are absorbed.
   *
   * Each retry rebuilds the entire fixture (fresh test dispatcher, sockets and server)
   * so a half-open connection left by a failed attempt can't poison the next one.
   */
  private fun integrationTest(
    timeout: Duration = 120.seconds,
    // 3 attempts: these real-socket + virtual-time tests flake more often on a heavily
    // contended CI runner (a single load-induced miss can recur across two tries). A genuine
    // break still fails all three, so this only absorbs flakes, it doesn't mask regressions.
    attempts: Int = 3,
    body: suspend TestScope.() -> Unit,
  ) {
    var lastError: Throwable? = null
    repeat(attempts) { attempt ->
      if (attempt > 0) {
        // Close the previous attempt's sockets before replacing it — a discarded fixture keeps
        // its selectors (and their Dispatchers.IO slots) alive forever otherwise.
        testContext.tearDown()
        coroutines = TestCoroutines(dispatcher = UnconfinedTestDispatcher())
        clientVisibleDevices = FakeVisibleDevices()
        testContext = newTestContext()
      }
      try {
        runTest(coroutines.dispatcher, timeout = timeout, testBody = body)
        testContext.tearDown()
        return
      } catch (t: Throwable) {
        lastError = t
        println(
          "integrationTest: attempt ${attempt + 1}/$attempts failed (${t.message}); " +
            if (attempt + 1 < attempts) "retrying with a fresh fixture" else "giving up",
        )
      }
    }
    testContext.tearDown()
    throw lastError!!
  }

  @Test
  fun startServerAndSendTextMessage() = integrationTest {
    testContext.setupServerAndClient()

    turbineScope {
      with(testContext) {
        sendAndVerifyMessage("klardrop protocol test")

        // A text send is one frame plus an ack, so it must not anchor anything: on Android that
        // would flash a foreground-service notification for nothing, and it would add a subscriber
        // to a progress flow most text callers never collect.
        assertEquals(
          emptyList(),
          transferAnchor.events,
          "a text send must not touch the transfer anchor",
        )
      }
    }
  }

  @Test
  fun testSendTwoMessagesForKlardrop() = integrationTest(timeout = 60.seconds) {
    testContext.setupServerAndClient(DeviceConnectionType.KLARDROP)

    turbineScope(timeout = 30.seconds) {
      with(testContext) {
        sendAndVerifyMessage("This is the first message")
        sendAndVerifyMessage("This is a second message!")
      }
    }
  }

  @Test
  fun testSendTwoMessagesForNearby() = integrationTest(timeout = 60.seconds) {
    testContext.setupServerAndClient(DeviceConnectionType.NEARBY)

    turbineScope(timeout = 30.seconds) {
      with(testContext) {
        sendAndVerifyMessage("This is the first message")
        sendAndVerifyMessage("This is a second message!")
      }
    }
  }

  // Regression for the Quick Share interop fixes: the sender path used to inject
  // a hardcoded byte payload between consent and the first file chunk, which
  // Android Quick Share could not parse. A file transfer over the Nearby
  // protocol exercises that same code path end-to-end.
  @Test
  fun testSendFileForNearby() = integrationTest(timeout = 60.seconds) {
    testContext.setupServerAndClient(DeviceConnectionType.NEARBY)

    turbineScope(timeout = 30.seconds) {
      with(testContext) {
        val testData = ByteArray(1024) { (it % 256).toByte() }
        sendAndVerifyFile("nearby-document.txt", testData, "text/plain")
      }
    }
  }

  // Multi-chunk file: with SANE_FRAME_LENGTH = 512 KiB, a 1.5 MiB file is 3
  // data chunks + 1 LAST_CHUNK trailer per file payload, plus introduction +
  // response framing. Catches any regression in chunk offset advancement,
  // payload_id reuse, or the LAST_CHUNK flag.
  @Test
  fun testSendLargeFileForNearby() = integrationTest(timeout = 120.seconds) {
    testContext.setupServerAndClient(DeviceConnectionType.NEARBY)

    turbineScope(timeout = 60.seconds) {
      with(testContext) {
        val testData = ByteArray(1_500_000) { (it % 256).toByte() }
        sendAndVerifyFile("nearby-large.bin", testData, "application/octet-stream")
      }
    }
  }

  // Non-ASCII file name: introduction's FileMetadata.name is UTF-8 on the
  // wire. Locks down that the sender doesn't truncate or mis-length it.
  @Test
  fun testSendNonAsciiFileNameForNearby() = integrationTest(timeout = 60.seconds) {
    testContext.setupServerAndClient(DeviceConnectionType.NEARBY)

    turbineScope(timeout = 30.seconds) {
      with(testContext) {
        val testData = "Köln 📱".encodeToByteArray()
        sendAndVerifyFile("Köln-📱.txt", testData, "text/plain")
      }
    }
  }

  // Regression: when every advertised Nearby endpoint refuses the connection
  // (e.g. the Android Quick Share session ended between discovery and our
  // connect attempt), the sender must emit a clean Error to the flow rather
  // than crashing the coroutine with NoSuchElementException via `.first {}`
  // on the list of failed attempts.
  @Test
  fun testNearbySendEmitsErrorWhenAllConnectionsRefuse() = integrationTest(timeout = 30.seconds) {
    // Point the fake visible device at a port nothing is listening on. The
    // OS will reject the connect with ECONNREFUSED.
    testContext.addStaleNearbyDevice(address = "127.0.0.1", port = 1)

    coroutines.dispatcher.scheduler.runCurrent()
    coroutines.dispatcher.scheduler.advanceTimeBy(100)
    coroutines.dispatcher.scheduler.runCurrent()

    turbineScope(timeout = 20.seconds) {
      val turbineScope = this
      with(testContext) {
        val clientMessenger = clientCommunicationModule.messenger()
        val senderChannel = clientMessenger.send(serverDeviceIdForTest(), textSendRequest("does-not-matter")).testIn(turbineScope)

        pumpVirtualAndRealTime(iterations = 8, virtualStepMs = 250, realSleepMs = 50)
        senderChannel.awaitForPumping { it is Error }

        senderChannel.cancelAndIgnoreRemainingEvents()
      }
    }
  }

  // Regression: at the end of a Nearby Share transfer the sender must emit a
  // DISCONNECTION OfflineFrame *before* closing the TCP socket, and must drain
  // the receiver's own DISCONNECTION back. Without the trailing DISCONNECTION
  // Android Quick Share displays a transfer error to the user even though
  // every file byte arrived. We assert this by observing the receiver-side
  // counter that is bumped whenever DISCONNECTION is decoded off the wire.
  @Test
  fun testNearbyTransferEndsWithDisconnectionExchange() = integrationTest(timeout = 60.seconds) {
    NearbyDisconnectionObserver.reset()
    testContext.setupServerAndClient(DeviceConnectionType.NEARBY)

    turbineScope(timeout = 30.seconds) {
      with(testContext) {
        sendAndVerifyFile("nearby-disconnect.bin", ByteArray(1024) { (it % 256).toByte() })
        pumpVirtualAndRealTime(iterations = 6, virtualStepMs = 250, realSleepMs = 50)
      }
    }

    // Both sides should have observed at least one peer DISCONNECTION on the
    // wire: receiver decoded the sender's, sender decoded the receiver's.
    assertTrue(
      NearbyDisconnectionObserver.observed() >= 2,
      "expected both sides to observe a peer DISCONNECTION, got ${NearbyDisconnectionObserver.observed()}"
    )
  }

  // Text first, then a file, over the same Nearby connection — exercises the
  // introduction frame carrying both text and file metadata in a single
  // session and the receiver routing each payload by its own payload_id.
  @Test
  fun testSendTextAndFileInSequenceForNearby() = integrationTest(timeout = 120.seconds) {
    testContext.setupServerAndClient(DeviceConnectionType.NEARBY)

    turbineScope(timeout = 60.seconds) {
      with(testContext) {
        sendAndVerifyMessage("hello over nearby")
        sendAndVerifyFile("after-text.bin", ByteArray(2048) { (it % 256).toByte() })
      }
    }
  }

  // A file/text received over Nearby Share never appears in the chat because the Nearby
  // receive path persists nothing to the DB. The chat list is rendered exclusively from
  // messageRepository.getMessagesForDevice (i.e. rows persisted via
  // insertMessage/insertFileTransfer). The Klardrop receive path persists via
  // TextMessageHandler.handleIncoming / FileMessageHandler.beginReceive
  // (insertMessage(isSender=false)); the Nearby receive path
  // (NearbyReceiverConnectionHandler) only writes the file to disk + emits the receiveFlow
  // and has no MessageRepository at all — so nothing is stored and the chat stays empty.
  //
  // This drives a real Nearby Share send (text then file) end-to-end over loopback sockets
  // and asserts the SERVER's repository recorded both as is_sender=false. On current code
  // the server repository receives zero inserts, so this test FAILS (RED).
  @Test
  fun testNearbyShareReceivePersistsMessagesToRepository() = integrationTest(timeout = 120.seconds) {
    testContext.setupServerAndClient(DeviceConnectionType.NEARBY)

    turbineScope(timeout = 60.seconds) {
      with(testContext) {
        // Both of these confirm (on the wire / receiveFlow) that the transfer completed.
        sendAndVerifyMessage("nearby chat text that must be persisted")

        val fileData = "nearby file body that must be persisted".encodeToByteArray()
        sendAndVerifyFile("nearby-persisted.txt", fileData, "text/plain")
      }
    }

    val repo = testContext.serverMessageRepository

    // The received FILE must have produced a file_transfer row + a FILE message persisted as
    // is_sender=false (mirroring FileMessageHandler.beginReceive on the Klardrop path).
    val fileTransfer = repo.insertedFileTransfers.firstOrNull { it.fileName == "nearby-persisted.txt" }
    assertNotNull(
      fileTransfer,
      "Nearby receive must persist a file_transfer row for the received file, " +
        "but the server repository recorded ${repo.insertedFileTransfers}",
    )

    val incomingFileMessage = repo.insertedMessages.firstOrNull {
      it.messageType == com.carlom.klardrop.common.persistence.MessageType.FILE && !it.isSender
    }
    assertNotNull(
      incomingFileMessage,
      "Nearby receive must persist the received FILE as an is_sender=false message so it " +
        "appears in the chat, but the server repository recorded ${repo.insertedMessages}",
    )

    // The received TEXT must also be persisted as is_sender=false (mirroring
    // TextMessageHandler.handleIncoming on the Klardrop path).
    val incomingTextMessage = repo.insertedMessages.firstOrNull {
      it.messageType == com.carlom.klardrop.common.persistence.MessageType.TEXT && !it.isSender
    }
    assertNotNull(
      incomingTextMessage,
      "Nearby receive must persist the received TEXT as an is_sender=false message so it " +
        "appears in the chat, but the server repository recorded ${repo.insertedMessages}",
    )
  }

  @Test
  fun testMessengerReconnectionFromClient() = testMessengerReconnection(clientDropsConnection = true, serverDropsConnection = false)

  @Test
  fun testMessengerReconnectionFromServer() = testMessengerReconnection(clientDropsConnection = false, serverDropsConnection = true)

  @Test
  fun testMessengerReconnectionFromBothSides() = testMessengerReconnection(clientDropsConnection = true, serverDropsConnection = true)

  @Test
  fun testSendFileFromClientToServer() = integrationTest(timeout = 60.seconds) {
    testContext.setupServerAndClient(DeviceConnectionType.KLARDROP)

    turbineScope(timeout = 30.seconds) {
      with(testContext) {
        val testData = ByteArray(1024) { (it % 256).toByte() }
        sendAndVerifyFile("test-document.txt", testData, "text/plain")

        // A file send must tell the platform to keep this process alive for the whole transfer:
        // on Android the anchor is a foreground service, and without it a send started in-app dies
        // the moment the user switches away. Released exactly once, or the notification pins
        // forever; released too early, or the process is free to be killed mid-stream.
        val events = transferAnchor.events
        val begins = events.filterIsInstance<RecordingTransferAnchor.Event.Begin>()
        val ends = events.filterIsInstance<RecordingTransferAnchor.Event.End>()

        assertEquals(1, begins.size, "expected exactly one anchor begin, got $events")
        assertEquals(1, ends.size, "expected exactly one anchor end, got $events")
        assertEquals("test-document.txt", begins.first().label)
        assertEquals(begins.first().transferId, ends.first().transferId)
        assertEquals(events.first(), begins.first(), "anchor begin must come first, got $events")
        assertTrue(
          events.indexOf(begins.first()) < events.indexOf(ends.first()),
          "anchor begin must precede end, got $events",
        )
        assertEquals(TransferAnchor.Direction.OUTGOING, begins.first().direction)

        // The receiving side needs the same protection, and needs it more: the user taps Accept
        // and puts the phone down, so the whole transfer runs backgrounded with the screen off.
        val receiveEvents = serverTransferAnchor.events
        val receiveBegins = receiveEvents.filterIsInstance<RecordingTransferAnchor.Event.Begin>()
        val receiveEnds = receiveEvents.filterIsInstance<RecordingTransferAnchor.Event.End>()

        assertEquals(1, receiveBegins.size, "expected exactly one receive anchor begin, got $receiveEvents")
        assertEquals(1, receiveEnds.size, "expected exactly one receive anchor end, got $receiveEvents")
        assertEquals("test-document.txt", receiveBegins.first().label)
        assertEquals(TransferAnchor.Direction.INCOMING, receiveBegins.first().direction)
        assertEquals(receiveBegins.first().transferId, receiveEnds.first().transferId)
        assertEquals(
          receiveEvents.first(), receiveBegins.first(),
          "receive anchor begin must come first — it has to cover the authorization wait too, got $receiveEvents",
        )
        assertTrue(
          receiveEvents.indexOf(receiveBegins.first()) < receiveEvents.indexOf(receiveEnds.first()),
          "receive anchor begin must precede end, got $receiveEvents",
        )
      }
    }
  }

  @Test
  fun testSendLargeFileRequiringMultipleChunks() = integrationTest(timeout = 120.seconds) {
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
  fun testFileTransferAllowsConcurrentTextAndHeartbeat() = integrationTest(timeout = 180.seconds) {
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
  fun testTextSendsAfterFileTransferReuseSameConnection() = integrationTest(timeout = 240.seconds) {
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
  fun testSendTextAndFileInSequence() = integrationTest(timeout = 120.seconds) {
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
  fun testAckCorrelationRaceCondition() = integrationTest(timeout = 60.seconds) {
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
    integrationTest(timeout = 60.seconds) {
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

          // Give both sides' real socket read loops time to observe EOF and mark stale
          // pool entries closed before the reconnection attempt starts.
          pumpVirtualAndRealTime(iterations = 10, virtualStepMs = 100, realSleepMs = 50)

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

  // Recording repository wired into the SERVER module so a test can assert what the
  // receive path persisted (the Nearby Share receive path must insert the received
  // message as is_sender=false, just like the Klardrop receive path does).
  val serverMessageRepository = RecordingMessageRepository()
  private val testAckTimeoutConfig = AckTimeoutConfig(
    noPayloadAckTimeout = 60.seconds,
    readyAckTimeout = 60.seconds,
    receivedAckTimeout = 120.seconds,
  )
  private val testHeartbeatConfig = HeartbeatConfig(enabled = false)

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

  /** Records the sending side's anchor traffic so a test can assert file sends are bracketed. */
  val transferAnchor = RecordingTransferAnchor()

  /** Same, for the receiving side — a receive has to be anchored just as a send does. */
  val serverTransferAnchor = RecordingTransferAnchor()

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
    ackTimeoutConfig = testAckTimeoutConfig,
    heartbeatConfig = testHeartbeatConfig,
    incomingAuthorizerOverride = autoAcceptAuthorizer,
    transferAnchor = transferAnchor,
  )

  val serverCommunicationModule = CommunicationModule(
    coroutines = coroutines,
    visibleDevices = FakeVisibleDevices(),
    protoBuf = ProtoBuf,
    clock = clock,
    fileManager = serverFileManager,
    currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(serverDeviceId)),
    messageRepository = serverMessageRepository,
    clipboardManager = FakeClipboardManager(),
    trustStorage = InMemoryTrustStorage(),
    ackTimeoutConfig = testAckTimeoutConfig,
    heartbeatConfig = testHeartbeatConfig,
    incomingAuthorizerOverride = autoAcceptAuthorizer,
    transferAnchor = serverTransferAnchor,
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

  /**
   * Releases the sockets this fixture opened. Every Client and every started Server owns a ktor
   * SelectorManager, and on Apple targets each live one blocks in `pselect` holding one of
   * Dispatchers.IO's 64 parallelism slots until closed. This class builds two of each and is
   * rebuilt on every retry, so without this the native suite starves that pool part-way through
   * and hangs forever — uncancellably, which is why runTest's own timeout never fired and CI hit
   * the 60-minute job limit instead.
   */
  fun tearDown() {
    clientCommunicationModule.server().stopServer()
    serverCommunicationModule.server().stopServer()
    clientCommunicationModule.client().close()
    serverCommunicationModule.client().close()
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
  // chance to progress. Small virtual steps interleaved with real dispatcher delays let the real
  // socket threads catch up between virtual-time events.
  suspend fun pumpVirtualAndRealTime(iterations: Int, virtualStepMs: Long, realSleepMs: Long) {
    repeat(iterations) {
      coroutines.dispatcher.scheduler.runCurrent()
      coroutines.dispatcher.scheduler.advanceTimeBy(virtualStepMs)
      withContext(coroutines.ioDispatcher) {
        delay(realSleepMs)
      }
      yield()
      coroutines.dispatcher.scheduler.runCurrent()
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

  /** Test hook: register a fake Nearby endpoint for the server device. */
  fun addStaleNearbyDevice(address: String, port: Int) {
    clientVisibleDevices.addNearbyDevice(serverDeviceId, address, port)
  }

  /** Test hook: expose the server device id without making the field public. */
  fun serverDeviceIdForTest(): String = serverDeviceId

  suspend fun <T> ReceiveTurbine<T>.awaitFor(block: ((T) -> Boolean)): T {
    return awaitForPumping(block = block)
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
    val deadline = TimeSource.Monotonic.markNow() + maxRealTimeMs.milliseconds
    var seenCount = 0
    var lastSeen: Any? = null
    while (deadline.hasNotPassedNow()) {
      coroutines.dispatcher.scheduler.runCurrent()
      coroutines.dispatcher.scheduler.advanceTimeBy(pollVirtualStepMs)
      coroutines.dispatcher.scheduler.runCurrent()
      withContext(coroutines.ioDispatcher) {
        delay(pollRealSleepMs)
      }
      yield()
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
    coroutines.dispatcher.scheduler.runCurrent()
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

    coroutines.dispatcher.scheduler.runCurrent()
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
    mimeType: String,
    messageId: Long?,
    sendStatus: com.carlom.klardrop.common.persistence.SendStatus,
  ): Long = 0L

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
  ): kotlinx.coroutines.flow.Flow<List<com.carlom.klardrop.common.persistence.ChatMessage>> = kotlinx.coroutines.flow.flowOf(emptyList())

  override fun getFileTransferById(id: Long): kotlinx.coroutines.flow.Flow<com.carlom.klardrop.common.database.File_transfers?> =
    kotlinx.coroutines.flow.flowOf(null)
}

/**
 * In-memory [com.carlom.klardrop.common.persistence.MessageRepository] that records every
 * inserted message and file-transfer so a test can assert what the receive path persisted.
 *
 * The chat list is rendered purely from rows persisted via [insertMessage]/[insertFileTransfer]
 * (the UI reads getMessagesForDevice, which reads the DB). So if a received transfer never
 * lands here as is_sender=false, it never shows up in the chat — this is exactly the failure
 * mode for the Nearby Share receive path.
 */
internal class RecordingMessageRepository : com.carlom.klardrop.common.persistence.MessageRepository {

  data class InsertedMessage(
    val remoteDeviceId: String,
    val content: String,
    val isSender: Boolean,
    val messageType: com.carlom.klardrop.common.persistence.MessageType,
    val fileTransferId: Long?,
    val mimeType: String,
  )

  data class InsertedFileTransfer(
    val fileName: String,
    val totalSize: Long,
    val mimeType: String,
  )

  val insertedMessages = mutableListOf<InsertedMessage>()
  val insertedFileTransfers = mutableListOf<InsertedFileTransfer>()
  private var nextFileTransferId = 1L
  private var nextMessageRowId = 1L

  override suspend fun insertMessage(
    remoteDeviceId: String,
    content: String,
    isSender: Boolean,
    messageType: com.carlom.klardrop.common.persistence.MessageType,
    fileTransferId: Long?,
    isRead: Boolean,
    mimeType: String,
    messageId: Long?,
    sendStatus: com.carlom.klardrop.common.persistence.SendStatus,
  ): Long {
    insertedMessages.add(
      InsertedMessage(
        remoteDeviceId = remoteDeviceId,
        content = content,
        isSender = isSender,
        messageType = messageType,
        fileTransferId = fileTransferId,
        mimeType = mimeType,
      )
    )
    return nextMessageRowId++
  }

  override suspend fun insertFileTransfer(
    fileName: String,
    filePath: String,
    totalSize: Long,
    status: com.carlom.klardrop.common.persistence.FileTransferStatus,
    mimeType: String
  ): Long {
    insertedFileTransfers.add(
      InsertedFileTransfer(fileName = fileName, totalSize = totalSize, mimeType = mimeType)
    )
    return nextFileTransferId++
  }

  override suspend fun updateFileTransferStatus(id: Long, status: com.carlom.klardrop.common.persistence.FileTransferStatus) {}
  override suspend fun updateFileTransferFilePath(id: Long, filePath: String) {}
  override suspend fun markStaleInProgressAsFailed() {}
  override suspend fun markMessagesAsRead(remoteDeviceId: String) {}
  override suspend fun getUnreadCountForDevice(remoteDeviceId: String): Long = 0L
  override fun getAllDevicesWithUnreadCounts(): kotlinx.coroutines.flow.Flow<Map<String, Long>> = kotlinx.coroutines.flow.flowOf(emptyMap())
  override fun getMessagesForDevice(
    remoteDeviceId: String,
    limit: Long
  ): kotlinx.coroutines.flow.Flow<List<com.carlom.klardrop.common.persistence.ChatMessage>> = kotlinx.coroutines.flow.flowOf(emptyList())

  override fun getFileTransferById(id: Long): kotlinx.coroutines.flow.Flow<com.carlom.klardrop.common.database.File_transfers?> =
    kotlinx.coroutines.flow.flowOf(null)
}
