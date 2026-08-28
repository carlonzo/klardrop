package com.carlom.klardrop.common.communication

import FakeLocalPropertiesRepository
import TestCoroutines
import app.cash.turbine.turbineScope
import com.carlom.klardrop.common.communication.MessengerSendProgress.Completed
import com.carlom.klardrop.common.communication.MessengerSendProgress.Error
import com.carlom.klardrop.common.communication.di.CommunicationModule
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.router.IncomingAuthorizer
import com.carlom.klardrop.common.database.AppDatabase
import com.carlom.klardrop.common.database.createTestDriver
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.mdns.FakeVisibleDevices
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageRepositoryImpl
import com.carlom.klardrop.common.persistence.SendStatus
import com.carlom.klardrop.common.persistence.MessageType as PersistenceMessageType
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.trust.InMemoryTrustStorage
import com.carlom.klardrop.common.utils.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Test-first coverage for the sent-status fix (docs/connection-review.md F12/F13, matrix
 * entries V6/V7): exactly ONE row is persisted per logical outgoing TEXT send, however many
 * times Messenger's retry loop re-attempts the transport. It starts SENDING (persisted before
 * any socket write or ACK) and flips to its terminal state exactly once: SENT on ACK_RECEIVED,
 * FAILED once retries are exhausted.
 *
 * These drive the REAL client-side `Messenger` (via [CommunicationModule], same harness as
 * [MessagesRouterReliabilityTest]) against a real SQLDelight-backed [MessageRepository], so the
 * assertions land on the actual DB rows the client would persist — not a hand-rolled model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SentStatusReliabilityTest {

  private var coroutines = TestCoroutines(dispatcher = UnconfinedTestDispatcher())
  private var clock = Clock()
  private val clientDeviceId = "clientCC"
  private val serverDeviceId = "serverDD"

  /** Short ACK timeouts + bounded retries so a retry-to-exhaustion scenario plays out quickly. */
  private val fastAckConfig = AckTimeoutConfig(
    noPayloadAckTimeout = 2.seconds,
    readyAckTimeout = 2.seconds,
    receivedAckTimeout = 2.seconds,
    userResponseTimeout = 2.seconds,
    maxRetries = 2,
    retryBackoffMultiplier = 1.0,
    connectionWaitTimeout = 1.seconds,
  )

  private fun runReliabilityTest(
    timeout: Duration = 90.seconds,
    body: suspend TestScope.(ctx: Ctx) -> Unit,
  ) {
    coroutines = TestCoroutines(dispatcher = UnconfinedTestDispatcher())
    clock = Clock()
    runTest(coroutines.dispatcher, timeout = timeout) {
      val ctx = Ctx()
      try {
        body(ctx)
      } finally {
        ctx.tearDown()
      }
    }
  }

  @Test
  fun happyPath_singlePersistedRow_sendingThenSent() = runReliabilityTest {
    val ctx = it
    ctx.setupServerAndClient()

    turbineScope(timeout = 60.seconds) {
      val clientMessenger = ctx.clientCommunicationModule.messenger()
      val sendRequest = SimpleSendMessageRequest(TextMessage(text = "hello there"))

      val senderChannel = clientMessenger.send(serverDeviceId, sendRequest).testIn(this)
      val terminal = ctx.awaitTerminal(senderChannel)

      assertTrue(terminal is Completed, "happy-path send should complete, was $terminal")

      val rows = ctx.clientRows(serverDeviceId)
      assertEquals(1, rows.size, "exactly one row must be persisted for the whole send")
      assertEquals(null, rows.first().send_status, "the row must end SENT (send_status NULL)")

      assertEquals(
        listOf("insertMessage(SENDING)", "updateMessageSendStatus(SENT)"),
        ctx.repositoryCallKinds(),
        "must insert once as SENDING then flip once to SENT — no duplicate rows",
      )

      senderChannel.cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun ackNeverArrives_retriesExhausted_singleRowEndsFailed() = runReliabilityTest {
    val ctx = it
    // Asymmetric trust: the server replies to the signed TEXT with a revocation but NO transfer
    // ACK at all (see MessagesRouterReliabilityTest for the same recipe), so the sender's
    // RECEIVED wait times out and handleKlardropTransfer retries to exhaustion (maxRetries=2 ->
    // 3 total delivery attempts) purely on ACK timeouts — not a fast decline.
    ctx.trustServerOnClient()
    ctx.setupServerAndClient()

    turbineScope(timeout = 60.seconds) {
      val clientMessenger = ctx.clientCommunicationModule.messenger()
      val sendRequest = SimpleSendMessageRequest(TextMessage(text = "never-acked"))

      val senderChannel = clientMessenger.send(serverDeviceId, sendRequest).testIn(this)
      val terminal = ctx.awaitTerminal(senderChannel)

      assertTrue(terminal is Error, "retries-exhausted send should end in Error, was $terminal")

      val rows = ctx.clientRows(serverDeviceId)
      assertEquals(
        1,
        rows.size,
        "the retry loop must not persist a duplicate row per attempt (F13); got: $rows",
      )
      assertEquals("FAILED", rows.first().send_status, "the single row must end FAILED, never SENT")

      assertEquals(
        listOf("insertMessage(SENDING)", "updateMessageSendStatus(FAILED)"),
        ctx.repositoryCallKinds(),
        "must insert exactly once regardless of how many retries ran",
      )

      senderChannel.cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun deviceNotVisible_singleRowPersistedAndEndsFailed() = runReliabilityTest {
    val ctx = it
    // Deliberately skip ctx.setupServerAndClient(): serverDeviceId is never added to the
    // client's visible-devices fake, reproducing a peer that drops out of the mDNS visible set
    // between the user hitting send and Messenger.send's coroutine actually running. Regression
    // coverage for the "not visible" early-return silently dropping the typed message with zero
    // persisted rows (docs/connection-review.md F12/F13 follow-up).
    turbineScope(timeout = 10.seconds) {
      val clientMessenger = ctx.clientCommunicationModule.messenger()
      val sendRequest = SimpleSendMessageRequest(TextMessage(text = "offline peer"))

      val senderChannel = clientMessenger.send(serverDeviceId, sendRequest).testIn(this)
      val terminal = ctx.awaitTerminal(senderChannel)

      assertTrue(terminal is Error, "send to a not-visible device should end in Error, was $terminal")

      val rows = ctx.clientRows(serverDeviceId)
      assertEquals(
        1,
        rows.size,
        "a send to a not-visible device must still persist exactly one durable, retryable row",
      )
      assertEquals("FAILED", rows.first().send_status, "the row must end FAILED, not vanish silently")

      assertEquals(
        listOf("insertMessage(SENDING)", "updateMessageSendStatus(FAILED)"),
        ctx.repositoryCallKinds(),
        "must insert once as SENDING then flip once to FAILED even when the device isn't visible",
      )

      senderChannel.cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun notVisibleButPooledInboundConnection_sendSucceeds() = runReliabilityTest {
    val ctx = it
    // KLARDROP-JD incident shape: the client's visible-devices map is EMPTY (one-directional
    // mDNS — the peer's dial reaches us, our browse never sees the peer), but the peer dialed
    // us first, so a live inbound connection sits in the pool. The send must ride that pooled
    // connection instead of failing with "$name is not visible".
    turbineScope(timeout = 30.seconds) {
      ctx.setupInboundOnlyConnection()

      // Client sends while still not visible — must succeed over the pooled inbound link.
      val clientMessenger = ctx.clientCommunicationModule.messenger()
      val sendRequest = SimpleSendMessageRequest(TextMessage(text = "riding the inbound link"))

      val senderChannel = clientMessenger.send(serverDeviceId, sendRequest).testIn(this)
      val terminal = ctx.awaitTerminal(senderChannel)

      assertTrue(
        terminal is Completed,
        "a send to a not-visible device with a live pooled connection must complete, was $terminal",
      )

      val rows = ctx.clientRows(serverDeviceId)
      assertEquals(1, rows.size, "exactly one row must be persisted for the whole send")
      assertEquals(null, rows.first().send_status, "the row must end SENT (send_status NULL)")

      senderChannel.cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun pairingResponse_keepsOnlyInboundConnectionWhenPeerNotVisible() = runReliabilityTest {
    val ctx = it
    turbineScope(timeout = 30.seconds) {
      ctx.setupInboundOnlyConnection()

      // Pairing acceptance stores trust before its response is sent. That makes the existing
      // encrypted link eligible for an authentication upgrade, but mDNS is still one-directional:
      // there is no endpoint with which to redial. The response must keep and use the only link.
      val request = ctx.serverCommunicationModule.trustManager()
        .createPairingRequest(clientDeviceId)
        .getOrThrow()
      val response = ctx.clientCommunicationModule.trustManager()
        .createPairingAcceptance(request)
        .getOrThrow()

      val senderChannel = ctx.clientCommunicationModule.messenger()
        .send(serverDeviceId, SimpleSendMessageRequest(response))
        .testIn(this)
      val terminal = ctx.awaitTerminal(senderChannel)

      assertTrue(
        terminal is Completed,
        "pairing response must use the only inbound connection when no redial path exists, was $terminal",
      )

      senderChannel.cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun pairingRequest_overPooledInboundConnection_succeeds() = runReliabilityTest {
    val ctx = it
    turbineScope(timeout = 30.seconds) {
      ctx.setupInboundOnlyConnection()

      // Mirror of pairingResponse_keepsOnlyInboundConnectionWhenPeerNotVisible for the REQUEST
      // leg: the initiator's visible map is empty (one-directional mDNS) but the peer dialed us,
      // so a live inbound connection sits in the pool. Pairing messages are hasPayload=false
      // lightweight frames — the pooled fallback must apply to them identically.
      val request = ctx.clientCommunicationModule.trustManager()
        .createPairingRequest(serverDeviceId)
        .getOrThrow()

      val senderChannel = ctx.clientCommunicationModule.messenger()
        .send(serverDeviceId, SimpleSendMessageRequest(request))
        .testIn(this)
      val terminal = ctx.awaitTerminal(senderChannel)

      assertTrue(
        terminal is Completed,
        "pairing request must deliver over the only inbound connection when no redial path exists, was $terminal",
      )

      senderChannel.cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun pairingRequest_connectFails_allRetriesRunBeforeTerminalError() = runReliabilityTest {
    val ctx = it
    turbineScope(timeout = 60.seconds) {
      // The device IS visible (endpoints exist) but its advertised port is dead, so every dial
      // is refused immediately and each attempt burns exactly one connectionWaitTimeout budget.
      // One exhausted budget must not abort the send: the loop must consume ALL maxRetries
      // before emitting the terminal Error (T5: the phone gave up after attempt 1).
      ctx.addVisibleDeviceWithDeadEndpoint()

      val startReal = TimeSource.Monotonic.markNow()
      val clientMessenger = ctx.clientCommunicationModule.messenger()
      val request = ctx.clientCommunicationModule.trustManager()
        .createPairingRequest(serverDeviceId)
        .getOrThrow()

      val senderChannel = clientMessenger
        .send(serverDeviceId, SimpleSendMessageRequest(request))
        .testIn(this)
      val terminal = ctx.awaitTerminal(senderChannel)

      assertTrue(terminal is Error, "send to a dead endpoint must end in Error, was $terminal")
      assertTrue(
        terminal.reason?.startsWith("connect-failed") == true,
        "terminal error must carry a connect-failed reason, was: $terminal",
      )

      // The connect-failed(TimeoutCancellationException) reason is only emitted once the loop has
      // run out of attempts, i.e. after attempt 3 started — which takes at least the first two
      // 1s connection-wait budgets plus backoffs. Pre-fix, attempt 1's exhausted budget aborted
      // the whole send with reason=null.
      val elapsedRealMs = startReal.elapsedNow().inWholeMilliseconds
      assertTrue(
        elapsedRealMs >= 2_000,
        "at least the first two connection-wait budgets must be consumed before the terminal " +
          "error (elapsed real ms: $elapsedRealMs)",
      )

      senderChannel.cancelAndIgnoreRemainingEvents()
    }
  }

  /** Test-only fixture: a real client+server loopback pair, mirroring MessagesRouterReliabilityTest.Ctx. */
  inner class Ctx {
    private val driver = createTestDriver()
    private val db = AppDatabase(driver)
    private val realClientRepository = MessageRepositoryImpl(db, clock, coroutines.ioDispatcher)
    private val recordingClientRepository = SendStatusRecordingRepository(realClientRepository)

    val clientTrustStorage = InMemoryTrustStorage()

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

    // Server holds no ECDSA key for the client, so a client that trusts the server (signs its
    // TEXT) hits the server's "!senderKnown" verify-failure branch, which replies with a
    // revocation and NO transfer ACK — exactly the no-ACK-ever scenario V7 needs.
    private val noClientKeyServerTrustStorage: com.carlom.klardrop.common.trust.TrustStorage =
      object : com.carlom.klardrop.common.trust.TrustStorage by InMemoryTrustStorage() {
        override suspend fun getECDSAKey(deviceId: String): ByteArray? = null
      }

    private val clientVisibleDevices = FakeVisibleDevices()
    val serverVisibleDevices = FakeVisibleDevices()

    val clientCommunicationModule = CommunicationModule(
      coroutines = coroutines,
      visibleDevices = clientVisibleDevices,
      protoBuf = ProtoBuf,
      clock = clock,
      fileManager = InMemoryTestFileManager(),
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(clientDeviceId)),
      messageRepository = recordingClientRepository,
      clipboardManager = FakeClipboardManager(),
      trustStorage = clientTrustStorage,
      ackTimeoutConfig = fastAckConfig,
      heartbeatConfig = HeartbeatConfig(enabled = false),
      incomingAuthorizerOverride = autoAcceptAuthorizer,
    )

    val serverCommunicationModule = CommunicationModule(
      coroutines = coroutines,
      visibleDevices = serverVisibleDevices,
      protoBuf = ProtoBuf,
      clock = clock,
      fileManager = InMemoryTestFileManager(),
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(serverDeviceId)),
      messageRepository = NoopMessageRepository(),
      clipboardManager = FakeClipboardManager(),
      trustStorage = noClientKeyServerTrustStorage,
      ackTimeoutConfig = fastAckConfig,
      heartbeatConfig = HeartbeatConfig(enabled = false),
      incomingAuthorizerOverride = autoAcceptAuthorizer,
    )

    /** Client trusts the server (using its real ECDSA key); server knows nothing about the client. */
    suspend fun trustServerOnClient() {
      serverCommunicationModule.trustManager().initialize()
      val serverPublicKey = noClientKeyServerTrustStorage.getDevicePublicKey()
        ?: error("server device public key not available after initialize()")
      clientTrustStorage.storeTrustedDevice(serverDeviceId, serverPublicKey)
      clientTrustStorage.storeECDSAKey(serverDeviceId, serverPublicKey)
    }

    suspend fun setupServerAndClient() {
      val server = serverCommunicationModule.server()
      val serverStatus = server.startServer()

      coroutines.dispatcher.scheduler.runCurrent()
      coroutines.dispatcher.scheduler.advanceTimeBy(200)
      coroutines.dispatcher.scheduler.runCurrent()
      coroutines.dispatcher.scheduler.advanceUntilIdle()

      clientVisibleDevices.addKlardropDevice(serverDeviceId, "localhost", serverStatus.port)

      coroutines.dispatcher.scheduler.runCurrent()
      coroutines.dispatcher.scheduler.advanceTimeBy(100)
      coroutines.dispatcher.scheduler.runCurrent()
      coroutines.dispatcher.scheduler.advanceUntilIdle()
    }

    suspend fun setupInboundOnlyConnection() {
      // The client remains absent from its own visible map. Only the server side knows an endpoint,
      // so it dials first and leaves an inbound connection in the client's pool.
      val clientServerStatus = clientCommunicationModule.server().startServer()
      coroutines.dispatcher.scheduler.runCurrent()
      coroutines.dispatcher.scheduler.advanceTimeBy(200)
      coroutines.dispatcher.scheduler.runCurrent()
      coroutines.dispatcher.scheduler.advanceUntilIdle()

      serverVisibleDevices.addKlardropDevice(clientDeviceId, "localhost", clientServerStatus.port)

      // Never await the dial inline: connectTo() is a real network round-trip, and blocking this
      // virtual-time coroutine on it stalls the pump that the peer's coroutines need to answer the
      // handshake. Launch it, pump, and RETRY — ClientImpl reads its peer list from a
      // stateIn(Eagerly) mirror populated by a coroutine on Dispatchers.IO, so a dial issued before
      // that mirror has copied the entry above returns Failed immediately rather than waiting. One
      // fixed sleep is not enough of a guarantee under full-suite load; retrying until the deadline
      // is.
      val pool = clientCommunicationModule.connectionsPool()
      val deadline = TimeSource.Monotonic.markNow() + 30.seconds
      var outcome: ConnectOutcome? = null
      while (deadline.hasNotPassedNow() && !pool.isAvailable(serverDeviceId)) {
        val dial = CoroutineScope(coroutines.ioDispatcher).async {
          serverCommunicationModule.client().connectTo(clientDeviceId)
        }
        while (deadline.hasNotPassedNow() && !dial.isCompleted) {
          pump(virtualStepMs = 200, realSleepMs = 50)
        }
        if (!dial.isCompleted) break
        outcome = dial.await()
        // Let the client side finish pooling the connection its peer just accepted.
        pump(virtualStepMs = 200, realSleepMs = 100)
      }

      // Assert here so a setup that never connects fails on this line instead of surfacing 60
      // seconds later as an unexplained awaitTerminal timeout.
      assertTrue(
        pool.isAvailable(serverDeviceId),
        "the client's pool must hold the inbound connection from $serverDeviceId " +
          "(last dial outcome: $outcome)",
      )
    }

    /** Makes [serverDeviceId] visible with an advertised endpoint where nothing listens. */
    fun addVisibleDeviceWithDeadEndpoint() {
      // Port 1 on localhost: reserved, nothing binds it as non-root, so the dial is refused
      // immediately on every platform this suite runs on.
      clientVisibleDevices.addKlardropDevice(serverDeviceId, "localhost", 1)
    }

    fun clientRows(remoteDeviceId: String) =
      db.messageQueries.getMessagesForDevice(remoteDeviceId, 10).executeAsList()

    fun repositoryCallKinds(): List<String> = recordingClientRepository.calls

    fun tearDown() {
      // Release the sockets, not just the DB. Every Client and every started Server owns a ktor
      // SelectorManager, and on Apple targets each live one blocks in `pselect` holding one of
      // Dispatchers.IO's 64 parallelism slots. Leaking a few per test across the whole native
      // suite starves that pool, and the test binary then hangs forever — uncancellably, so even
      // runTest's own timeout can't fire (that is the macosArm64Test 60-minute CI hang).
      clientCommunicationModule.server().stopServer()
      serverCommunicationModule.server().stopServer()
      clientCommunicationModule.client().close()
      serverCommunicationModule.client().close()
      driver.close()
    }

    private suspend fun pump(virtualStepMs: Long, realSleepMs: Long) {
      coroutines.dispatcher.scheduler.runCurrent()
      coroutines.dispatcher.scheduler.advanceTimeBy(virtualStepMs)
      withContext(coroutines.ioDispatcher) { delay(realSleepMs) }
      yield()
      coroutines.dispatcher.scheduler.runCurrent()
    }

    /** Pumps virtual+real time until the sender flow emits a terminal (Completed/Error). */
    suspend fun awaitTerminal(
      channel: app.cash.turbine.ReceiveTurbine<MessengerSendProgress>,
      maxRealTimeMs: Long = 60_000,
    ): MessengerSendProgress {
      val ch = channel.asChannel()
      val deadline = TimeSource.Monotonic.markNow() + maxRealTimeMs.milliseconds
      var last: MessengerSendProgress? = null
      while (deadline.hasNotPassedNow()) {
        pump(virtualStepMs = 200, realSleepMs = 50)
        while (true) {
          val result = ch.tryReceive()
          if (result.isSuccess) {
            val item = result.getOrThrow()
            last = item
            if (item is Completed || item is Error) return item
          } else if (result.isClosed) {
            error("send flow closed before terminal (last=$last): ${result.exceptionOrNull()}")
          } else {
            break
          }
        }
      }
      error("send flow did not reach a terminal state within ${maxRealTimeMs}ms (last=$last)")
    }
  }
}

/** Records every outgoing-status write the client-side repository sees, delegating for real persistence. */
private class SendStatusRecordingRepository(
  private val delegate: MessageRepository,
) : MessageRepository by delegate {
  val calls = mutableListOf<String>()

  override suspend fun insertMessage(
    remoteDeviceId: String,
    content: String,
    isSender: Boolean,
    messageType: PersistenceMessageType,
    fileTransferId: Long?,
    isRead: Boolean,
    mimeType: String,
    messageId: Long?,
    sendStatus: SendStatus,
  ): Long {
    calls.add("insertMessage($sendStatus)")
    return delegate.insertMessage(remoteDeviceId, content, isSender, messageType, fileTransferId, isRead, mimeType, messageId, sendStatus)
  }

  override suspend fun updateMessageSendStatus(messageId: Long, status: SendStatus) {
    calls.add("updateMessageSendStatus($status)")
    delegate.updateMessageSendStatus(messageId, status)
  }
}
