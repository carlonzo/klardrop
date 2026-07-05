package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.communication.FrameCipher
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.database.File_transfers
import com.carlom.klardrop.common.persistence.ChatMessage
import com.carlom.klardrop.common.persistence.FileTransferStatus
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.SendStatus
import com.carlom.klardrop.common.utils.Coroutines
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import com.carlom.klardrop.common.persistence.MessageType as PersistenceMessageType
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V6 (docs/connection-review.md, F12): `handleOutgoing` must never persist anything itself —
 * that responsibility moved entirely to `Messenger.send`, which inserts a single SENDING row up
 * front and flips it to SENT/FAILED exactly once. Before the fix, `handleOutgoing` inserted a
 * SENT row BEFORE the socket write (and before any ACK), so a throwing write channel still left
 * a false "SENT" row on disk. These tests pin the fixed behavior: no repository call at all,
 * whether the write throws or succeeds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TextMessageHandlerTest {

  private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
  private val coroutines: Coroutines = object : Coroutines {
    override val ioDispatcher = testDispatcher
    override val mainDispatcher = testDispatcher
    override val cpuDispatcher = testDispatcher
    override val appScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
    override fun newScope() = kotlinx.coroutines.CoroutineScope(testDispatcher)
    override fun newScope(context: kotlin.coroutines.CoroutineContext) =
      kotlinx.coroutines.CoroutineScope(context)
  }
  private val serializer = MessageSerializer(ProtoBuf, coroutines)

  @Test
  fun handleOutgoing_neverPersists_evenWhenTheWriteThrows() = runTest(testDispatcher) {
    val recordingRepository = RecordingMessageRepository()
    val handler = TextMessageHandler(serializer, recordingRepository)

    // A channel cancelled with a cause before anything is written: any write against it throws.
    val channel = ByteChannel()
    channel.cancel(IllegalStateException("simulated socket failure"))

    val request = SimpleSendMessageRequest(TextMessage(text = "never persisted"))
    val progress = MutableSharedFlow<MessengerSendProgress>(extraBufferCapacity = 1)

    assertFailsWith<Throwable> {
      handler.handleOutgoing(
        toDeviceId = "peer-01",
        request = request,
        writeChannel = channel,
        progressFlow = progress,
        cipher = FrameCipher.Plain,
      )
    }

    assertTrue(
      recordingRepository.calls.isEmpty(),
      "handleOutgoing must never write to the repository — persistence belongs to Messenger.send " +
        "(docs/connection-review.md F12); got: ${recordingRepository.calls}",
    )
  }

  @Test
  fun handleOutgoing_neverPersists_evenOnASuccessfulWrite() = runTest(testDispatcher) {
    // Companion happy-path check: even when the write succeeds, handleOutgoing still must not
    // touch the repository — Messenger.send alone owns the single SENDING -> SENT/FAILED row.
    val recordingRepository = RecordingMessageRepository()
    val handler = TextMessageHandler(serializer, recordingRepository)

    val channel = ByteChannel(autoFlush = true)
    val request = SimpleSendMessageRequest(TextMessage(text = "written ok"))
    val progress = MutableSharedFlow<MessengerSendProgress>(extraBufferCapacity = 1)

    handler.handleOutgoing(
      toDeviceId = "peer-01",
      request = request,
      writeChannel = channel,
      progressFlow = progress,
      cipher = FrameCipher.Plain,
    )

    assertTrue(
      recordingRepository.calls.isEmpty(),
      "handleOutgoing must never persist, got: ${recordingRepository.calls}",
    )
  }
}

private class RecordingMessageRepository : MessageRepository {
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
  ) {
    calls.add("insertMessage($sendStatus)")
  }

  override suspend fun updateMessageSendStatus(messageId: Long, status: SendStatus) {
    calls.add("updateMessageSendStatus($status)")
  }

  override suspend fun insertFileTransfer(
    fileName: String,
    filePath: String,
    totalSize: Long,
    status: FileTransferStatus,
    mimeType: String,
  ): Long = 0L

  override suspend fun updateFileTransferStatus(id: Long, status: FileTransferStatus) {}
  override suspend fun updateFileTransferFilePath(id: Long, filePath: String) {}
  override suspend fun markStaleInProgressAsFailed() {}
  override suspend fun markMessagesAsRead(remoteDeviceId: String) {}
  override suspend fun getUnreadCountForDevice(remoteDeviceId: String): Long = 0L
  override fun getAllDevicesWithUnreadCounts(): Flow<Map<String, Long>> = flowOf(emptyMap())
  override fun getMessagesForDevice(remoteDeviceId: String, limit: Long): Flow<List<ChatMessage>> = flowOf(emptyList())
  override fun getFileTransferById(id: Long): Flow<File_transfers?> = flowOf(null)
}
