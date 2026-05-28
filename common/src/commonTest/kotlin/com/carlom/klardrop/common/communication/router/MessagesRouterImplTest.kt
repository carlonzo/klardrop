package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.AckType
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.MessageAcknowledgment
import com.carlom.klardrop.common.communication.message.MessageHandler
import com.carlom.klardrop.common.communication.message.MessageHandlers
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.message.PingMessage
import com.carlom.klardrop.common.communication.message.PongMessage
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.TrustRevocationMessage
import com.carlom.klardrop.common.communication.message.TrustedMessage
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.readMessage
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import io.github.vinceglb.filekit.PlatformFile
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


@OptIn(ExperimentalCoroutinesApi::class)
class MessagesRouterImplTest {

  private lateinit var messagesRouter: MessagesRouterImpl
  private lateinit var mockMessageRepository: MockMessageRepository
  private lateinit var mockMessageHandlers: MockMessageHandlers
  private lateinit var mockMessageSerializer: MessageSerializer // Real one, but could be mocked if needed
  private lateinit var mockMessageReceiver: MockMessageReceiver
  private lateinit var testDispatcher: TestDispatcher
  private lateinit var mockCoroutines: Coroutines


  // --- Mocks ---
  class MockMessageRepository : MessageRepository {
    val calls = mutableListOf<String>()

    override suspend fun insertMessage(
      remoteDeviceId: String,
      content: String,
      isSender: Boolean,
      messageType: com.carlom.klardrop.common.persistence.MessageType,
      fileTransferId: Long?,
      isRead: Boolean,
      mimeType: String
    ) {
      calls.add("insertMessage($remoteDeviceId, $content, $isSender, $messageType, $fileTransferId, $isRead, $mimeType)")
    }

    override suspend fun insertFileTransfer(
      fileName: String,
      filePath: String,
      totalSize: Long,
      status: com.carlom.klardrop.common.persistence.FileTransferStatus,
      mimeType: String
    ): Long {
      calls.add("insertFileTransfer($fileName, $filePath, $totalSize, $status, $mimeType)")
      return 1L
    }

    override suspend fun updateFileTransferStatus(id: Long, status: com.carlom.klardrop.common.persistence.FileTransferStatus) {
      calls.add("updateFileTransferStatus($id, $status)")
    }

    override suspend fun updateFileTransferFilePath(id: Long, filePath: String) {
      calls.add("updateFileTransferFilePath($id, $filePath)")
    }

    override suspend fun markStaleInProgressAsFailed() {
      calls.add("markStaleInProgressAsFailed()")
    }

    override suspend fun markMessagesAsRead(remoteDeviceId: String) {
      calls.add("markMessagesAsRead($remoteDeviceId)")
    }

    override suspend fun getUnreadCountForDevice(remoteDeviceId: String): Long {
      calls.add("getUnreadCountForDevice($remoteDeviceId)")
      return 0L
    }

    override fun getAllDevicesWithUnreadCounts(): kotlinx.coroutines.flow.Flow<Map<String, Long>> {
      calls.add("getAllDevicesWithUnreadCounts()")
      return kotlinx.coroutines.flow.flowOf(emptyMap())
    }

    override fun getMessagesForDevice(
      remoteDeviceId: String,
      limit: Long
    ): kotlinx.coroutines.flow.Flow<List<com.carlom.klardrop.common.database.Messages>> =
      kotlinx.coroutines.flow.flowOf(emptyList())

    override fun getFileTransferById(id: Long): kotlinx.coroutines.flow.Flow<com.carlom.klardrop.common.database.File_transfers?> =
      kotlinx.coroutines.flow.flowOf(null)
  }

  class MockMessageHandlers : MessageHandlers {
    var handlerToReturn: MessageHandler<Message, SendMessageRequest>? = null

    override fun get(messageType: MessageType): MessageHandler<Message, SendMessageRequest>? {
      return handlerToReturn // Only return a handler if one is specifically set for a test
    }
  }

  class MockMessageHandler<E : Message, R : SendMessageRequest> : MessageHandler<E, R> {
    var incomingMessageHandled: E? = null
    var outgoingRequestHandled: R? = null
    override suspend fun handleIncoming(message: E, readChannel: ByteReadChannel, receiveFlow: MutableStateFlow<ReceiveMessageUpdate>) {
      incomingMessageHandled = message
    }

