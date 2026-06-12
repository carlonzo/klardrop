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

  /**
   * Verdict contract (success): a clean transfer must report `true` from complete() so the
   * router sends ACK_RECEIVED. The router keys its terminal ACK off this boolean.
   */
  @Test
  fun completeReturnsTrueOnSuccessfulFinalize() = runTest(testDispatcher) {
    val payload = ByteArray(200) { (it % 256).toByte() }
    val header = FileMessage("ok.bin", payload.size.toLong(), "application/octet-stream")
    val flow = newReceiveFlow("peer-1")

    val pipeline = fileMessageHandler.beginReceive(header, "peer-1", flow)
    pipeline.acceptChunk(FileChunkMessage(header.id, seq = 0, data = payload, isLast = true))

    assertEquals(true, pipeline.complete(), "intact transfer must report true so the router sends ACK_RECEIVED")
    assertEquals("updateFileTransferStatus(1, COMPLETED)", mockMessageRepository.calls.last())
    assertTrue(flow.value.status is ReceiveMessageStatus.Completed)
  }

  /**
   * Verdict contract (integrity): a content-hash mismatch must report `false` so the router
   * sends ACK_REJECTED — NOT ACK_RECEIVED. Previously the router acked RECEIVED unconditionally
   * after complete() returned, so a corrupt transfer was acknowledged as a success.
   */
  @Test
  fun completeReturnsFalseOnContentHashMismatch() = runTest(testDispatcher) {
    val realCrypto = com.carlom.klardrop.common.trust.TrustCrypto()
    val payload = ByteArray(300) { (it % 256).toByte() }
    // Header advertises a hash that the delivered bytes won't match.
    val wrongHash = realCrypto.sha256(ByteArray(8))
    val header = FileMessage("x.bin", payload.size.toLong(), "application/octet-stream", contentHash = wrongHash)
    val flow = newReceiveFlow("peer-1")

    val pipeline = fileMessageHandler.beginReceive(header, "peer-1", flow)
    pipeline.acceptChunk(FileChunkMessage(header.id, seq = 0, data = payload, isLast = true))

    assertEquals(false, pipeline.complete(), "integrity mismatch must report false so the router sends ACK_REJECTED")
    assertEquals("updateFileTransferStatus(1, FAILED)", mockMessageRepository.calls.last())
    assertTrue(flow.value.status is ReceiveMessageStatus.Failed)
  }

  /**
   * Verdict contract (finalize failure): when onTransferCompleted() throws — the exact macOS
   * sandbox bug, `mkdir failed: Operation not permitted` — complete() must NOT propagate the
   * exception (a throw would bubble to the connection read loop and tear down the whole
   * connection, starving the sender into a retry storm). It must roll back, mark FAILED, and
   * report `false` so the router sends a terminal ACK_REJECTED and the sender fails fast.
   */
  @Test
  fun completeReturnsFalseAndDoesNotThrowWhenFinalizeFails() = runTest(testDispatcher) {
    mockFileManager.completeError = kotlinx.io.IOException("mkdir failed: Operation not permitted")
    val payload = ByteArray(120) { (it % 256).toByte() }
    val header = FileMessage("y.bin", payload.size.toLong(), "application/octet-stream")
    val flow = newReceiveFlow("peer-1")

    val pipeline = fileMessageHandler.beginReceive(header, "peer-1", flow)
    pipeline.acceptChunk(FileChunkMessage(header.id, seq = 0, data = payload, isLast = true))

    // Must not throw.
    val verdict = pipeline.complete()

    assertEquals(false, verdict, "a finalize failure must report false, not throw")
    assertEquals("updateFileTransferStatus(1, FAILED)", mockMessageRepository.calls.last())
    assertTrue(flow.value.status is ReceiveMessageStatus.Failed)
    assertTrue(mockFileManager.preparedFile!!.failedCalled, "a finalize failure must roll back via onTransferFailed()")
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

  /**
   * Regression test: chunks must hash to the value the (signed) header committed to.
   * If the bytes don't match, the receive pipeline marks the transfer FAILED and rolls
   * back the sink — even though every chunk arrived without I/O error. This is the
   * mechanism that lets us skip per-chunk ECDSA without losing integrity.
   */
  @Test
  fun completeFailsTransferWhenChunkBytesDontMatchHeaderContentHash() = runTest(testDispatcher) {
    val realCrypto = com.carlom.klardrop.common.trust.TrustCrypto()
    val originalBytes = ByteArray(300) { (it % 256).toByte() }
    val tamperedBytes = originalBytes.copyOf().also { it[100] = (it[100] + 1).toByte() }

    val header = FileMessage(
      fileName = "tampered.bin",
      fileSize = originalBytes.size.toLong(),
      mimeType = "application/octet-stream",
      contentHash = realCrypto.sha256(originalBytes),
    )
    val flow = newReceiveFlow("peer-1")

    val pipeline = fileMessageHandler.beginReceive(header, "peer-1", flow)
    // Feed the TAMPERED bytes — simulates an attacker (or transit corruption) modifying
    // chunk content while leaving the (signed) header alone.
    pipeline.acceptChunk(FileChunkMessage(header.id, seq = 0, data = tamperedBytes.copyOfRange(0, 100), isLast = false))
    pipeline.acceptChunk(FileChunkMessage(header.id, seq = 1, data = tamperedBytes.copyOfRange(100, 200), isLast = false))
    pipeline.acceptChunk(FileChunkMessage(header.id, seq = 2, data = tamperedBytes.copyOfRange(200, 300), isLast = true))
    pipeline.complete()

    assertEquals(true, pipeline.isFinished, "complete() must mark pipeline finished even on hash failure")
    assertEquals(
      "updateFileTransferStatus(1, FAILED)", mockMessageRepository.calls.last(),
      "Hash mismatch must record the transfer as FAILED, not COMPLETED",
    )
    val status = flow.value.status
    assertTrue(status is ReceiveMessageStatus.Failed, "expected Failed, got $status")
    assertTrue(
      status.reason.contains("integrity", ignoreCase = true),
      "Failure reason should mention the integrity check, got '${status.reason}'",
    )
  }

  /**
   * Happy path: header carries SHA-256(file), chunks deliver matching bytes, complete()
   * passes the hash check and finalizes COMPLETED.
   */
  @Test
  fun completeMatchesContentHashOnHappyPath() = runTest(testDispatcher) {
    val realCrypto = com.carlom.klardrop.common.trust.TrustCrypto()
    val payload = ByteArray(300) { (it % 256).toByte() }
    val header = FileMessage(
      fileName = "ok.bin",
      fileSize = payload.size.toLong(),
      mimeType = "application/octet-stream",
      contentHash = realCrypto.sha256(payload),
    )
    val flow = newReceiveFlow("peer-1")

    val pipeline = fileMessageHandler.beginReceive(header, "peer-1", flow)
    pipeline.acceptChunk(FileChunkMessage(header.id, seq = 0, data = payload.copyOfRange(0, 150), isLast = false))
    pipeline.acceptChunk(FileChunkMessage(header.id, seq = 1, data = payload.copyOfRange(150, 300), isLast = true))
    pipeline.complete()

    assertEquals("updateFileTransferStatus(1, COMPLETED)", mockMessageRepository.calls.last())
    assertTrue(flow.value.status is ReceiveMessageStatus.Completed)
  }

  /**
   * Backward-compat: a header without contentHash (legacy peer pre-dating the field) still
   * completes — receiver logs a WARN but doesn't fail. This is the only safe behavior since
   * we can't retroactively gain integrity over bytes we have no commitment to.
   */
  @Test
  fun completeAcceptsHeaderWithoutContentHashForBackwardCompat() = runTest(testDispatcher) {
    val payload = ByteArray(300) { (it % 256).toByte() }
    val header = FileMessage(
      fileName = "legacy.bin",
      fileSize = payload.size.toLong(),
      mimeType = "application/octet-stream",
      contentHash = null,
    )
    val flow = newReceiveFlow("peer-1")

    val pipeline = fileMessageHandler.beginReceive(header, "peer-1", flow)
    pipeline.acceptChunk(FileChunkMessage(header.id, seq = 0, data = payload, isLast = true))
    pipeline.complete()

    assertEquals("updateFileTransferStatus(1, COMPLETED)", mockMessageRepository.calls.last())
    assertTrue(flow.value.status is ReceiveMessageStatus.Completed)
  }

  /**
   * Per-chunk MAC happy path: when both peers share a secret (post-pairing-with-secret
   * persistence), the sender's chunkMacFn returns a tag for each chunk and the receiver's
   * verifier accepts them all. The pipeline must finalize COMPLETED with no fallback to
   * content-hash.
   */
  @Test
  fun chunkMacHappyPathFinalizesCompleted() = runTest(testDispatcher) {
    val sharedSecret = ByteArray(32) { it.toByte() } // deterministic stand-in
    val realCrypto = com.carlom.klardrop.common.trust.TrustCrypto()
    val info = "klardrop-chunk-mac-v1".encodeToByteArray()
    val macKey = realCrypto.hkdfSha256(sharedSecret, info)

    val payload = ByteArray(300) { (it * 7 % 256).toByte() }
    val header = FileMessage(
      fileName = "macd.bin",
      fileSize = payload.size.toLong(),
      mimeType = "application/octet-stream",
      // Note: contentHash NOT set — MAC mode supersedes it.
    )
    val flow = newReceiveFlow("peer-1")

    val verifyMac: suspend (FileChunkMessage) -> Boolean = { chunk ->
      val tag = chunk.mac
      tag != null && realCrypto.verifyHmacSha256(
        key = macKey,
        data = chunkMacInput(chunk.fileMessageId, chunk.seq, chunk.isLast, chunk.data),
        tag = tag,
      )
    }
    val pipeline = fileMessageHandler.beginReceive(header, "peer-1", flow, verifyChunkMac = verifyMac)

    suspend fun macFor(chunk: FileChunkMessage): ByteArray = realCrypto.hmacSha256(
      key = macKey,
      data = chunkMacInput(chunk.fileMessageId, chunk.seq, chunk.isLast, chunk.data),
    )

    val c0 = FileChunkMessage(header.id, seq = 0, data = payload.copyOfRange(0, 150), isLast = false)
    pipeline.acceptChunk(c0.copy(mac = macFor(c0)))
    val c1 = FileChunkMessage(header.id, seq = 1, data = payload.copyOfRange(150, 300), isLast = true)
    pipeline.acceptChunk(c1.copy(mac = macFor(c1)))
    pipeline.complete()

    assertEquals("updateFileTransferStatus(1, COMPLETED)", mockMessageRepository.calls.last())
    assertTrue(flow.value.status is ReceiveMessageStatus.Completed)
    assertContentEquals(payload, mockFileManager.preparedFile!!.bytes())
  }

  /**
   * Tampered chunk: byte flip mid-stream. The chunk's MAC won't match → pipeline marks
   * macFailureSeq and complete() rolls back to FAILED. The tampered bytes must NOT be
   * written to the sink.
   */
  @Test
  fun chunkMacFailsTransferOnTamperedChunk() = runTest(testDispatcher) {
    val sharedSecret = ByteArray(32) { it.toByte() }
    val realCrypto = com.carlom.klardrop.common.trust.TrustCrypto()
    val macKey = realCrypto.hkdfSha256(sharedSecret, "klardrop-chunk-mac-v1".encodeToByteArray())

    val payload = ByteArray(300) { (it * 7 % 256).toByte() }
    val header = FileMessage("tampered.bin", payload.size.toLong(), "application/octet-stream")
    val flow = newReceiveFlow("peer-1")

    val pipeline = fileMessageHandler.beginReceive(
      header, "peer-1", flow,
      verifyChunkMac = { chunk ->
        val tag = chunk.mac
        tag != null && realCrypto.verifyHmacSha256(
          key = macKey,
          data = chunkMacInput(chunk.fileMessageId, chunk.seq, chunk.isLast, chunk.data),
          tag = tag,
        )
      },
    )

    suspend fun macFor(chunk: FileChunkMessage): ByteArray = realCrypto.hmacSha256(
      key = macKey,
      data = chunkMacInput(chunk.fileMessageId, chunk.seq, chunk.isLast, chunk.data),
    )

    // Chunk 0: clean, MAC computed over the original bytes.
    val original0 = FileChunkMessage(header.id, seq = 0, data = payload.copyOfRange(0, 150), isLast = false)
    val tag0 = macFor(original0)
    // Then we ship the chunk with TAMPERED data but the original MAC — simulating an
    // attacker who can flip bytes in transit but doesn't hold the HMAC key.
    val tampered0 = original0.copy(
      data = original0.data.copyOf().also { it[42] = (it[42] + 1).toByte() },
      mac = tag0,
    )
    pipeline.acceptChunk(tampered0)
    // Chunk 1: clean (we're testing that one bad chunk is enough to fail).
    val c1 = FileChunkMessage(header.id, seq = 1, data = payload.copyOfRange(150, 300), isLast = true)
    pipeline.acceptChunk(c1.copy(mac = macFor(c1)))

    pipeline.complete()

    assertEquals("updateFileTransferStatus(1, FAILED)", mockMessageRepository.calls.last())
    assertTrue(flow.value.status is ReceiveMessageStatus.Failed)
    // Tampered bytes should NOT be in the sink — pipeline aborted writes after the MAC
    // failure on chunk 0, and chunk 1 was rejected because macFailureSeq was already set.
    assertEquals(0, mockFileManager.preparedFile!!.bytes().size)
  }

  /**
   * Anti-downgrade: when the receiver has a shared secret with the peer (verifyChunkMac
   * non-null), a chunk arriving WITHOUT a mac field counts as failure. An attacker can't
   * strip the MAC to bypass verification — the receiver decides verification mode based
   * on its own trust state, not on what the sender chose to include.
   */
  @Test
  fun chunkMacRejectsMissingMacWhenPeerHasSharedSecret() = runTest(testDispatcher) {
    val payload = ByteArray(150) { it.toByte() }
    val header = FileMessage("downgrade.bin", payload.size.toLong(), "application/octet-stream")
    val flow = newReceiveFlow("peer-1")

    val pipeline = fileMessageHandler.beginReceive(
      header, "peer-1", flow,
      verifyChunkMac = { chunk -> chunk.mac != null }, // rejects null-mac
    )

    val chunkWithoutMac = FileChunkMessage(header.id, seq = 0, data = payload, isLast = true, mac = null)
    pipeline.acceptChunk(chunkWithoutMac)
    pipeline.complete()

    assertEquals("updateFileTransferStatus(1, FAILED)", mockMessageRepository.calls.last())
    assertTrue(flow.value.status is ReceiveMessageStatus.Failed)
  }

  /** Shared chunk-MAC input layout that mirrors TrustManager.chunkMacInput. */
  private fun chunkMacInput(fileMessageId: Int, seq: Int, isLast: Boolean, data: ByteArray): ByteArray {
    val out = ByteArray(4 + 4 + 1 + data.size)
    out[0] = (fileMessageId ushr 24).toByte()
    out[1] = (fileMessageId ushr 16).toByte()
    out[2] = (fileMessageId ushr 8).toByte()
    out[3] = fileMessageId.toByte()
    out[4] = (seq ushr 24).toByte()
    out[5] = (seq ushr 16).toByte()
    out[6] = (seq ushr 8).toByte()
    out[7] = seq.toByte()
    out[8] = if (isLast) 1 else 0
    data.copyInto(out, 9)
    return out
  }

  /**
   * Sender side: handleOutgoingChunked must compute SHA-256 of the file and emit it on
   * the header, so the receiver has something to verify against. The header field starts
   * out null on the request and must be populated by the time it's framed on the wire.
   */
  @Test
  fun handleOutgoingChunkedComputesAndEmitsContentHashOnHeader() = runTest(testDispatcher) {
    val realCrypto = com.carlom.klardrop.common.trust.TrustCrypto()
    val payload = ByteArray(1024) { (it % 256).toByte() }
    val expectedHash = realCrypto.sha256(payload)

    val tempPath = Path("/tmp", "hashed.bin")
    val platformFile = PlatformFile(tempPath)
    mockFileManager.fileDataToServe[platformFile.path] = payload

    // Caller's request still has contentHash = null — it's the handler's job to compute it.
    val header = FileMessage("hashed.bin", payload.size.toLong(), "application/octet-stream", contentHash = null)
    val request = header.toSendRequest(platformFile)

    val sentMessages = mutableListOf<Message>()
    val progress = MutableSharedFlow<MessengerSendProgress>(extraBufferCapacity = 16)

    fileMessageHandler.handleOutgoingChunked(
      toDeviceId = "peer-out",
      request = request,
      sendFramed = { sentMessages.add(it) },
      progressFlow = progress,
      awaitReady = {},
    )

    val sentHeader = sentMessages.first() as FileMessage
    assertContentEquals(
      expectedHash, sentHeader.contentHash,
      "handleOutgoingChunked must populate the header's contentHash with SHA-256 of the file content",
    )
    // The id must be preserved so the receiver's chunk-pipeline lookup (keyed by header id)
    // and the chunks (which carry fileMessageId == header.id) line up.
    assertEquals(header.id, sentHeader.id)
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
    mimeType: String,
    sendStatus: com.carlom.klardrop.common.persistence.MessageSendStatus?,
  ): Long {
    calls.add("insertMessage($remoteDeviceId, $content, $isSender, $messageType, $fileTransferId, $isRead, $mimeType)")
    return nextMessageId++
  }

  override suspend fun updateMessageSendStatus(id: Long, status: com.carlom.klardrop.common.persistence.MessageSendStatus) {
    calls.add("updateMessageSendStatus($id, $status)")
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

  override suspend fun markStaleInProgressAsFailed() {
    calls.add("markStaleInProgressAsFailed()")
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

  /** When set, the next prepared transfer's onTransferCompleted() throws this — simulates a
   *  finalize/storage failure (e.g. the macOS sandbox `mkdir failed: Operation not permitted`). */
  var completeError: Throwable? = null

  override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer {
    return MockFileTransfer(completeError).also { preparedFile = it }
  }

  override fun getReadStreamFrom(file: PlatformFile): RawSource {
    val data = fileDataToServe[file.path] ?: ByteArray(0)
    return Buffer().apply { write(data) }
  }

  override suspend fun openFile(filePath: String): Boolean = true
  override suspend fun openUrl(url: String): Boolean = true
}

private class MockFileTransfer(private val completeError: Throwable? = null) : FileTransfer {
  var failedCalled = false
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

  override suspend fun onTransferCompleted(): Path? {
    completeError?.let { throw it }
    return null
  }
  override suspend fun onTransferFailed() { failedCalled = true }
}

