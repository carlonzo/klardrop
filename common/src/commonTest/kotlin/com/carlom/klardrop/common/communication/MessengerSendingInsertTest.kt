package com.carlom.klardrop.common.communication

import FakeLocalPropertiesRepository
import TestCoroutines
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.database.File_transfers
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.mdns.FakeVisibleDevices
import com.carlom.klardrop.common.persistence.ChatMessage
import com.carlom.klardrop.common.persistence.DeliveryStatus
import com.carlom.klardrop.common.persistence.FileTransferStatus
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType
import com.carlom.klardrop.common.persistence.SendStatus
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.trust.InMemoryTrustStorage
import com.carlom.klardrop.common.trust.TrustCrypto
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.utils.Clock
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.RawSource
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Transfer UX: a text send while the peer is unpooled / still dialing must persist a SENDING
 * row (and emit Pending) *before* [Client.connectTo] succeeds. The chat bubble is observed
 * from this row — if insert waits on the socket, the composer looks dead until the peer is up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessengerSendingInsertTest {

  @Test
  fun textSend_unpooledPeer_insertsSendingRow_beforeConnectToSucceeds() = runTest {
    val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
    val coroutines = TestCoroutines(dispatcher = dispatcher, ioDispatcher = dispatcher)
    val peerId = "peer0001"
    val visibleDevices = FakeVisibleDevices()
    visibleDevices.addKlardropDevice(peerId, address = "10.0.0.2", port = 12345)

    val repository = RecordingChatRepository()
    val client = HangingClient()
    val messenger = MessengerImpl(
      visibleDevices = visibleDevices,
      connectionsPool = com.carlom.klardrop.common.FakeConnectionPool(),
      client = client,
      coroutines = coroutines,
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("self0001")),
      fileManager = UnusedFileManager(),
      messageReceiver = StubMessageReceiver(),
      trustManager = TrustManager(
        crypto = TrustCrypto(),
        storage = InMemoryTrustStorage(),
        clock = Clock(),
        currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("self0001")),
      ),
      messageSerializer = MessageSerializer(ProtoBuf, coroutines),
      messageRepository = repository,
      ackTimeoutConfig = AckTimeoutConfig(connectionWaitTimeout = 30.seconds, maxRetries = 0),
    )

    messenger.send(peerId, TextMessage(text = "hello from the composer").toSimpleSendRequest())
    // runCurrent, not advanceUntilIdle: awaitOrEstablishConnection is bounded by
    // withTimeout(connectionWaitTimeout), and advancing virtual time would fire that
    // timeout, flip the row to FAILED, and hide the SENDING window this test pins.
    runCurrent()

    assertEquals(
      1,
      client.connectToCalls,
      "the send should have started dialing the unpooled peer",
    )
    assertTrue(
      !client.connectFinished,
      "connectTo must still be hanging — this is the window the UI has to show SENDING",
    )
    assertEquals(
      listOf(SendStatus.SENDING),
      repository.insertStatuses,
      "exactly one SENDING insert, before connectTo returns (F12/F13: no second row on retry)",
    )
    val rows = repository.getMessagesForDevice(peerId, limit = 100).first()
    assertEquals(1, rows.size, "chat list must already contain the outgoing row")
    assertEquals("hello from the composer", rows.single().content)
    assertEquals(DeliveryStatus.SENDING, rows.single().deliveryStatus)
    assertTrue(rows.single().isSender)
  }

  private class HangingClient : Client {
    var connectToCalls = 0
    var connectFinished = false
    private val gate = CompletableDeferred<ConnectOutcome>()

    override suspend fun connectTo(deviceId: String): ConnectOutcome {
      connectToCalls++
      val outcome = gate.await()
      connectFinished = true
      return outcome
    }
  }

  private class RecordingChatRepository : MessageRepository {
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val insertStatuses = mutableListOf<SendStatus>()
    private var nextId = 1L

    override suspend fun insertMessage(
      remoteDeviceId: String,
      content: String,
      isSender: Boolean,
      messageType: MessageType,
      fileTransferId: Long?,
      isRead: Boolean,
      mimeType: String,
      messageId: Long?,
      sendStatus: SendStatus,
    ): Long {
      val id = nextId++
      insertStatuses += sendStatus
      val delivery = when (sendStatus) {
        SendStatus.FAILED -> DeliveryStatus.FAILED
        SendStatus.SENDING -> DeliveryStatus.SENDING
        SendStatus.SENT -> DeliveryStatus.SENT
      }
      messages.update {
        it + ChatMessage(
          id = id,
          remoteDeviceId = remoteDeviceId,
          content = content,
          timestamp = id,
          isSender = isSender,
          messageType = messageType.name,
          fileTransferId = fileTransferId,
          isRead = if (isRead) 1L else 0L,
          mimeType = mimeType,
          deliveryStatus = delivery,
        )
      }
      return id
    }

    override suspend fun updateMessageSendStatus(messageId: Long, status: SendStatus) {
      val delivery = when (status) {
        SendStatus.FAILED -> DeliveryStatus.FAILED
        SendStatus.SENDING -> DeliveryStatus.SENDING
        SendStatus.SENT -> DeliveryStatus.SENT
      }
      messages.update { rows ->
        rows.map { if (it.id == messageId) it.copy(deliveryStatus = delivery) else it }
      }
    }

    override suspend fun insertFileTransfer(
      fileName: String,
      filePath: String,
      totalSize: Long,
      status: FileTransferStatus,
      mimeType: String,
    ): Long = 0L

    override suspend fun updateFileTransferStatus(id: Long, status: FileTransferStatus) = Unit
    override suspend fun markStaleInProgressAsFailed() = Unit
    override fun getMessagesForDevice(remoteDeviceId: String, limit: Long): Flow<List<ChatMessage>> =
      messages
    override fun getFileTransferById(id: Long): Flow<File_transfers?> = emptyFlow()
    override suspend fun updateFileTransferFilePath(id: Long, filePath: String) = Unit
    override suspend fun markMessagesAsRead(remoteDeviceId: String) = Unit
    override suspend fun getUnreadCountForDevice(remoteDeviceId: String): Long = 0L
    override fun getAllDevicesWithUnreadCounts(): Flow<Map<String, Long>> = emptyFlow()
  }

  private class UnusedFileManager : FileManager {
    override fun prepareSaveFile(fileName: String, mimeType: String): com.carlom.klardrop.common.FileTransfer = error("unused")
    override fun getReadStreamFrom(file: PlatformFile): RawSource = error("unused")
    override suspend fun openFile(filePath: String): Boolean = false
    override suspend fun openUrl(url: String): Boolean = false
  }

  private class StubMessageReceiver : MessageReceiver {
    override fun onReceiveMessage(deviceId: String) =
      MutableStateFlow(ReceiveMessageUpdate(status = ReceiveMessageStatus.Started))
    override val notifier: Flow<Pair<String, StateFlow<ReceiveMessageUpdate>>> = emptyFlow()
    override val messageReceivedNotifier: Flow<ReceiveMessageUpdate> = emptyFlow()
  }
}