    override suspend fun handleOutgoing(
      toDeviceId: String,
      request: R,
      writeChannel: ByteWriteChannel,
      progressFlow: MutableSharedFlow<MessengerSendProgress>
    ) {
      outgoingRequestHandled = request
    }
  }


  class MockMessageReceiver : MessageReceiver {
    val onReceiveMessageFlow = MutableStateFlow(
      ReceiveMessageUpdate(
        device = DeviceInfo("test-device", "Test Device", DeviceType.DESKTOP),
        status = ReceiveMessageStatus.Started
      )
    )

    override fun onReceiveMessage(deviceId: String): MutableStateFlow<ReceiveMessageUpdate> = onReceiveMessageFlow
    override val notifier: kotlinx.coroutines.flow.Flow<Pair<String, kotlinx.coroutines.flow.StateFlow<ReceiveMessageUpdate>>> =
      kotlinx.coroutines.flow.flowOf()
    override val messageReceivedNotifier: kotlinx.coroutines.flow.Flow<ReceiveMessageUpdate> = kotlinx.coroutines.flow.flowOf()
  }

  fun createMockTrustManager(): TrustManager {
    val crypto = com.carlom.klardrop.common.trust.TrustCrypto()
    val storage = object : com.carlom.klardrop.common.trust.TrustStorage {
      override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {}
      override suspend fun storeECDSAKey(deviceId: String, ecdsaPublicKey: ByteArray) {}
      override suspend fun getTrustedDeviceKey(deviceId: String): ByteArray? = null
      override suspend fun getECDSAKey(deviceId: String): ByteArray? = null
      override suspend fun getAllTrustedDevices(): Map<String, ByteArray> = emptyMap()
      override suspend fun removeTrustedDevice(deviceId: String) {}
      override suspend fun clearAllTrustedDevices() {}
      override suspend fun storeDevicePrivateKey(privateKey: ByteArray) {}
      override suspend fun getDevicePrivateKey(): ByteArray? = null
      override suspend fun storeDevicePublicKey(publicKey: ByteArray) {}
      override suspend fun getDevicePublicKey(): ByteArray? = null
      override suspend fun deleteDevicePrivateKey() {}
    }
    val clock = com.carlom.klardrop.common.utils.Clock()
    val localPropsRepo = object : com.carlom.klardrop.common.persistence.LocalPropertiesRepository {
      override val properties: kotlinx.coroutines.flow.Flow<com.carlom.klardrop.common.persistence.KlardropProperties> = 
        kotlinx.coroutines.flow.flowOf(com.carlom.klardrop.common.persistence.KlardropProperties("test-device", "Test Device"))
      override suspend fun getProperty(): com.carlom.klardrop.common.persistence.KlardropProperties = 
        com.carlom.klardrop.common.persistence.KlardropProperties("test-device", "Test Device")
      override suspend fun save(properties: com.carlom.klardrop.common.persistence.KlardropProperties) {}
      override suspend fun saveCustomDeviceName(customDeviceName: String?) {}
    }
    val currentDeviceProvider = com.carlom.klardrop.common.discovery.CurrentDeviceProvider(localPropsRepo)
    
    return com.carlom.klardrop.common.trust.TrustManager(crypto, storage, clock, currentDeviceProvider)
  }


  @BeforeTest
  fun setup() {
    testDispatcher = UnconfinedTestDispatcher()
    mockCoroutines = object : Coroutines {
      override val ioDispatcher = testDispatcher
      override val mainDispatcher = testDispatcher
      override val cpuDispatcher = testDispatcher
      override val appScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
      override fun newScope() = kotlinx.coroutines.CoroutineScope(testDispatcher)
      override fun newScope(context: kotlin.coroutines.CoroutineContext) = kotlinx.coroutines.CoroutineScope(context)
    }
    mockMessageRepository = MockMessageRepository()
    mockMessageHandlers = MockMessageHandlers()
    // Using ProtoBuf for actual serialization as it's part of the contract
    mockMessageSerializer = MessageSerializer(ProtoBuf, mockCoroutines)
    mockMessageReceiver = MockMessageReceiver()

    val mockTrustManager = createMockTrustManager()
    // Auto-accepting authorizer keeps these routing tests focused on dispatch logic,
    // not the trust/authorization gate (covered by IncomingAuthorizerTest).
    val autoAcceptAuthorizer = object : IncomingAuthorizer(mockTrustManager) {
      override suspend fun authorize(
        fromDeviceId: String,
        kind: TransferKind,
        headers: List<Message>,
        receiveFlow: MutableStateFlow<ReceiveMessageUpdate>,
        notifyAwaitingUser: suspend () -> Unit,
      ): Boolean = true
    }
    messagesRouter = MessagesRouterImpl(
      handlers = mockMessageHandlers,
      fileMessageHandler = com.carlom.klardrop.common.communication.message.FileMessageHandler(
        fileManager = object : com.carlom.klardrop.common.FileManager {
          override fun prepareSaveFile(fileName: String, mimeType: String): com.carlom.klardrop.common.FileTransfer =
            error("not used in router tests")
          override fun getReadStreamFrom(file: PlatformFile): kotlinx.io.RawSource =
            error("not used in router tests")
          override suspend fun openFile(filePath: String): Boolean = false
          override suspend fun openUrl(url: String): Boolean = false
        },
        clock = com.carlom.klardrop.common.utils.Clock(),
        coroutines = mockCoroutines,
        messageRepository = mockMessageRepository,
      ),
      messageSerializer = mockMessageSerializer,
      coroutines = mockCoroutines,
      messengeReceiver = mockMessageReceiver,
      trustManager = mockTrustManager,
      incomingAuthorizer = autoAcceptAuthorizer,
    )
  }

