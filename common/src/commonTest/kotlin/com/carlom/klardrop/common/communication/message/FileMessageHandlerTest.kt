package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileTransfer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.persistence.FileTransferStatus
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.carlom.klardrop.common.persistence.MessageType as PersistenceMessageType


@OptIn(ExperimentalCoroutinesApi::class)
class FileMessageHandlerTest {

  private lateinit var fileMessageHandler: FileMessageHandler
  private lateinit var mockMessageRepository: MockMessageRepository
  private lateinit var mockFileManager: MockFileManager
  private lateinit var realClock: Clock
  private lateinit var testDispatcher: TestDispatcher
  private lateinit var mockCoroutines: Coroutines

  @BeforeTest
  fun setup() {
    mockMessageRepository = MockMessageRepository()
    mockFileManager = MockFileManager()
    realClock = Clock()
    testDispatcher = UnconfinedTestDispatcher()
    mockCoroutines = object : Coroutines {
      override val ioDispatcher = testDispatcher
      override val mainDispatcher = testDispatcher
      override val cpuDispatcher = testDispatcher
      override val appScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
      override fun newScope() = kotlinx.coroutines.CoroutineScope(testDispatcher)
      override fun newScope(context: kotlin.coroutines.CoroutineContext) = kotlinx.coroutines.CoroutineScope(context)
    }

    fileMessageHandler = FileMessageHandler(
      fileManager = mockFileManager,
      clock = realClock,
      coroutines = mockCoroutines,
      messageRepository = mockMessageRepository,
    )
  }

  @Test
  fun beginReceiveOpensSinkAndReturnsPipeline() = runTest(testDispatcher) {
    val header = FileMessage("doc.bin", 100, "application/octet-stream")
    val flow = newReceiveFlow("peer-1")

    val pipeline = fileMessageHandler.beginReceive(header, "peer-1", flow)

    assertEquals("insertFileTransfer(doc.bin, , 100, IN_PROGRESS, application/octet-stream)", mockMessageRepository.calls[0])
    assertEquals("insertMessage(peer-1, doc.bin, false, FILE, 1, false, application/octet-stream)", mockMessageRepository.calls[1])
    assertEquals(2, mockMessageRepository.calls.size, "beginReceive must not finalize the transfer")
    assertEquals(false, pipeline.isFinished)
  }

  @Test
  fun acceptChunkWritesBytesAndCompleteFinalizesDb() = runTest(testDispatcher) {
    val payload = ByteArray(300) { (it % 256).toByte() }
    val header = FileMessage("doc.bin", payload.size.toLong(), "application/octet-stream")
    val flow = newReceiveFlow("peer-1")

    val pipeline = fileMessageHandler.beginReceive(header, "peer-1", flow)

    // Split into three chunks; only the last carries isLast.
    pipeline.acceptChunk(FileChunkMessage(header.id, seq = 0, data = payload.copyOfRange(0, 100), isLast = false))
    pipeline.acceptChunk(FileChunkMessage(header.id, seq = 1, data = payload.copyOfRange(100, 200), isLast = false))
    pipeline.acceptChunk(FileChunkMessage(header.id, seq = 2, data = payload.copyOfRange(200, 300), isLast = true))
    pipeline.complete()

    assertEquals(true, pipeline.isFinished)
    assertContentEquals(payload, mockFileManager.preparedFile!!.bytes())
    assertEquals("updateFileTransferStatus(1, COMPLETED)", mockMessageRepository.calls.last())
    assertTrue(flow.value.status is ReceiveMessageStatus.Completed)
  }

