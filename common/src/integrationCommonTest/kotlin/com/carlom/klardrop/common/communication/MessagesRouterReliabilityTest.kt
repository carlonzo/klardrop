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
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.mdns.FakeVisibleDevices
import com.carlom.klardrop.common.persistence.FileTransferStatus
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType as PersistenceMessageType
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.trust.InMemoryTrustStorage
import com.carlom.klardrop.common.utils.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Repro tests (test-FIRST, before any fix) for the inbound-TEXT reliability bugs in
 * [com.carlom.klardrop.common.communication.router.MessagesRouterImpl].
 *
 * The TEXT branch of `onMessageIncoming` spawns the whole authorize → handle → ACK pipeline on
 * a fire-and-forget [kotlinx.coroutines.SupervisorJob] scope with NO try/catch. Any throw from
 * the authorizer or from `TextMessageHandler.handleIncoming` (which calls
 * `MessageRepository.insertMessage`) is therefore swallowed and the terminal ACK_RECEIVED is
 * NEVER sent. The sender's `awaitRegisteredAck(RECEIVED)` then times out, the transfer is retried
 * up to `maxRetries` times (each retry re-delivers the same TEXT and re-runs the failing handler),
 * and the user sees a transport-level failure instead of a clean rejection — the classic "ghost
 * duplicate message" symptom.
 *
 * Contrast with the FILE path ([MessagesRouterImpl.handleFileChunk]) which already sends a terminal
 * ACK_REJECTED on failure so the sender fast-fails without retrying.
 *
 * These tests reuse the two-real-module loopback pattern from KlardropIntegrationTest but inject a
 * SERVER-side [MessageRepository] that throws and counts attempts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessagesRouterReliabilityTest {

  // Rebuilt per test by [runReliabilityTest] (hence `var`) so each test gets a fresh dispatcher /
  // virtual clock / sockets — a previous test's advanced virtual time and torn-down sockets must
  // not bleed into the next one's connection setup.
  private var coroutines = TestCoroutines(dispatcher = UnconfinedTestDispatcher())
  private var clock = Clock()
  // 8-char ids: the Klardrop handshake exchanges SHORTENED (8-char) device ids, so longer ids get
  // truncated and the client rejects the connection as a mismatch ("Device ... found is wrong").
  private val clientDeviceId = "clientAA"
  private val serverDeviceId = "serverBB"

  /**
   * Short ACK timeouts + bounded retries so the bug (timeout → retry → exhaustion) plays out
   * quickly in virtual time rather than the 60s/120s used by the happy-path harness. maxRetries=2
   * means a healthy round-trip plus two retries = 3 total deliveries of the same TEXT when the
   * receiver stays silent.
   */
  private val fastAckConfig = AckTimeoutConfig(
    noPayloadAckTimeout = 2.seconds,
    readyAckTimeout = 2.seconds,
    receivedAckTimeout = 2.seconds,
    userResponseTimeout = 2.seconds,
    maxRetries = 2,
    retryBackoffMultiplier = 1.0,
  )

  private fun runReliabilityTest(
    timeout: Duration = 90.seconds,
    body: suspend TestScope.(ctx: Ctx) -> Unit,
  ) {
    // Fresh fixture per test: rebuild the dispatcher + clock so virtual time starts at zero and no
    // sockets from a sibling test linger.
    coroutines = TestCoroutines(dispatcher = UnconfinedTestDispatcher())
    clock = Clock()
    runTest(coroutines.dispatcher, timeout = timeout) {
      val ctx = Ctx()
      body(ctx)
    }
  }

  /**
   * Server-side `insertMessage` throws on every inbound TEXT.
   *
   * The bug: the throw is swallowed by the SupervisorJob, no terminal ACK is sent, the sender's
   * RECEIVED wait times out, and `handleKlardropTransfer` re-delivers the TEXT maxRetries more
   * times — so `insertMessage` is hit 1 + maxRetries (= 3) times for ONE user-initiated send.
   * Each redelivery is a duplicate "ghost" attempt at the receiver.
   *
   * With the fix the router catches the throw and replies ACK_REJECTED, which the sender treats
   * as a terminal rejection: it does NOT retry, so `insertMessage` is attempted exactly once.
   *
   * This test fails on current code because the receiver is silently hammered with retries.
   */
  @Test
  fun textHandlerThrow_isFastRejected_notRetriedAsGhostDuplicates() = runReliabilityTest {
    val ctx = it
    ctx.setupServerAndClient()

    turbineScope(timeout = 60.seconds) {
      val clientMessenger = ctx.clientCommunicationModule.messenger()
      val sendRequest = SimpleSendMessageRequest(TextMessage("title", text = "ghost-message"))

      val senderChannel = clientMessenger.send(serverDeviceId, sendRequest).testIn(this)

      // Pump virtual + real time until the send flow reaches a terminal state. The receiver
      // throws inside the un-guarded TEXT launch, so the only terminal we ever reach today is
      // Error (after retries exhaust). With the fix the terminal is still Error (declined), but
      // the receiver-side attempt count is what differentiates the two — asserted below.
      val terminal = ctx.awaitTerminal(senderChannel)

      // The send must terminate (sanity: not hang). Both pre-fix and post-fix end in Error here
      // because the receiver rejects the message either way — but the WAY it gets there differs.
      assertTrue(
        terminal is Error || terminal is Completed,
        "send should reach a terminal state, was $terminal",
      )

      // The crux of this test: a single user send of ONE text must not be re-delivered to the
      // receiver as retry-induced ghost duplicates. The router must terminally ACK_REJECTED the
      // first delivery so the sender stops. Pre-fix the receiver silently swallows the throw,
      // never ACKs, and the sender retries -> insertMessage is attempted 1 + maxRetries times.
      assertEquals(
        1,
        ctx.serverInsertAttempts(),
        "Inbound TEXT whose handler throws must be terminally rejected after a SINGLE delivery; " +
          "instead it was redelivered as ghost duplicates (insertMessage attempted " +
          "${ctx.serverInsertAttempts()} times for one send).",
      )

      senderChannel.cancelAndIgnoreRemainingEvents()
    }
  }

  /**
   * The server holds NO ECDSA key for the client, so when the client (which trusts the server)
   * wraps its TEXT in a signed TrustedMessage, the server's `verifyMessage` fails and the
   * `!senderKnown` sub-path runs: it replies with a revocation but sends NO transfer ACK at all.
   *
   * The sender is waiting on ACK_RECEIVED (it never registered a pending-revocation channel), so
   * it times out and `handleKlardropTransfer` retries the full send maxRetries times. The fix is
   * to send ACK_REJECTED before returning so the sender fast-fails.
   *
   * We observe the bug through the number of inbound TrustedMessage verifications the server
   * performs: a single user send should hit the verify path once; retries make it >1.
   */
  @Test
  fun signatureInvalid_unknownSender_repliesWithoutAck_causesRetries() = runReliabilityTest {
    val ctx = it
    // Make the client trust the server using the server's REAL device identity key. This is the
    // asymmetric trust state the bug needs:
    //  - client trusts server  -> MessengerImpl wraps the TEXT in a signed TrustedMessage, and the
    //    client's UKEY2 initiator handshake can authenticate the server's binding (so the
    //    handshake completes instead of aborting as a "trusted peer, unverifiable" MITM).
    //  - server does NOT trust client -> when the signed TEXT lands, the server's verifyMessage
    //    finds no ECDSA key for the client -> the `!isValid && !senderKnown` sub-path runs, which
    //    today replies with a revocation but sends NO transfer ACK.
    ctx.trustServerOnClient()

    ctx.setupServerAndClient()
    // The UKEY2 handshake also looks up the client's ECDSA key once; ignore that so the counter
    // reflects only per-message verification attempts (which retries inflate).
    ctx.resetVerifyAttempts()

    turbineScope(timeout = 60.seconds) {
      val clientMessenger = ctx.clientCommunicationModule.messenger()
      val sendRequest = SimpleSendMessageRequest(TextMessage("title", text = "unsigned-to-server"))

      val senderChannel = clientMessenger.send(serverDeviceId, sendRequest).testIn(this)
      val terminal = ctx.awaitTerminal(senderChannel)

      assertTrue(terminal is Error, "send to a server that can't verify us should fail, was $terminal")

      // Sanity: the signed TEXT actually reached the server's verify path (so the failure below is
      // about the missing ACK, not a connection that never carried the message).
      assertTrue(
        ctx.serverVerifyAttempts() >= 1,
        "expected the server to attempt verification of the inbound TrustedMessage at least once",
      )

      // When the server can't verify the signature it must reply with a terminal ACK_REJECTED so
      // the sender fast-fails as a DECLINE. Pre-fix the server's unknown-sender branch returns
      // WITHOUT any transfer ACK, so the sender's RECEIVED wait times out and the send is retried
      // to exhaustion — surfacing a transport-timeout Error ("Transfer failed ...") rather than
      // the clean decline. Asserting on the exact terminal message distinguishes the fast-reject
      // (post-fix) from the retry-timeout (pre-fix) unambiguously.
      assertEquals(
        "Recipient declined the transfer",
        (terminal as Error).message,
        "An unverifiable signed TEXT must be terminally ACK_REJECTED so the sender fast-fails as a " +
          "decline; instead it timed out and retried (terminal was: ${terminal.message}).",
      )

      senderChannel.cancelAndIgnoreRemainingEvents()
    }
  }

  /** Test-only fixture mirroring KlardropTestContext but with instrumented server-side seams. */
  inner class Ctx {
    val clientTrustStorage = InMemoryTrustStorage()

    // Counts inbound-TEXT handler invocations on the server. handleIncoming throws AFTER
    // incrementing so we measure how many times the receiver was actually hit (= ghost
    // redeliveries).
    private var insertAttempts = 0
    fun serverInsertAttempts() = insertAttempts

    private val throwingServerRepository = object : MessageRepository by NoopMessageRepository() {
      override suspend fun insertMessage(
        remoteDeviceId: String,
        content: String,
        isSender: Boolean,
        messageType: PersistenceMessageType,
        fileTransferId: Long?,
        isRead: Boolean,
        mimeType: String,
        messageId: Long?,
        sendStatus: com.carlom.klardrop.common.persistence.SendStatus,
      ) {
        // Only the inbound (received) TEXT matters for the repro; an outgoing insert on the
        // server would be a sender-side artifact and shouldn't happen here.
        insertAttempts++
        throw IllegalStateException("DB write failed (simulated)")
      }
    }

    // Counts inbound TrustedMessage verifications on the server. verifyMessage's first step
    // is `storage.getECDSAKey(senderId)`, so counting client-key lookups counts verification
    // attempts. The UKEY2 handshake also looks the key up once during connection setup, so the
    // counter is reset (resetVerifyAttempts) right after setup completes — leaving only the
    // per-message verifications, which is exactly what retries inflate.
    private var verifyAttempts = 0
    fun serverVerifyAttempts() = verifyAttempts
    fun resetVerifyAttempts() {
      verifyAttempts = 0
    }

    private val countingServerTrustStorage: com.carlom.klardrop.common.trust.TrustStorage =
      object : com.carlom.klardrop.common.trust.TrustStorage by InMemoryTrustStorage() {
        override suspend fun getECDSAKey(deviceId: String): ByteArray? {
          if (deviceId == clientDeviceId) verifyAttempts++
          return null // server holds no ECDSA key for the client -> verification fails
        }
      }

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

    private val clientVisibleDevices = FakeVisibleDevices()

    val clientCommunicationModule = CommunicationModule(
      coroutines = coroutines,
      visibleDevices = clientVisibleDevices,
      protoBuf = ProtoBuf,
      clock = clock,
      fileManager = InMemoryTestFileManager(),
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(clientDeviceId)),
      messageRepository = NoopMessageRepository(),
      clipboardManager = FakeClipboardManager(),
      trustStorage = clientTrustStorage,
      ackTimeoutConfig = fastAckConfig,
      heartbeatConfig = HeartbeatConfig(enabled = false),
      incomingAuthorizerOverride = autoAcceptAuthorizer,
    )

    private val serverCommunicationModule = CommunicationModule(
      coroutines = coroutines,
      visibleDevices = FakeVisibleDevices(),
      protoBuf = ProtoBuf,
      clock = clock,
      fileManager = InMemoryTestFileManager(),
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(serverDeviceId)),
      messageRepository = throwingServerRepository,
      clipboardManager = FakeClipboardManager(),
      trustStorage = countingServerTrustStorage,
      ackTimeoutConfig = fastAckConfig,
      heartbeatConfig = HeartbeatConfig(enabled = false),
      incomingAuthorizerOverride = autoAcceptAuthorizer,
    )

    /**
     * Establish ASYMMETRIC trust: the client trusts the server using the server's REAL ECDSA
     * identity key (so both the UKEY2 handshake authentication AND application-message signing
     * succeed), while the server is left knowing nothing about the client. Call BEFORE
     * [setupServerAndClient].
     */
    suspend fun trustServerOnClient() {
      // Generate + persist the server's device identity so we can read its public key.
      serverCommunicationModule.trustManager().initialize()
      val serverPublicKey = countingServerTrustStorage.getDevicePublicKey()
        ?: error("server device public key not available after initialize()")
      // The client stores it under serverDeviceId both as the trusted (ECDH) key — making
      // isTrusted(server) true so the TEXT gets signed — and as the ECDSA key the handshake +
      // message verification use.
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

    private suspend fun pump(virtualStepMs: Long, realSleepMs: Long) {
      coroutines.dispatcher.scheduler.runCurrent()
      coroutines.dispatcher.scheduler.advanceTimeBy(virtualStepMs)
      withContext(coroutines.ioDispatcher) { delay(realSleepMs) }
      yield()
      coroutines.dispatcher.scheduler.runCurrent()
    }

    /**
     * Pumps virtual+real time until the sender flow emits a terminal (Completed/Error), bridging
     * the virtual-time ACK timeouts and the real-time socket I/O the same way the harness's
     * awaitForPumping does.
     */
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

/** A do-nothing [MessageRepository] used as a base for the instrumented server-side fakes. */
internal open class NoopMessageRepository : MessageRepository {
  override suspend fun insertMessage(
    remoteDeviceId: String,
    content: String,
    isSender: Boolean,
    messageType: PersistenceMessageType,
    fileTransferId: Long?,
    isRead: Boolean,
    mimeType: String,
    messageId: Long?,
    sendStatus: com.carlom.klardrop.common.persistence.SendStatus,
  ) {
  }

  override suspend fun insertFileTransfer(
    fileName: String,
    filePath: String,
    totalSize: Long,
    status: FileTransferStatus,
    mimeType: String,
  ): Long = 1L

  override suspend fun updateFileTransferStatus(id: Long, status: FileTransferStatus) {}
  override suspend fun updateFileTransferFilePath(id: Long, filePath: String) {}
  override suspend fun markStaleInProgressAsFailed() {}
  override suspend fun markMessagesAsRead(remoteDeviceId: String) {}
  override suspend fun getUnreadCountForDevice(remoteDeviceId: String): Long = 0L
  override fun getAllDevicesWithUnreadCounts(): kotlinx.coroutines.flow.Flow<Map<String, Long>> =
    kotlinx.coroutines.flow.flowOf(emptyMap())

  override fun getMessagesForDevice(
    remoteDeviceId: String,
    limit: Long,
  ): kotlinx.coroutines.flow.Flow<List<com.carlom.klardrop.common.persistence.ChatMessage>> =
    kotlinx.coroutines.flow.flowOf(emptyList())

  override fun getFileTransferById(id: Long): kotlinx.coroutines.flow.Flow<com.carlom.klardrop.common.database.File_transfers?> =
    kotlinx.coroutines.flow.flowOf(null)
}