  private suspend fun createMessageBytes(message: Message): ByteArray {
    // Use the actual MessageSerializer to create properly formatted messages
    val messagePayload = mockMessageSerializer.serialize(message)

    // Create the length prefix (4 bytes) as expected by readByteArrayMessage
    val lengthBytes = ByteArray(4)
    lengthBytes[0] = (messagePayload.size shr 24).toByte()
    lengthBytes[1] = (messagePayload.size shr 16).toByte()
    lengthBytes[2] = (messagePayload.size shr 8).toByte()
    lengthBytes[3] = messagePayload.size.toByte()

    return lengthBytes + messagePayload
  }


  @Test
  fun onMessageIncomingForTextMessageNoPayloadInsertsMessage() = runTest(testDispatcher) {
    val fromDeviceId = "sender-text"
    val textContent = "Hello from router test!"
    val textMessage = TextMessage(text = textContent)

    // Set up a handler for TEXT messages
    val mockHandler = MockMessageHandler<TextMessage, SimpleSendMessageRequest>()
    @Suppress("UNCHECKED_CAST")
    mockMessageHandlers.handlerToReturn = mockHandler as MessageHandler<Message, SendMessageRequest>

    val serializedMessage = createMessageBytes(textMessage)
    val readChannel = ByteReadChannel(serializedMessage)
    val writeChannel = ByteChannel(true)

    messagesRouter.onMessageIncoming(fromDeviceId, writeChannel, readChannel, ackCallback = { })

    // Verify the handler was called with the message
    assertEquals(textMessage, mockHandler.incomingMessageHandled)
  }

  @Test
  fun onSendingMessageForTextMessageNoPayloadInsertsMessage() = runTest(testDispatcher) {
    val toDeviceId = "receiver-text"
    val textContent = "Router test sending!"
    val textMessage = TextMessage(text = textContent)
    val request = textMessage.toSimpleSendRequest() // SimpleSendMessageRequest

    val writeChannel = ByteChannel(true)
    val readChannel = ByteReadChannel(byteArrayOf()) // Not used for no-payload sending
    val progressFlow = MutableSharedFlow<MessengerSendProgress>()

    messagesRouter.onSendingMessage(toDeviceId, request, writeChannel, readChannel, progressFlow)

    // For TEXT messages with hasPayload=false, the router sends directly without using handlers
    // So we just verify no errors occurred (no exceptions thrown)
    assertEquals(0, mockMessageRepository.calls.size) // MessageRepository should not be called directly by router for no-payload messages
  }

  // FILE message routing is no longer driven through the generic MessageHandlers dispatch — the
  // router special-cases FileMessage / FileChunkMessage and calls FileMessageHandler.beginReceive
  // and handleOutgoingChunked directly. Behavior is covered by FileMessageHandlerTest (handler
  // unit tests) and KlardropIntegrationTest (end-to-end loopback). The previous tests in this
  // class asserted on a code path that no longer exists.