  @Test
  fun acceptChunkAfterCompleteIsDropped() = runTest(testDispatcher) {
    val header = FileMessage("a.bin", 10, "application/octet-stream")
    val flow = newReceiveFlow("peer-1")

    val pipeline = fileMessageHandler.beginReceive(header, "peer-1", flow)
    pipeline.acceptChunk(FileChunkMessage(header.id, seq = 0, data = ByteArray(10), isLast = true))
    pipeline.complete()

    // Late chunk arriving after complete must not throw or write.
    val before = mockFileManager.preparedFile!!.bytes().size
    pipeline.acceptChunk(FileChunkMessage(header.id, seq = 1, data = ByteArray(5), isLast = false))
    assertEquals(before, mockFileManager.preparedFile!!.bytes().size)
  }

  @Test
  fun pipelineFailUpdatesDbAndFlow() = runTest(testDispatcher) {
    val header = FileMessage("a.bin", 10, "application/octet-stream")
    val flow = newReceiveFlow("peer-1")

    val pipeline = fileMessageHandler.beginReceive(header, "peer-1", flow)
    pipeline.fail(RuntimeException("disk full"))

    assertEquals(true, pipeline.isFinished)
    assertEquals("updateFileTransferStatus(1, FAILED)", mockMessageRepository.calls.last())
    val status = flow.value.status
    assertTrue(status is ReceiveMessageStatus.Failed)
    assertEquals("disk full", status.reason)
  }

  @Test
  fun handleOutgoingChunkedSendsHeaderThenChunksWithLastFlag() = runTest(testDispatcher) {
    val payload = ByteArray(700_000) { (it % 256).toByte() } // > 2 chunks at 256KB
    val tempPath = Path("/tmp", "out.bin")
    val platformFile = PlatformFile(tempPath)
    mockFileManager.fileDataToServe[platformFile.path] = payload

    val header = FileMessage("out.bin", payload.size.toLong(), "application/octet-stream")
    val request = header.toSendRequest(platformFile)

    val sentMessages = mutableListOf<Message>()
    val progress = MutableSharedFlow<MessengerSendProgress>(extraBufferCapacity = 16)
    val collectedProgress = mutableListOf<MessengerSendProgress>()
    val collectJob = CoroutineScope(testDispatcher).launch {
      progress.collect { collectedProgress.add(it) }
    }

    fileMessageHandler.handleOutgoingChunked(
      toDeviceId = "peer-out",
      request = request,
      sendFramed = { sentMessages.add(it) },
      progressFlow = progress,
      awaitReady = { /* no-op: simulate ACK_READY arriving instantly */ },
    )

    collectJob.cancel()

    // First message is the header.
    assertTrue(sentMessages.first() is FileMessage, "First sent message must be the FileMessage header")
    val chunks = sentMessages.drop(1).filterIsInstance<FileChunkMessage>()
    assertEquals(sentMessages.size - 1, chunks.size, "All non-header sends must be chunks")
    assertTrue(chunks.size >= 3, "700KB at 256KB chunks should produce at least 3 chunks, got ${chunks.size}")
    // Exactly one chunk has isLast=true and it's the final one.
    assertEquals(1, chunks.count { it.isLast })
    assertEquals(true, chunks.last().isLast)
    // Reassembly equals original payload.
    val reassembled = ByteArray(payload.size).also { dest ->
      var off = 0
      for (c in chunks) { c.data.copyInto(dest, off); off += c.data.size }
    }
    assertContentEquals(payload, reassembled)
    // All chunks reference the header id.
    assertTrue(chunks.all { it.fileMessageId == header.id })
    // Progress flow saw 0% start and 100% end.
    assertTrue(collectedProgress.any { it is MessengerSendProgress.InProgress && it.percentage == 0 })
    assertTrue(collectedProgress.any { it is MessengerSendProgress.InProgress && it.percentage == 100 })
  }

