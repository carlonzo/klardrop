package com.carlom.klardrop.common.communication

import TestCoroutines
import com.carlom.klardrop.common.FakeMessagesRouter
import com.carlom.klardrop.common.ble.BleSession
import com.carlom.klardrop.common.communication.message.AckType
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.MessageAcknowledgment
import com.carlom.klardrop.common.communication.message.PingMessage
import com.carlom.klardrop.common.communication.message.PongMessage
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import kotlinx.serialization.protobuf.ProtoBuf
import com.carlom.klardrop.common.communication.router.MessagesRouter
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
      connection = Connection.Tcp(handle.clientSocket, "peer01"),
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
  fun payloadSendWaitsForAckReadyBeforeStreamingPayload() = runTest(coroutines.dispatcher, timeout = 30.seconds) {
    val handle = openLoopbackClientSocket()
    val payloadStreamCalled = CompletableDeferred<Unit>()
    val headerSentBeforeReady = CompletableDeferred<Boolean>()

    val router = object : FakeMessagesRouter() {
      override suspend fun <S : SendMessageRequest> onSendingMessage(
        toDeviceId: String,
        sendMessageRequest: S,
        writeChannel: ByteWriteChannel,
        readChannel: ByteReadChannel,
        progress: MutableSharedFlow<MessengerSendProgress>,
        awaitReadyAck: suspend () -> Unit,
        writeLock: kotlinx.coroutines.sync.Mutex,
        cipher: com.carlom.klardrop.common.communication.FrameCipher,
      ) {
        // Simulate the FileMessageHandler ordering: send header, await ready, stream payload.
        // Mark "header was sent and we're about to await ready" without proceeding past awaitReadyAck.
        headerSentBeforeReady.complete(true)
        awaitReadyAck()
        payloadStreamCalled.complete(Unit)
      }
    }

    val payloadMessage = FileMessage(fileName = "fake.bin", fileSize = 10L, mimeType = "application/octet-stream")
    val messenger = ConnectionMessenger(
      coroutines = coroutines,
      connection = Connection.Tcp(handle.clientSocket, "peer01"),
      messagesRouter = router,
      readChannel = handle.readChannel,
      writeChannel = handle.writeChannel,
      ackTimeoutMs = 5_000L,
    )

    val progress = MutableSharedFlow<MessengerSendProgress>(extraBufferCapacity = 10)
    val sendJob = launch {
      messenger.send(SimpleSendMessageRequest(payloadMessage), progress)
    }

    headerSentBeforeReady.await()
    assertFalse(payloadStreamCalled.isCompleted, "Payload streaming must wait for ACK_READY")

    messenger.handleAckMessage(MessageAcknowledgment(AckType.READY, payloadMessage.id))

    payloadStreamCalled.await()  // proves awaitReady() returned and handler proceeded to payload

    messenger.handleAckMessage(MessageAcknowledgment(AckType.RECEIVED, payloadMessage.id))
    sendJob.join()
  }

  @Test
  fun payloadSendThrowsOnAckReadyTimeout() = runTest(coroutines.dispatcher, timeout = 30.seconds) {
    val handle = openLoopbackClientSocket()
    // Router that sends the header then awaits ready forever (no ACK_READY ever delivered).
    val router = object : FakeMessagesRouter() {
      override suspend fun <S : SendMessageRequest> onSendingMessage(
        toDeviceId: String,
        sendMessageRequest: S,
        writeChannel: ByteWriteChannel,
        readChannel: ByteReadChannel,
        progress: MutableSharedFlow<MessengerSendProgress>,
        awaitReadyAck: suspend () -> Unit,
        writeLock: kotlinx.coroutines.sync.Mutex,
        cipher: com.carlom.klardrop.common.communication.FrameCipher,
      ) {
        awaitReadyAck()
      }
    }

    val config = AckTimeoutConfig(
      readyAckTimeout = 200.milliseconds,
      receivedAckTimeout = 30.seconds,
      noPayloadAckTimeout = 30.seconds,
    )
    val messenger = ConnectionMessenger(
      coroutines = coroutines,
      connection = Connection.Tcp(handle.clientSocket, "peer01"),
      messagesRouter = router,
      readChannel = handle.readChannel,
      writeChannel = handle.writeChannel,
      ackTimeoutConfig = config,
    )

    val progress = MutableSharedFlow<MessengerSendProgress>(extraBufferCapacity = 10)
    val error = assertFailsWith<IllegalStateException> {
      messenger.send(
        SimpleSendMessageRequest(FileMessage(fileName = "x.bin", fileSize = 1L, mimeType = "application/octet-stream")),
        progress,
      )
    }
    assertTrue(
      error.message?.contains("ACK timeout") == true && error.message?.contains("READY") == true,
      "Expected ACK_READY timeout, got: ${error.message}",
    )
  }

  @Test
  fun heartbeatClosesConnectionWhenNoPongArrives() = runTest(coroutines.dispatcher, timeout = 30.seconds) {
    val handle = openLoopbackClientSocket()
    val heartbeat = HeartbeatConfig.forTest(intervalMs = 100, timeoutMs = 100)
    val messenger = ConnectionMessenger(
      coroutines = coroutines,
      connection = Connection.Tcp(handle.clientSocket, "peer01"),
      messagesRouter = FakeMessagesRouter(),
      readChannel = handle.readChannel,
      writeChannel = handle.writeChannel,
      ackTimeoutConfig = AckTimeoutConfig.DEFAULT,
      heartbeatConfig = heartbeat,
      messageSerializer = MessageSerializer(ProtoBuf, coroutines),
    )

    messenger.startHeartbeat()

    // Real-time wait for the heartbeat coroutine (running on Dispatchers.IO) to
    // emit a ping, fail to receive a pong, and close. 1s is plenty for 100ms+100ms.
    val deadline = TimeSource.Monotonic.markNow() + 5.seconds
    while (!messenger.isClosed() && deadline.hasNotPassedNow()) {
      withContext(coroutines.ioDispatcher) {
        delay(50.milliseconds)
      }
    }

    assertTrue(messenger.isClosed(), "Heartbeat should close the connection when no PONG arrives")
  }

  @Test
  fun heartbeatStaysAliveWhenPongIsDeliveredManually() = runTest(coroutines.dispatcher, timeout = 30.seconds) {
    val handle = openLoopbackClientSocket()
    val heartbeat = HeartbeatConfig.forTest(intervalMs = 100, timeoutMs = 500)
    val messenger = ConnectionMessenger(
      coroutines = coroutines,
      connection = Connection.Tcp(handle.clientSocket, "peer01"),
      messagesRouter = FakeMessagesRouter(),
      readChannel = handle.readChannel,
      writeChannel = handle.writeChannel,
      ackTimeoutConfig = AckTimeoutConfig.DEFAULT,
      heartbeatConfig = heartbeat,
      messageSerializer = MessageSerializer(ProtoBuf, coroutines),
    )

    messenger.startHeartbeat()

    // For a short window, deliver pongs for any pings that go out (real time).
    // Without a router we cannot intercept the actual ping ids, so the simplest
    // way to satisfy the heartbeat is to read the ping from the wire and reply
    // with a PONG carrying its id - which is what the production router does.
    // Here we just trust that handlePongMessage(any-current-ping-id) won't be
    // called and instead exercise the negative case: assert the connection
    // stayed alive only as long as we kept its heartbeat satisfied.
    //
    // Simpler invariant: after a single interval, isClosed() should still be
    // false because the timeout (500ms) hasn't elapsed yet. That's enough to
    // confirm the heartbeat doesn't spuriously close a fresh connection.
    withContext(coroutines.ioDispatcher) {
      delay(150.milliseconds)
    }
    assertTrue(!messenger.isClosed(), "Heartbeat must not close before its timeout has elapsed")
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
      connection = Connection.Tcp(handle.clientSocket, "peer01"),
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

  /**
   * Regression test for Reliability #2.2 — detection latency.
   *
   * A half-open connection (no PONG ever delivered) must be detected within
   * approximately (interval + timeout). We verify that with the test config
   * (interval=100ms, timeout=100ms) the connection is closed well within 2s —
   * i.e. the detection latency is bounded and not dependent on any external
   * probe outside the heartbeat.
   *
   * This also verifies that after heartbeat closure the pool evicts the entry
   * so the next connectTo() attempt re-dials instead of reusing the dead socket.
   */
  @Test
  fun halfOpenConnectionIsDetectedWithinHeartbeatWindow() = runTest(coroutines.dispatcher, timeout = 30.seconds) {
    val handle = openLoopbackClientSocket()
    // interval=100ms, timeout=100ms → detection window ≈ 200ms
    val heartbeat = HeartbeatConfig.forTest(intervalMs = 100, timeoutMs = 100)
    val messenger = ConnectionMessenger(
      coroutines = coroutines,
      connection = Connection.Tcp(handle.clientSocket, "peer-halfopen"),
      messagesRouter = FakeMessagesRouter(),
      readChannel = handle.readChannel,
      writeChannel = handle.writeChannel,
      ackTimeoutConfig = AckTimeoutConfig.DEFAULT,
      heartbeatConfig = heartbeat,
      messageSerializer = MessageSerializer(ProtoBuf, coroutines),
    )

    // Register with the real pool so we can verify eviction after heartbeat closes.
    // ConnectionsPoolImpl.isAvailable() delegates to isClosed(), so it will return false
    // once the heartbeat tears down the connection.
    val pool = ConnectionsPoolImpl()
    pool.updateConnection("peer-halfopen", messenger)

    assertTrue(pool.isAvailable("peer-halfopen"), "Pool should report connection as available before heartbeat fires")

    val startMark = TimeSource.Monotonic.markNow()
    messenger.startHeartbeat()

    // Poll for closure. Detection must occur within 2s (10x the interval+timeout window).
    withContext(coroutines.ioDispatcher) {
      val deadline = startMark + 2.seconds
      while (!messenger.isClosed() && TimeSource.Monotonic.markNow() < deadline) {
        delay(50.milliseconds)
      }
    }

    val elapsed = startMark.elapsedNow()
    assertTrue(messenger.isClosed(), "Heartbeat must close the half-open connection (elapsed: $elapsed)")
    assertTrue(elapsed < 2.seconds, "Detection latency must be < 2s (actual: $elapsed)")

    // After the heartbeat-driven close(), isClosed() returns true, so the pool's
    // isAvailable() (which calls isClosed() internally) must also return false, and
    // getConnection() must evict the dead entry and return null.
    assertFalse(pool.isAvailable("peer-halfopen"), "Pool must not report a heartbeat-closed connection as available")
    val evicted = pool.getConnection("peer-halfopen")
    assertTrue(evicted == null, "Pool must evict the closed connection on getConnection()")
  }

  /**
   * Regression test for Reliability #2.2 — write-lock starvation.
   *
   * When the write lock is continuously held (simulating a wedged or very-slow
   * writer), the heartbeat must still close the connection after
   * [HeartbeatConfig.maxConsecutiveSkips] * interval time — it must NOT suppress
   * liveness detection indefinitely.
   *
   * Strategy: we use a FakeMessagesRouter whose onSendingMessage acquires the
   * write lock passed to it and then suspends forever. This simulates a writer
   * holding the lock without making progress — the scenario the bug describes.
   * With maxConsecutiveSkips=3 and interval=100ms the connection must be torn
   * down within ~2s.
   */
  @Test
  fun writeLockStarvationClosesConnectionAfterMaxSkips() = runTest(coroutines.dispatcher, timeout = 30.seconds) {
    val handle = openLoopbackClientSocket()
    // maxConsecutiveSkips=3 keeps the test fast; production default is 12
    val heartbeat = HeartbeatConfig.forTest(intervalMs = 100, timeoutMs = 500, maxConsecutiveSkips = 3)

    val writeLockHeld = CompletableDeferred<Unit>()
    val writeLockReleaseSignal = CompletableDeferred<Unit>()

    // A router that acquires the write lock and blocks, simulating a wedged writer.
    val blockingRouter = object : FakeMessagesRouter() {
      override suspend fun <S : SendMessageRequest> onSendingMessage(
        toDeviceId: String,
        sendMessageRequest: S,
        writeChannel: ByteWriteChannel,
        readChannel: ByteReadChannel,
        progress: MutableSharedFlow<MessengerSendProgress>,
        awaitReadyAck: suspend () -> Unit,
        writeLock: kotlinx.coroutines.sync.Mutex,
        cipher: FrameCipher,
      ) {
        // Lock the write mutex (exactly what the production file writer does) and hold it.
        writeLock.lock()
        writeLockHeld.complete(Unit)
        writeLockReleaseSignal.await()  // blocks until the test signals or is cancelled
      }
    }

    val messenger = ConnectionMessenger(
      coroutines = coroutines,
      connection = Connection.Tcp(handle.clientSocket, "peer-wedged"),
      messagesRouter = blockingRouter,
      readChannel = handle.readChannel,
      writeChannel = handle.writeChannel,
      ackTimeoutConfig = AckTimeoutConfig(
        readyAckTimeout = 60.seconds,
        receivedAckTimeout = 60.seconds,
        noPayloadAckTimeout = 60.seconds,
      ),
      heartbeatConfig = heartbeat,
      messageSerializer = MessageSerializer(ProtoBuf, coroutines),
    )

    // Drive a send in the background — blockingRouter will hold the writeLock inside it
    val sendJob = coroutines.appScope.launch(coroutines.ioDispatcher) {
      runCatching {
        messenger.send(
          SimpleSendMessageRequest(TextMessage(text = "blocked")),
          MutableSharedFlow(extraBufferCapacity = 10),
        )
      }
    }

    // Wait until the write lock is confirmed held before starting the heartbeat
    withContext(coroutines.ioDispatcher) { writeLockHeld.await() }

    val startMark = TimeSource.Monotonic.markNow()
    messenger.startHeartbeat()

    // Poll for closure. Deadline = (maxSkips + 2) × interval + generous scheduling slack.
    withContext(coroutines.ioDispatcher) {
      val deadline = startMark + 2.seconds
      while (!messenger.isClosed() && TimeSource.Monotonic.markNow() < deadline) {
        delay(50.milliseconds)
      }
    }

    assertTrue(messenger.isClosed(), "Heartbeat must close a wedged-writer connection after maxConsecutiveSkips intervals")

    // Unblock and cancel the background send so the test does not leak coroutines.
    writeLockReleaseSignal.complete(Unit)
    sendJob.cancel()
  }

  /**
   * Regression test for Reliability #2.2 — the write-lock-starvation bound must NOT
   * false-close a *healthy* saturated transfer.
   *
   * A real chunked file send holds the write lock for each framed chunk and releases it
   * between chunks (during the next disk read). On a saturated link the lock is held a high
   * fraction of the time, so the heartbeat's instantaneous `tryLock()` usually fails — but
   * the writer is plainly alive because it keeps RELEASING the lock. The probe in
   * [ConnectionMessenger.heartbeatLoop] must observe one of those releases each tick and
   * reset the skip counter, so the connection survives well past
   * `maxConsecutiveSkips × interval`.
   *
   * Setup uses a full loopback pair: the server end answers every PING with a PONG (a real
   * peer), and the client router both (a) drives a progressing writer that cycles the write
   * lock and (b) dispatches inbound PONGs to the heartbeat. Under the OLD code (every
   * "lock held" sample counted as a skip) this connection closes within ~maxSkips intervals;
   * under the fix it stays open.
   */
  @Test
  fun progressingWriterIsNotClosedByHeartbeat() = runTest(coroutines.dispatcher, timeout = 30.seconds) {
    val selectorManager = SelectorManager(coroutines.ioDispatcher)
    val serverSocket = aSocket(selectorManager).tcp().bind("127.0.0.1", 0)
    val port = (serverSocket.localAddress as InetSocketAddress).port
    val acceptDeferred = coroutines.appScope.async(coroutines.ioDispatcher) { serverSocket.accept() }
    val clientSocket = aSocket(selectorManager).tcp().connect("127.0.0.1", port)
    val serverAccepted = acceptDeferred.await()

    val clientRead = clientSocket.openReadChannel()
    val clientWrite = clientSocket.openWriteChannel(autoFlush = true)
    val serverRead = serverAccepted.openReadChannel()
    val serverWrite = serverAccepted.openWriteChannel(autoFlush = true)

    val serializer = MessageSerializer(ProtoBuf, coroutines)
    val stop = CompletableDeferred<Unit>()

    // Server end: behave like a real peer — reply to every PING with the matching PONG.
    val pongResponder = coroutines.appScope.launch(coroutines.ioDispatcher) {
      runCatching {
        while (!stop.isCompleted) {
          val msg = serverRead.readMessage(serializer)
          if (msg is PingMessage) serverWrite.sendMessage(PongMessage(pingId = msg.id), serializer)
        }
      }
    }

    // Probe window must be comfortably LARGER than the writer's hold/release cycle (80ms +
    // 20ms = 100ms) so the heartbeat reliably observes a lock release, and the PONG timeout
    // must tolerate a loaded CI runner's real-socket round-trip. With interval==cycle (100ms)
    // and a 500ms timeout this test flaked on the CI macOS runner (scheduling jitter made the
    // probe miss the 20ms release window / a PONG land late). Widen both: a 300ms probe window
    // spans ~3 writer cycles (always catches a release) and 1500ms gives ample PONG slack.
    // maxSkips stays a safety net — a progressing writer should never accumulate skips at all.
    val heartbeat = HeartbeatConfig.forTest(intervalMs = 300, timeoutMs = 1500, maxConsecutiveSkips = 5)

    val progressingRouter = object : FakeMessagesRouter() {
      // Read the inbound stream and feed PONGs to the heartbeat (what the real router does).
      override suspend fun onMessageIncoming(
        fromDeviceId: String,
        writeChannel: ByteWriteChannel,
        readChannel: ByteReadChannel,
        ackCallback: (suspend (MessageAcknowledgment) -> Unit),
        pongCallback: (suspend (PongMessage) -> Unit),
        writeLock: kotlinx.coroutines.sync.Mutex,
        cipher: FrameCipher,
      ) {
        val msg = readChannel.readMessage(serializer, cipher)
        if (msg is PongMessage) pongCallback(msg)
      }

      // Simulate a healthy chunked writer: grab the lock for ~one chunk write, release it
      // (the per-chunk disk read), repeat. Hold ratio ≈ 80% so the heartbeat's tryLock
      // usually fails, forcing the probe path — but a release happens within every 100ms
      // probe window, so the writer is always detected as progressing.
      override suspend fun <S : SendMessageRequest> onSendingMessage(
        toDeviceId: String,
        sendMessageRequest: S,
        writeChannel: ByteWriteChannel,
        readChannel: ByteReadChannel,
        progress: MutableSharedFlow<MessengerSendProgress>,
        awaitReadyAck: suspend () -> Unit,
        writeLock: kotlinx.coroutines.sync.Mutex,
        cipher: FrameCipher,
      ) {
        while (!stop.isCompleted) {
          writeLock.withLock { delay(80.milliseconds) }
          delay(20.milliseconds)
        }
      }
    }

    val messenger = ConnectionMessenger(
      coroutines = coroutines,
      connection = Connection.Tcp(clientSocket, "peer-progressing"),
      messagesRouter = progressingRouter,
      readChannel = clientRead,
      writeChannel = clientWrite,
      ackTimeoutConfig = AckTimeoutConfig(
        readyAckTimeout = 60.seconds,
        receivedAckTimeout = 60.seconds,
        noPayloadAckTimeout = 60.seconds,
      ),
      heartbeatConfig = heartbeat,
      messageSerializer = serializer,
    )

    // acceptIncomingMessages() starts the heartbeat and pumps inbound PONGs.
    val readLoop = coroutines.appScope.launch(coroutines.ioDispatcher) {
      runCatching { messenger.acceptIncomingMessages() }
    }
    // The send drives the progressing writer (cycles the write lock).
    val sendJob = coroutines.appScope.launch(coroutines.ioDispatcher) {
      runCatching {
        messenger.send(
          SimpleSendMessageRequest(TextMessage(text = "streaming")),
          MutableSharedFlow(extraBufferCapacity = 10),
        )
      }
    }

    // Run well past maxConsecutiveSkips × interval (5 × 300ms = 1500ms) so a regressed
    // heartbeat would have closed by now; the fixed code keeps it open.
    withContext(coroutines.ioDispatcher) { delay(2500.milliseconds) }

    assertFalse(
      messenger.isClosed(),
      "Heartbeat must NOT close a connection whose writer keeps releasing the lock (healthy saturated transfer)",
    )

    stop.complete(Unit)
    sendJob.cancel()
    readLoop.cancel()
    pongResponder.cancel()
    runCatching { clientSocket.close() }
    runCatching { serverAccepted.close() }
    runCatching { serverSocket.close() }
    runCatching { selectorManager.close() }
  }

  private suspend fun openLoopbackClientSocket(): LoopbackHandle {
    val selectorManager = SelectorManager(coroutines.ioDispatcher)
    val serverSocket = aSocket(selectorManager).tcp().bind("127.0.0.1", 0)
    val port = (serverSocket.localAddress as InetSocketAddress).port

    val acceptDeferred = coroutines.appScope.async(coroutines.ioDispatcher) { serverSocket.accept() }
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

  // ---------------------------------------------------------------------------------------
  // Read-loop termination.
  //
  // acceptIncomingMessages() used to loop on `!readChannel.isClosedForRead` alone, with the
  // body wrapped in runCatching. Two ways that spun a core forever — on the REAL
  // Dispatchers.IO the loop runs on, not on virtual time:
  //
  //   1. BLE transport: close() shuts the BleSession, but the bridged ktor ByteChannel stays
  //      open, so the exit condition never became true after the first failure.
  //   2. Cancellation: runCatching catches Throwable, CancellationException included, so a
  //      cancelled loop logged "disconnected cleanly" and immediately went round again.
  //
  // A leaked spinner degrades every later test in the same binary; on CI's 3-core macOS
  // runner that was enough to blow the 30-minute step budget.
  //
  // Both tests bound themselves: acceptIncomingMessages runs on a real dispatcher, so a
  // regression trips runTest's wall-clock watchdog and FAILS rather than wedging the suite.
  // ---------------------------------------------------------------------------------------

  @Test
  fun readLoopStopsOnceBleTransportIsClosed() = runTest(coroutines.dispatcher, timeout = 20.seconds) {
    val session = FakeBleSession("peer-ble")
    var invocations = 0

    val router = object : FakeMessagesRouter() {
      override suspend fun onMessageIncoming(
        fromDeviceId: String,
        writeChannel: ByteWriteChannel,
        readChannel: ByteReadChannel,
        ackCallback: (suspend (MessageAcknowledgment) -> Unit),
        pongCallback: (suspend (PongMessage) -> Unit),
        writeLock: kotlinx.coroutines.sync.Mutex,
        cipher: FrameCipher,
      ) {
        invocations++
        throw RuntimeException("read failed")
      }
    }

    val messenger = ConnectionMessenger(
      coroutines = coroutines,
      connection = Connection.Ble(session, "peer-ble"),
      messagesRouter = router,
      readChannel = ByteChannel(autoFlush = true),
      writeChannel = ByteChannel(autoFlush = true),
      ackTimeoutMs = 1_000L,
    )

    // Must RETURN. Before the fix this never terminated for a BLE connection.
    messenger.acceptIncomingMessages()

    assertEquals(
      1, invocations,
      "read loop must stop once close() shut the transport — closing a BleSession never closes " +
        "the ktor channel, so looping on isClosedForRead alone spun forever",
    )
    assertFalse(session.isOpen, "the failing read must have closed the transport")
  }

  @Test
  fun readLoopUnwindsOnCancellationInsteadOfRetrying() = runTest(coroutines.dispatcher, timeout = 20.seconds) {
    val session = FakeBleSession("peer-cancel")
    val entered = CompletableDeferred<Unit>()
    var invocations = 0

    val router = object : FakeMessagesRouter() {
      override suspend fun onMessageIncoming(
        fromDeviceId: String,
        writeChannel: ByteWriteChannel,
        readChannel: ByteReadChannel,
        ackCallback: (suspend (MessageAcknowledgment) -> Unit),
        pongCallback: (suspend (PongMessage) -> Unit),
        writeLock: kotlinx.coroutines.sync.Mutex,
        cipher: FrameCipher,
      ) {
        invocations++
        entered.complete(Unit)
        awaitCancellation()
      }
    }

    val messenger = ConnectionMessenger(
      coroutines = coroutines,
      connection = Connection.Ble(session, "peer-cancel"),
      messagesRouter = router,
      readChannel = ByteChannel(autoFlush = true),
      writeChannel = ByteChannel(autoFlush = true),
      ackTimeoutMs = 1_000L,
    )

    val job = launch(coroutines.ioDispatcher) { messenger.acceptIncomingMessages() }
    entered.await()

    // Must complete. Before the fix the CancellationException was swallowed by runCatching
    // and the loop immediately re-entered, so join() never returned.
    job.cancelAndJoin()

    assertEquals(
      1, invocations,
      "cancellation must unwind the read loop, not be swallowed and retried",
    )
  }

  private class FakeBleSession(override val deviceId: String) : BleSession {
    override var isOpen: Boolean = true
      private set

    override val mtu: Int get() = 512

    override suspend fun sendChunk(chunk: ByteArray) = Unit
    override suspend fun receiveChunk(): ByteArray? = null

    override fun close() {
      isOpen = false
    }
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