  /**
   * Trust storage helper where [trustedIds] are considered trusted (returns a non-null fake
   * key for them, which is all `isTrusted` actually consults).
   */
  private fun trustStorageWith(trustedIds: Set<String>): com.carlom.klardrop.common.trust.TrustStorage =
    object : com.carlom.klardrop.common.trust.TrustStorage {
      override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {}
      override suspend fun storeECDSAKey(deviceId: String, ecdsaPublicKey: ByteArray) {}
      override suspend fun getTrustedDeviceKey(deviceId: String): ByteArray? =
        if (deviceId in trustedIds) byteArrayOf(0x1) else null
      override suspend fun getECDSAKey(deviceId: String): ByteArray? = null
      override suspend fun getAllTrustedDevices(): Map<String, ByteArray> = emptyMap()
      override suspend fun removeTrustedDevice(deviceId: String) {}
      override suspend fun clearAllTrustedDevices() {}
      override suspend fun storeDevicePrivateKey(privateKey: ByteArray) {}
      override suspend fun getDevicePrivateKey(): ByteArray? = null
      override suspend fun storeDevicePublicKey(publicKey: ByteArray) {}
      override suspend fun getDevicePublicKey(): ByteArray? = null
      override suspend fun deleteDevicePrivateKey() {}
    }

  /**
   * Build a router whose TrustManager treats [trustedDeviceId] as paired/trusted. The router
   * receives messages from that device through the standard onMessageIncoming entry point.
   */
  private fun routerWithTrustedDevice(trustedDeviceId: String): MessagesRouterImpl {
    val crypto = com.carlom.klardrop.common.trust.TrustCrypto()
    val storage = trustStorageWith(setOf(trustedDeviceId))
    val clock = com.carlom.klardrop.common.utils.Clock()
    val localPropsRepo = object : com.carlom.klardrop.common.persistence.LocalPropertiesRepository {
      override val properties = kotlinx.coroutines.flow.flowOf(
        com.carlom.klardrop.common.persistence.KlardropProperties("self-id", "Self")
      )
      override suspend fun getProperty() =
        com.carlom.klardrop.common.persistence.KlardropProperties("self-id", "Self")
      override suspend fun save(properties: com.carlom.klardrop.common.persistence.KlardropProperties) {}
      override suspend fun saveCustomDeviceName(customDeviceName: String?) {}
    }
    val trustManager = com.carlom.klardrop.common.trust.TrustManager(
      crypto = crypto,
      storage = storage,
      clock = clock,
      currentDeviceProvider = com.carlom.klardrop.common.discovery.CurrentDeviceProvider(localPropsRepo),
    )
    val authorizer = object : IncomingAuthorizer(trustManager) {
      override suspend fun authorize(
        fromDeviceId: String,
        kind: TransferKind,
        headers: List<Message>,
        receiveFlow: MutableStateFlow<ReceiveMessageUpdate>,
        notifyAwaitingUser: suspend () -> Unit,
      ): Boolean = true
    }
    return MessagesRouterImpl(
      handlers = mockMessageHandlers,
      fileMessageHandler = com.carlom.klardrop.common.communication.message.FileMessageHandler(
        fileManager = object : com.carlom.klardrop.common.FileManager {
          override fun prepareSaveFile(fileName: String, mimeType: String): com.carlom.klardrop.common.FileTransfer =
            error("not used in router tests")
          override fun getReadStreamFrom(file: PlatformFile): kotlinx.io.RawSource =
            error("not used in router tests")
          override suspend fun openFile(filePath: String): Boolean = false
          override suspend fun openUrl(url: String): Boolean = false
        },
        clock = clock,
        coroutines = mockCoroutines,
        messageRepository = mockMessageRepository,
      ),
      messageSerializer = mockMessageSerializer,
      coroutines = mockCoroutines,
      messengeReceiver = mockMessageReceiver,
      trustManager = trustManager,
      incomingAuthorizer = authorizer,
    )
  }

  /**
   * Regression test for the trusted-device control-plane bug: an unsigned PING coming from a
   * trusted peer must NOT be rejected by the security gate, otherwise the heartbeat (sent
   * unsigned by ConnectionMessenger which has no TrustManager handle) tears the connection
   * down. With the bug, this test would observe no PONG written — the router would drop the
   * frame silently with `SECURITY: unsigned message from trusted device ... - rejecting`.
   */
  @Test
  fun unsignedPingFromTrustedDeviceIsAcceptedAndPongedBack() = runTest(testDispatcher) {
    val trustedDevice = "trusted-peer"
    val router = routerWithTrustedDevice(trustedDevice)

    val ping = PingMessage(id = 4242)
    val readChannel = ByteReadChannel(createMessageBytes(ping))
    val writeChannel = ByteChannel(true)

    router.onMessageIncoming(trustedDevice, writeChannel, readChannel, ackCallback = { })

    // Reading from the write channel should yield a PONG framed by the wire format. The
    // router signs replies to trusted peers via sendMessageToDevice, so the outermost
    // frame may be a TrustedMessage wrapping the PONG. Either way, the inner message must
    // be a PONG with the matching ping id — that's the contract this test enforces (the
    // router did NOT silently drop the unsigned PING with `SECURITY: ... rejecting`).
    val raw = writeChannel.readMessage(mockMessageSerializer)
    val pong = if (raw is TrustedMessage) {
      mockMessageSerializer.deserialize(raw.payload)
    } else {
      raw
    }
    assertTrue(pong is PongMessage, "Expected PONG, got ${pong::class.simpleName}")
    assertEquals(ping.id, pong.pingId)
  }