  @Test
  fun handleOutgoingChunkedEmptyFileSendsSingleEmptyLastChunk() = runTest(testDispatcher) {
    val tempPath = Path("/tmp", "empty.bin")
    val platformFile = PlatformFile(tempPath)
    mockFileManager.fileDataToServe[platformFile.path] = ByteArray(0)

    val header = FileMessage("empty.bin", 0L, "application/octet-stream")
    val request = header.toSendRequest(platformFile)
    val sentMessages = mutableListOf<Message>()
    val progress = MutableSharedFlow<MessengerSendProgress>(extraBufferCapacity = 8)

    fileMessageHandler.handleOutgoingChunked(
      toDeviceId = "peer-out",
      request = request,
      sendFramed = { sentMessages.add(it) },
      progressFlow = progress,
      awaitReady = {},
    )

    val chunks = sentMessages.filterIsInstance<FileChunkMessage>()
    assertEquals(1, chunks.size, "Empty file should send exactly one chunk to terminate the transfer")
    assertEquals(0, chunks[0].data.size)
    assertEquals(true, chunks[0].isLast)
  }

  private fun newReceiveFlow(deviceId: String) = MutableStateFlow(
    ReceiveMessageUpdate(
      device = DeviceInfo(deviceId, "peer", DeviceType.DESKTOP),
      status = ReceiveMessageStatus.Started,
    )
  )
}

// --- Mocks ---
private class MockMessageRepository : MessageRepository {
  val calls = mutableListOf<String>()
  var nextFileTransferId = 1L
  var nextMessageId = 1L

  override suspend fun insertMessage(
    remoteDeviceId: String,
    content: String,
    isSender: Boolean,
    messageType: PersistenceMessageType,
    fileTransferId: Long?,
    isRead: Boolean,
    mimeType: String
  ) {
    calls.add("insertMessage($remoteDeviceId, $content, $isSender, $messageType, $fileTransferId, $isRead, $mimeType)")
  }

  override suspend fun insertFileTransfer(fileName: String, filePath: String, totalSize: Long, status: FileTransferStatus, mimeType: String): Long {
    calls.add("insertFileTransfer($fileName, $filePath, $totalSize, $status, $mimeType)")
    return nextFileTransferId++
  }

  override suspend fun updateFileTransferStatus(id: Long, status: FileTransferStatus) {
    calls.add("updateFileTransferStatus($id, $status)")
  }

  override suspend fun updateFileTransferFilePath(id: Long, filePath: String) {
    calls.add("updateFileTransferFilePath($id, $filePath)")
  }

  override suspend fun markMessagesAsRead(remoteDeviceId: String) {}
  override suspend fun getUnreadCountForDevice(remoteDeviceId: String): Long = 0L
  override fun getAllDevicesWithUnreadCounts(): kotlinx.coroutines.flow.Flow<Map<String, Long>> =
    kotlinx.coroutines.flow.flowOf(emptyMap())
  override fun getMessagesForDevice(remoteDeviceId: String, limit: Long) =
    kotlinx.coroutines.flow.flowOf(emptyList<com.carlom.klardrop.common.database.Messages>())
  override fun getFileTransferById(id: Long) =
    kotlinx.coroutines.flow.flowOf<com.carlom.klardrop.common.database.File_transfers?>(null)
}

private open class MockFileManager : FileManager {
  var preparedFile: MockFileTransfer? = null
  val fileDataToServe = mutableMapOf<String, ByteArray>()

  override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer {
    return MockFileTransfer().also { preparedFile = it }
  }

  override fun getReadStreamFrom(file: PlatformFile): RawSource {
    val data = fileDataToServe[file.path] ?: ByteArray(0)
    return Buffer().apply { write(data) }
  }

  override suspend fun openFile(filePath: String): Boolean = true
}

private class MockFileTransfer : FileTransfer {
  // Capture written bytes via a RawSink that survives close (Buffer.close discards).
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

  fun bytes(): ByteArray {
    val total = chunks.sumOf { it.size }
    val out = ByteArray(total)
    var off = 0
    for (c in chunks) { c.copyInto(out, off); off += c.size }
    return out
  }

  override suspend fun onTransferCompleted(): Path? = null
  override suspend fun onTransferFailed() {}
}