  /**
   * Regression test for the bug Carlo hit on Android↔Desktop: a trusted-device receiver was
   * sending ACK_READY *unsigned*, and the trusted-device sender's security gate rejected it
   * outright, causing every file transfer between paired devices to time out and retry until
   * eventual failure. Two sides of the same coin:
   *  1. The receiver-side router must SIGN its ACK reply when the peer is trusted.
   *  2. The sender-side router must also ACCEPT control-plane acks even if unsigned (defense
   *     in depth, since a peer running an older build won't sign).
   *
   * This test exercises (2): an unsigned ACK_RECEIVED from a trusted peer should still invoke
   * ackCallback, not be silently dropped.
   */
  @Test
  fun unsignedAckFromTrustedDeviceInvokesAckCallback() = runTest(testDispatcher) {
    val trustedDevice = "trusted-peer"
    val router = routerWithTrustedDevice(trustedDevice)

    val ack = MessageAcknowledgment(AckType.RECEIVED, id = 7777)
    val readChannel = ByteReadChannel(createMessageBytes(ack))
    val writeChannel = ByteChannel(true)

    var observedAck: MessageAcknowledgment? = null
    router.onMessageIncoming(
      fromDeviceId = trustedDevice,
      writeChannel = writeChannel,
      readChannel = readChannel,
      ackCallback = { observedAck = it },
    )

    val captured = assertNotNull(observedAck, "ackCallback must fire for unsigned ACK from trusted device")
    assertEquals(ack.id, captured.id)
    assertEquals(AckType.RECEIVED, captured.ackType)
  }

  /**
   * Regression test for the trusted-device ACK id mismatch.
   *
   * When a peer is trusted, [com.carlom.klardrop.common.communication.Messenger.send] wraps
   * the application-level message (TextMessage / FileMessage / ...) in a [TrustedMessage]
   * envelope before handing it to [com.carlom.klardrop.common.communication.ConnectionMessenger]
   * — which then registers the pending ACK_RECEIVED under the **outer** TrustedMessage id.
   *
   * Pre-fix, the receiver's router unwrapped the envelope and sent its ACK using the
   * **inner** deserialized message's id. The sender saw "Unexpected ACK ... no matching
   * pending request" and timed out, even though the round-trip had functionally succeeded.
   *
   * Contract this test enforces: the wire-level ACK id must equal the outer TrustedMessage
   * id (whatever id the sender originally framed on the wire), so the sender's ack-tracking
   * matches.
   */
  @Test
  fun ackForTrustedMessageReferencesOuterEnvelopeIdNotInner() = runTest(testDispatcher) {
    val senderId = "sender01"
    val receiverId = "receiver01"
    val clock = com.carlom.klardrop.common.utils.Clock()
    val crypto = com.carlom.klardrop.common.trust.TrustCrypto()

    fun localPropsRepo(deviceId: String) = object : com.carlom.klardrop.common.persistence.LocalPropertiesRepository {
      override val properties = kotlinx.coroutines.flow.flowOf(
        com.carlom.klardrop.common.persistence.KlardropProperties(deviceId, deviceId)
      )
      override suspend fun getProperty() =
        com.carlom.klardrop.common.persistence.KlardropProperties(deviceId, deviceId)
      override suspend fun save(properties: com.carlom.klardrop.common.persistence.KlardropProperties) {}
      override suspend fun saveCustomDeviceName(customDeviceName: String?) {}
    }

    val senderStorage = com.carlom.klardrop.common.trust.InMemoryTrustStorage()
    val senderTrust = com.carlom.klardrop.common.trust.TrustManager(
      crypto = crypto,
      storage = senderStorage,
      clock = clock,
      currentDeviceProvider = com.carlom.klardrop.common.discovery.CurrentDeviceProvider(localPropsRepo(senderId)),
    )
    val receiverStorage = com.carlom.klardrop.common.trust.InMemoryTrustStorage()
    val receiverTrust = com.carlom.klardrop.common.trust.TrustManager(
      crypto = crypto,
      storage = receiverStorage,
      clock = clock,
      currentDeviceProvider = com.carlom.klardrop.common.discovery.CurrentDeviceProvider(localPropsRepo(receiverId)),
    )

    // Mutual pair so receiver has sender's ECDSA public key (required to verify) and
    // sender's storage has receiver's identity (consistency, not strictly used here).
    val pairingRequest = senderTrust.createPairingRequest(receiverId).getOrThrow()
    val pairingResponse = receiverTrust.createPairingAcceptance(pairingRequest).getOrThrow()
    senderTrust.finalizePairing(pairingResponse)

    // A handler is required for TEXT or onMessageIncoming would error out before the ACK
    // reply (the ack is sent AFTER the handler runs in the no-payload path).
    val mockHandler = MockMessageHandler<TextMessage, SimpleSendMessageRequest>()
    @Suppress("UNCHECKED_CAST")
    mockMessageHandlers.handlerToReturn = mockHandler as MessageHandler<Message, SendMessageRequest>

    val authorizer = object : IncomingAuthorizer(receiverTrust) {
      override suspend fun authorize(
        fromDeviceId: String,
        kind: TransferKind,
        headers: List<Message>,
        receiveFlow: MutableStateFlow<ReceiveMessageUpdate>,
        notifyAwaitingUser: suspend () -> Unit,
      ): Boolean = true
    }
    val router = MessagesRouterImpl(
      handlers = mockMessageHandlers,
      fileMessageHandler = com.carlom.klardrop.common.communication.message.FileMessageHandler(
        fileManager = object : com.carlom.klardrop.common.FileManager {
          override fun prepareSaveFile(fileName: String, mimeType: String) = error("unused")
          override fun getReadStreamFrom(file: PlatformFile): kotlinx.io.RawSource = error("unused")
          override suspend fun openFile(filePath: String) = false
          override suspend fun openUrl(url: String) = false
        },
        clock = clock,
        coroutines = mockCoroutines,
        messageRepository = mockMessageRepository,
      ),
      messageSerializer = mockMessageSerializer,
      coroutines = mockCoroutines,
      messengeReceiver = mockMessageReceiver,
      trustManager = receiverTrust,
      incomingAuthorizer = authorizer,
    )

    // Build the wire frame: TextMessage (inner id 999) signed into a TrustedMessage with
    // its own random outer id.
    val innerText = TextMessage(text = "hi from sender")
    val innerId = innerText.id
    val payload = mockMessageSerializer.serialize(innerText)
    val trustedMessage = senderTrust.signMessage(payload)
      ?: error("signMessage returned null — sender trust setup broken")
    val outerId = trustedMessage.id

    val readChannel = ByteReadChannel(createMessageBytes(trustedMessage))
    val writeChannel = ByteChannel(true)

    router.onMessageIncoming(senderId, writeChannel, readChannel, ackCallback = { })

    // Pull whatever the router wrote back. The router signs replies to trusted peers, so
    // the outermost frame is itself a TrustedMessage wrapping an ACK_RECEIVED. Unwrap.
    val replyFrame = writeChannel.readMessage(mockMessageSerializer)
    val ackMessage = if (replyFrame is TrustedMessage) {
      mockMessageSerializer.deserialize(replyFrame.payload)
    } else {
      replyFrame
    }
    assertTrue(ackMessage is MessageAcknowledgment, "Expected ACK, got ${ackMessage::class.simpleName}")
    assertEquals(AckType.RECEIVED, ackMessage.ackType)
    assertEquals(
      outerId, ackMessage.id,
      "ACK must reference the OUTER TrustedMessage id ($outerId) — that's what the sender " +
        "registered its pending-ACK channel under. Got ${ackMessage.id} (which equals inner=$innerId? ${ackMessage.id == innerId}).",
    )
    assertNotEquals(
      innerId, ackMessage.id,
      "ACK must NOT use the inner application message id; the sender doesn't track that one.",
    )
  }

  /**
   * Regression test for the heartbeat-deadlock observed when an iPad sent a file or text
   * to an unpaired Android peer:
   *
   *  1. iPad sends FILE header → Android router reads it
   *  2. Router calls IncomingAuthorizer.authorize → suspends on the user's tap
   *  3. While suspended, the read loop is parked, so iPad's PONG replies to Android's
   *     own heartbeat PINGs sit unread in the buffer
   *  4. Android's heartbeat hits its 5s timeout → connection torn down
   *  5. Prompt is still on screen but the link is dead; tapping accept does nothing
   *
   * The fix spawns the authorize-and-followup work in a router-scoped coroutine so the
   * read loop returns immediately and keeps draining the channel. This test replays that
   * sequence: a TEXT (or FILE) header from an untrusted peer hits a blocking authorizer,
   * then a PING arrives — the router must PONG it back even while the authorizer is
   * still waiting. Pre-fix the second `onMessageIncoming` call would never even start.
   */
  @Test
  fun authorizationDoesNotBlockSubsequentMessagesOnReadLoop() = runTest(testDispatcher) {
    val fromDeviceId = "untrusted-peer"
    val mockTrustManager = createMockTrustManager()
    // Authorizer that never resolves — simulates a user who hasn't tapped yet.
    val blockingAuthorizer = object : IncomingAuthorizer(mockTrustManager) {
      override suspend fun authorize(
        fromDeviceId: String,
        kind: TransferKind,
        headers: List<Message>,
        receiveFlow: MutableStateFlow<ReceiveMessageUpdate>,
        notifyAwaitingUser: suspend () -> Unit,
      ): Boolean {
        kotlinx.coroutines.awaitCancellation()
      }
    }
    val router = MessagesRouterImpl(
      handlers = mockMessageHandlers,
      fileMessageHandler = com.carlom.klardrop.common.communication.message.FileMessageHandler(
        fileManager = object : com.carlom.klardrop.common.FileManager {
          override fun prepareSaveFile(fileName: String, mimeType: String): com.carlom.klardrop.common.FileTransfer =
            error("not used")
          override fun getReadStreamFrom(file: PlatformFile): kotlinx.io.RawSource = error("not used")
          override suspend fun openFile(filePath: String): Boolean = false
          override suspend fun openUrl(url: String): Boolean = false
        },
        clock = com.carlom.klardrop.common.utils.Clock(),
        coroutines = mockCoroutines,
        messageRepository = mockMessageRepository,
      ),
      messageSerializer = mockMessageSerializer,
      coroutines = mockCoroutines,
      messengeReceiver = mockMessageReceiver,
      trustManager = mockTrustManager,
      incomingAuthorizer = blockingAuthorizer,
    )

    // First message: TEXT requiring authorization (which will block forever).
    val text = TextMessage(text = "blocked")
    val textBytes = createMessageBytes(text)
    val textRead = ByteReadChannel(textBytes)
    val writeChannel = ByteChannel(true)
    router.onMessageIncoming(fromDeviceId, writeChannel, textRead, ackCallback = { })

    // Second message arrives on the same connection — a heartbeat PING. Pre-fix, the
    // first call would still be parked in authorize() and we'd never get here at the
    // read-loop level. Even with the test calling onMessageIncoming directly, pre-fix
    // the first call wouldn't have returned, so reaching this line is itself part of
    // the proof. We additionally verify that the router PONGs the PING back.
    val ping = PingMessage(id = 91234)
    val pingRead = ByteReadChannel(createMessageBytes(ping))
    router.onMessageIncoming(fromDeviceId, writeChannel, pingRead, ackCallback = { })

    val reply = writeChannel.readMessage(mockMessageSerializer)
    assertTrue(reply is PongMessage, "Expected PONG while authorize is pending, got ${reply::class.simpleName}")
    assertEquals(ping.id, reply.pingId)
  }

  /**
   * Reactive mismatch path: when an unknown peer sends a TrustedMessage we cannot verify
   * (because we don't hold their ECDSA key — typically because we previously unpaired them),
   * the router must respond with a signed TrustRevocationMessage on the same connection so
   * the peer can clean up. Without this, the peer keeps believing we're paired and silent
   * asymmetric state lingers forever.
   */
  @Test
  fun trustedMessageFromUnknownSenderTriggersRevocationReply() = runTest(testDispatcher) {
    val senderId = "sender01"  // 8 chars so shortDeviceId matches
    val receiverId = "receivr1"
    val clock = com.carlom.klardrop.common.utils.Clock()
    val crypto = com.carlom.klardrop.common.trust.TrustCrypto()

    fun localPropsRepo(deviceId: String) = object : com.carlom.klardrop.common.persistence.LocalPropertiesRepository {
      override val properties = kotlinx.coroutines.flow.flowOf(
        com.carlom.klardrop.common.persistence.KlardropProperties(deviceId, deviceId)
      )
      override suspend fun getProperty() =
        com.carlom.klardrop.common.persistence.KlardropProperties(deviceId, deviceId)
      override suspend fun save(properties: com.carlom.klardrop.common.persistence.KlardropProperties) {}
      override suspend fun saveCustomDeviceName(customDeviceName: String?) {}
    }

    val senderStorage = com.carlom.klardrop.common.trust.InMemoryTrustStorage()
    val senderTrust = com.carlom.klardrop.common.trust.TrustManager(
      crypto = crypto,
      storage = senderStorage,
      clock = clock,
      currentDeviceProvider = com.carlom.klardrop.common.discovery.CurrentDeviceProvider(localPropsRepo(senderId)),
    )

    // Receiver: HAS identity (so it can sign a revocation back), but has NEVER paired with
    // the sender (so signature verification will fail with "unknown sender").
    val receiverStorage = com.carlom.klardrop.common.trust.InMemoryTrustStorage()
    val receiverTrust = com.carlom.klardrop.common.trust.TrustManager(
      crypto = crypto,
      storage = receiverStorage,
      clock = clock,
      currentDeviceProvider = com.carlom.klardrop.common.discovery.CurrentDeviceProvider(localPropsRepo(receiverId)),
    )
    receiverTrust.initialize()
    val receiverPublicKey = receiverStorage.getDevicePublicKey()!!
    // Sender pre-pairs the receiver locally — only on the sender's side — so the sender can
    // verify the revocation reply against the receiver's key. Stand-in for the original
    // pairing that happened before the receiver wiped their trust entry.
    senderStorage.storeECDSAKey(receiverId, receiverPublicKey)
    senderStorage.storeTrustedDevice(receiverId, byteArrayOf(0x1)) // marker; ECDH not exercised here

    val authorizer = object : IncomingAuthorizer(receiverTrust) {
      override suspend fun authorize(
        fromDeviceId: String,
        kind: TransferKind,
        headers: List<Message>,
        receiveFlow: MutableStateFlow<ReceiveMessageUpdate>,
        notifyAwaitingUser: suspend () -> Unit,
      ): Boolean = true
    }
    val router = MessagesRouterImpl(
      handlers = mockMessageHandlers,
      fileMessageHandler = com.carlom.klardrop.common.communication.message.FileMessageHandler(
        fileManager = object : com.carlom.klardrop.common.FileManager {
          override fun prepareSaveFile(fileName: String, mimeType: String) = error("unused")
          override fun getReadStreamFrom(file: PlatformFile): kotlinx.io.RawSource = error("unused")
          override suspend fun openFile(filePath: String) = false
          override suspend fun openUrl(url: String) = false
        },
        clock = clock,
        coroutines = mockCoroutines,
        messageRepository = mockMessageRepository,
      ),
      messageSerializer = mockMessageSerializer,
      coroutines = mockCoroutines,
      messengeReceiver = mockMessageReceiver,
      trustManager = receiverTrust,
      incomingAuthorizer = authorizer,
    )

    // Sender wraps a TextMessage in a signed TrustedMessage and ships it to the receiver.
    val innerText = TextMessage(text = "hello from forgotten peer")
    val payload = mockMessageSerializer.serialize(innerText)
    val signed = senderTrust.signMessage(payload)
      ?: error("signMessage returned null — sender trust setup broken")

    val readChannel = ByteReadChannel(createMessageBytes(signed))
    val writeChannel = ByteChannel(true)
    router.onMessageIncoming(senderId, writeChannel, readChannel, ackCallback = { })

    // Router should have written back a TrustRevocationMessage signed by the receiver,
    // targeting the sender.
    val reply = writeChannel.readMessage(mockMessageSerializer)
    assertTrue(
      reply is TrustRevocationMessage,
      "Expected TrustRevocationMessage from receiver, got ${reply::class.simpleName}",
    )
    assertEquals(receiverId, reply.senderId, "Revocation must be from the receiver")
    assertEquals(senderId, reply.targetDeviceId, "Revocation must target the unknown sender")
    // Confirm the revocation can be verified end-to-end by the sender (who knows the receiver).
    assertTrue(
      senderTrust.verifyRevocationMessage(reply),
      "Revocation reply must verify against the sender's stored receiver-key",
    )
  }
}
