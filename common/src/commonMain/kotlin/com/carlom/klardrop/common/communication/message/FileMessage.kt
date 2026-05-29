package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileTransfer
import com.carlom.klardrop.common.communication.FrameCipher
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.persistence.FileTransferStatus
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType as PersistenceMessageType
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.invoke
import kotlinx.io.buffered
import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.random.Random

/**
 * Chunk size for file transfer over TCP. 256KB strikes a good balance:
 * - large enough that per-chunk Kotlin/coroutine overhead (log, progress emit, StateFlow update)
 *   is amortized across ~256x more bytes than the old 32KB
 * - small enough that progress reporting still feels responsive at LAN speeds
 * - within a typical TCP window so it doesn't get fragmented into multiple round-trips
 */
internal const val FILE_CHUNK_SIZE = 256 * 1024

/** Min interval between progress emissions. Caps emission rate at ~10/sec. */
private const val PROGRESS_EMIT_INTERVAL_MS = 100L

/** Min percentage delta between progress emissions. */
private const val PROGRESS_EMIT_PERCENT_DELTA = 5

/**
 * Header that announces an upcoming file transfer. The actual bytes flow as a sequence of
 * [FileChunkMessage]s keyed by this header's [id]. The receiver opens its sink and registers
 * a [FileReceivePipeline] when the header arrives, then chunks are streamed independently —
 * unrelated framed messages (PING, ACK, TEXT, even other concurrent FILE_CHUNK transfers) may
 * interleave between chunks since each chunk is its own framed message on the wire.
 */
@Serializable
data class FileMessage(
  val fileName: String,
  val fileSize: Long,
  val mimeType: String,
  /**
   * SHA-256 of the file's bytes, computed by the sender before the header goes on the wire.
   * Binds the (signed) header to the content that follows so chunks can flow unsigned without
   * losing end-to-end integrity — the receiver hashes as it accumulates and verifies before
   * marking the transfer COMPLETED. Null on legacy headers from peers that pre-date this
   * field; receivers fall back to "no integrity check" with a warn log in that case.
   */
  val contentHash: ByteArray? = null,
  override val id: Int = Random.nextInt(),
) : Message() {
  override val type: MessageType = MessageType.FILE
  override val hasPayload: Boolean = true

  data class FileSendRequest(
    override val message: FileMessage,
    val file: PlatformFile,
    override val messageSignature: MessageSignature? = null
  ) : SignedSendMessageRequest

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is FileMessage) return false
    if (fileName != other.fileName) return false
    if (fileSize != other.fileSize) return false
    if (mimeType != other.mimeType) return false
    if (id != other.id) return false
    if (!contentHash.contentEqualsNullable(other.contentHash)) return false
    return true
  }

  override fun hashCode(): Int {
    var result = fileName.hashCode()
    result = 31 * result + fileSize.hashCode()
    result = 31 * result + mimeType.hashCode()
    result = 31 * result + id
    result = 31 * result + (contentHash?.contentHashCode() ?: 0)
    return result
  }

  private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean {
    if (this == null && other == null) return true
    if (this == null || other == null) return false
    return this.contentEquals(other)
  }
}

fun FileMessage.toSendRequest(file: PlatformFile, messageSignature: MessageSignature? = null): FileMessage.FileSendRequest {
  return FileMessage.FileSendRequest(this, file, messageSignature)
}

/**
 * One chunk of a chunked file transfer. Each chunk is an independent framed message on the wire,
 * so the writer mutex is held only for the duration of writing this single chunk's bytes — not
 * for the whole transfer. That lets pings, acks, and other messages naturally interleave.
 *
 * [fileMessageId] correlates back to the [FileMessage.id] of the header that opened the transfer.
 * [seq] is purely diagnostic (TCP delivers in order); the receiver doesn't reorder by seq.
 * [isLast] tells the receiver to close the sink and finalize the transfer.
 */
@Serializable
data class FileChunkMessage(
  val fileMessageId: Int,
  val seq: Int,
  val data: ByteArray,
  val isLast: Boolean = false,
  /**
   * HMAC-SHA256 tag over `fileMessageId || seq || isLast || data`, keyed by the per-pair
   * key derived from the ECDH shared secret (see [com.carlom.klardrop.common.trust.TrustManager.computeChunkMac]).
   * Present when the sender is paired with the destination — the receiver verifies it
   * synchronously per chunk and fails the transfer on mismatch, which gives us per-chunk
   * authenticity & integrity at HMAC speed (~µs) rather than per-chunk ECDSA speed (~ms).
   *
   * Null when the sender has no shared secret with the peer (legacy pairing pre-dating
   * the persisted secret, or peer not trusted): the chunked-send path falls back to
   * "no per-chunk auth" and the file body is unauthenticated — which is fine for
   * untrusted-peer transfers (those go through manual user accept anyway).
   */
  val mac: ByteArray? = null,
  override val id: Int = Random.nextInt(),
) : Message() {
  override val type: MessageType = MessageType.FILE_CHUNK
  override val hasPayload: Boolean = false

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is FileChunkMessage) return false
    if (fileMessageId != other.fileMessageId) return false
    if (seq != other.seq) return false
    if (isLast != other.isLast) return false
    if (id != other.id) return false
    if (!data.contentEquals(other.data)) return false
    if (mac == null && other.mac != null) return false
    if (mac != null && other.mac == null) return false
    if (mac != null && other.mac != null && !mac.contentEquals(other.mac)) return false
    return true
  }

  override fun hashCode(): Int {
    var result = fileMessageId
    result = 31 * result + seq
    result = 31 * result + isLast.hashCode()
    result = 31 * result + id
    result = 31 * result + data.contentHashCode()
    result = 31 * result + (mac?.contentHashCode() ?: 0)
    return result
  }
}

/**
 * Receive-side state for an in-flight chunked file transfer. The router creates one of these
 * via [FileMessageHandler.beginReceive] when the header arrives, stores it keyed by the header's
 * id, and feeds incoming [FileChunkMessage]s into [acceptChunk]. When [FileChunkMessage.isLast]
 * arrives, the router calls [complete] (or [fail] on error) and removes the pipeline from the map.
 *
 * Not thread-safe: the router serializes all chunk callbacks for a given pipeline by virtue of
 * processing incoming frames one at a time on the connection's read loop.
 */
class FileReceivePipeline internal constructor(
  val header: FileMessage,
  private val fromDeviceId: String,
  private val fileTransferId: Long,
  private val fileTransfer: FileTransfer,
  private val receiveFlow: MutableStateFlow<ReceiveMessageUpdate>,
  private val messageRepository: MessageRepository,
  private val clock: Clock,
  private val crypto: com.carlom.klardrop.common.trust.TrustCrypto = com.carlom.klardrop.common.trust.TrustCrypto(),
  /**
   * Verifier for per-chunk MACs. The router supplies a closure backed by
   * [com.carlom.klardrop.common.trust.TrustManager.verifyChunkMac]; null when this peer
   * has no shared secret with us (legacy pairing or untrusted), in which case the
   * pipeline falls back to verifying [FileMessage.contentHash] over the assembled bytes
   * at completion time.
   */
  private val verifyChunkMac: (suspend (chunk: FileChunkMessage) -> Boolean)? = null,
) {
  private val sink = fileTransfer.bufferedSink
  private var totalReceived = 0L
  private var lastEmitTime = 0L
  private var lastEmitPercent = -1
  private val recvStart = clock.currentTimeMillis()
  // Accumulator for SHA-256 of the bytes received. Used only when the sender went via
  // the option-2 path (MAC unavailable) and the header carries a content-hash — we feed
  // every chunk into it as it arrives so we have the digest at completion time without
  // re-reading the file. With MAC-mode this is unused and we save the work.
  private val hashAccumulator = crypto.sha256Accumulator()
  // Set when a chunk fails MAC verification so complete() takes the failure branch.
  private var macFailureSeq: Int = -1

  /** Returns true after [complete] or [fail] has been called; further chunks must be dropped. */
  var isFinished: Boolean = false
    private set

  suspend fun acceptChunk(chunk: FileChunkMessage) {
    if (isFinished) {
      log("FileReceivePipeline", "Dropping chunk seq=${chunk.seq} for finished transfer ${header.id}")
      return
    }
    if (macFailureSeq >= 0) {
      // A previous chunk already failed verification; ignore the rest. complete() will
      // mark the transfer FAILED. Cheaper than re-verifying the tail and consistent with
      // "first failure wins" diagnostics.
      return
    }
    if (verifyChunkMac != null) {
      // MAC-mode: peer is trusted (shared secret on file). Every chunk must arrive with a
      // valid HMAC tag — anti-downgrade decision is local-only, an attacker can't suppress
      // verification by stripping the field. mac=null counts as failure here.
      val tagOk = chunk.mac != null && verifyChunkMac.invoke(chunk)
      if (!tagOk) {
        macFailureSeq = chunk.seq
        log(
          "FileReceivePipeline",
          "INTEGRITY: chunk seq=${chunk.seq} for ${header.fileName} from $fromDeviceId failed MAC verification (mac=${if (chunk.mac == null) "missing" else "mismatch"})",
        )
        return
      }
    } else {
      // No-MAC mode: fall back to accumulating SHA-256 for the content-hash check at end.
      hashAccumulator.update(chunk.data, 0, chunk.data.size)
    }
    sink.write(chunk.data, 0, chunk.data.size)
    totalReceived += chunk.data.size

    val progressValue = if (header.fileSize > 0) {
      ((totalReceived * 100L) / header.fileSize).toInt().coerceIn(0, 100)
    } else 100
    val now = clock.currentTimeMillis()
    if (progressValue >= lastEmitPercent + PROGRESS_EMIT_PERCENT_DELTA ||
        now - lastEmitTime >= PROGRESS_EMIT_INTERVAL_MS) {
      lastEmitTime = now
      lastEmitPercent = progressValue
      receiveFlow.update {
        it.copy(status = ReceiveMessageStatus.Progress(listOf(header to progressValue)))
      }
    }
  }

  suspend fun complete() {
    if (isFinished) return
    isFinished = true
    runCatching { sink.close() }

    // Integrity verdict:
    //   1. If any chunk failed MAC verification → fail the transfer.
    //   2. If we're in MAC mode (verifyChunkMac != null) and got here → all chunks
    //      passed individually, transfer is authentic + intact.
    //   3. Else (no shared secret with peer): fall back to the option-2 SHA-256 over
    //      the assembled file, compared against the (signed) header's contentHash. Catch
    //      the legacy / untrusted-peer case.
    if (macFailureSeq >= 0) {
      runCatching { fileTransfer.onTransferFailed() }
      messageRepository.updateFileTransferStatus(fileTransferId, FileTransferStatus.FAILED)
      receiveFlow.update {
        it.copy(status = ReceiveMessageStatus.Failed("File integrity check failed (chunk $macFailureSeq)"))
      }
      return
    }

    if (verifyChunkMac == null) {
      val expectedHash = header.contentHash
      if (expectedHash != null) {
        val actualHash = hashAccumulator.digest()
        if (!expectedHash.contentEquals(actualHash)) {
          log(
            "FileReceivePipeline",
            "INTEGRITY: content hash mismatch for ${header.fileName} from ${header.id}; rolling back. " +
              "expected=${expectedHash.take(8).toByteArray().toHexShort()} actual=${actualHash.take(8).toByteArray().toHexShort()}",
          )
          runCatching { fileTransfer.onTransferFailed() }
          messageRepository.updateFileTransferStatus(fileTransferId, FileTransferStatus.FAILED)
          receiveFlow.update {
            it.copy(status = ReceiveMessageStatus.Failed("File integrity check failed"))
          }
          return
        }
      } else {
        log(
          "FileReceivePipeline",
          "WARN: no MAC and no contentHash for ${header.fileName} from $fromDeviceId — accepting without integrity check (legacy or untrusted)",
        )
      }
    }

    val finalPath = fileTransfer.onTransferCompleted()
    if (finalPath != null) {
      messageRepository.updateFileTransferFilePath(fileTransferId, finalPath.toString())
    }
    messageRepository.updateFileTransferStatus(fileTransferId, FileTransferStatus.COMPLETED)
    receiveFlow.update { it.copy(status = ReceiveMessageStatus.Completed) }
    val durationMs = clock.currentTimeMillis() - recvStart
    val kbPerSec = if (durationMs > 0) (totalReceived * 1000 / durationMs / 1024) else 0
    log("FileReceivePipeline", "Received ${header.fileName} ($totalReceived bytes) in ${durationMs}ms ($kbPerSec KB/s)")
  }

  private fun ByteArray.toHexShort(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

  suspend fun fail(error: Throwable) {
    if (isFinished) return
    isFinished = true
    runCatching { sink.close() }
    fileTransfer.onTransferFailed()
    messageRepository.updateFileTransferStatus(fileTransferId, FileTransferStatus.FAILED)
    receiveFlow.update {
      it.copy(status = ReceiveMessageStatus.Failed(error.message ?: "Unknown error"))
    }
  }
}

/**
 * File transfer handler. Note that, unlike other [MessageHandler]s, the standard
 * [handleIncoming] / [handleOutgoing] / [handleOutgoingWithReadyAck] paths are NOT used for
 * FILE messages — the router invokes [beginReceive] (incoming header) and [handleOutgoingChunked]
 * (outgoing) directly because the chunked wire format requires per-chunk framing rather than
 * one continuous payload write under a single mutex hold.
 *
 * Those overridden methods only exist to satisfy the [MessageHandler] interface; the router
 * never reaches them for FILE messages.
 */
class FileMessageHandler(
  private val fileManager: FileManager,
  private val clock: Clock,
  private val coroutines: Coroutines,
  private val messageRepository: MessageRepository,
  private val crypto: com.carlom.klardrop.common.trust.TrustCrypto = com.carlom.klardrop.common.trust.TrustCrypto(),
) : MessageHandler<FileMessage, FileMessage.FileSendRequest> {

  override suspend fun handleIncoming(
    message: FileMessage,
    readChannel: ByteReadChannel,
    receiveFlow: MutableStateFlow<ReceiveMessageUpdate>
  ) {
    error("FileMessageHandler.handleIncoming is bypassed by the router for FILE messages; " +
        "use beginReceive() instead.")
  }

  override suspend fun handleOutgoing(
    toDeviceId: String,
    request: FileMessage.FileSendRequest,
    writeChannel: ByteWriteChannel,
    progressFlow: MutableSharedFlow<MessengerSendProgress>,
    cipher: FrameCipher,
  ) {
    error("FileMessageHandler.handleOutgoing is bypassed by the router for FILE messages; " +
        "use handleOutgoingChunked() instead.")
  }

  override suspend fun handleOutgoingWithReadyAck(
    toDeviceId: String,
    request: FileMessage.FileSendRequest,
    writeChannel: ByteWriteChannel,
    progressFlow: MutableSharedFlow<MessengerSendProgress>,
    awaitReady: suspend () -> Unit,
    cipher: FrameCipher,
  ) {
    error("FileMessageHandler.handleOutgoingWithReadyAck is bypassed by the router for FILE " +
        "messages; use handleOutgoingChunked() instead.")
  }

  /**
   * Called by the router when a [FileMessage] header arrives. Inserts persistence rows, opens
   * the platform sink, updates the receive flow to Started/Progress(0), and returns a pipeline
   * the router will feed chunks into. Throws on failure (e.g. permission denied opening the
   * sink) — the router catches and aborts before sending ACK_READY, so the sender knows not
   * to start streaming chunks.
   */
  suspend fun beginReceive(
    header: FileMessage,
    fromDeviceId: String,
    receiveFlow: MutableStateFlow<ReceiveMessageUpdate>,
    verifyChunkMac: (suspend (chunk: FileChunkMessage) -> Boolean)? = null,
  ): FileReceivePipeline {
    log("FileMessageHandler", "beginReceive ${header.fileName} (${header.fileSize} bytes) from $fromDeviceId")

    val fileTransferId = messageRepository.insertFileTransfer(
      fileName = header.fileName,
      filePath = "",
      totalSize = header.fileSize,
      status = FileTransferStatus.IN_PROGRESS,
      mimeType = header.mimeType,
    )
    messageRepository.insertMessage(
      remoteDeviceId = fromDeviceId,
      content = header.fileName,
      isSender = false,
      messageType = PersistenceMessageType.FILE,
      fileTransferId = fileTransferId,
      isRead = false,
      mimeType = header.mimeType,
    )

    receiveFlow.update {
      it.copy(messages = listOf(header), status = ReceiveMessageStatus.Started)
    }

    val fileTransfer = fileManager.prepareSaveFile(
      fileName = header.fileName,
      mimeType = header.mimeType,
    )

    receiveFlow.update {
      it.copy(status = ReceiveMessageStatus.Progress(listOf(header to 0)))
    }

    return FileReceivePipeline(
      header = header,
      fromDeviceId = fromDeviceId,
      fileTransferId = fileTransferId,
      fileTransfer = fileTransfer,
      receiveFlow = receiveFlow,
      messageRepository = messageRepository,
      clock = clock,
      crypto = crypto,
      verifyChunkMac = verifyChunkMac,
    )
  }

  /**
   * Read the entire file into memory so we can compute SHA-256 of the content. We rely on
   * [FileMessage.fileSize] for the allocation; if the actual stream returns more bytes the
   * extra are appended (paranoid guard against fileSize being stale). For LAN-share file
   * sizes this is fine; if Klardrop ever wants to support multi-GB transfers we'd switch to
   * an incremental hasher and stream-once instead.
   */
  private suspend fun readFileBytes(request: FileMessage.FileSendRequest): ByteArray = coroutines.ioDispatcher.invoke {
    val size = request.message.fileSize
    if (size == 0L) return@invoke ByteArray(0)

    fileManager.getReadStreamFrom(request.file).buffered().use { source ->
      val buffer = ByteArray(size.toInt().coerceAtLeast(0))
      var read = 0
      while (read < buffer.size) {
        // kotlinx.io's RawSource.readAtMostTo takes (sink, startIndex, endIndex), NOT
        // (sink, offset, length) — passing a length here would compute a startIndex past
        // endIndex once `read > 0` and explode with IllegalArgumentException.
        val n = source.readAtMostTo(buffer, read, buffer.size)
        if (n <= 0) break
        read += n
      }
      if (read == buffer.size) buffer else buffer.copyOf(read)
    }
  }

  /**
   * Sends a chunked file transfer.
   *
   * Flow:
   *   1. emit 0% progress
   *   2. send the FILE header via [sendFramed] (one framed write, lock held only for that frame)
   *   3. await ACK_READY from the receiver (sender doesn't blast bytes at a peer that can't accept)
   *   4. read the source file in [FILE_CHUNK_SIZE] chunks; each chunk is framed and written via
   *      [sendFramed] which takes the connection's write mutex per call. Between chunks the mutex
   *      is released, so heartbeats / ACKs / unrelated sends can interleave.
   *   5. mark the last chunk with isLast=true so the receiver finalizes its sink and the router
   *      sends back ACK_RECEIVED for the header id.
   *
   * The whole loop runs on [Coroutines.ioDispatcher] so disk reads don't block the main dispatcher.
   */
  suspend fun handleOutgoingChunked(
    toDeviceId: String,
    request: FileMessage.FileSendRequest,
    sendFramed: suspend (Message) -> Unit,
    progressFlow: MutableSharedFlow<MessengerSendProgress>,
    awaitReady: suspend () -> Unit,
    /**
     * Per-chunk HMAC computer. Router supplies a closure that calls
     * [com.carlom.klardrop.common.trust.TrustManager.computeChunkMac]; returns null when
     * there's no shared secret with the peer (legacy pairing or untrusted), in which case
     * the chunked path falls back to the per-file SHA-256 content-hash binding in the
     * header. Default is "no MAC" so direct unit-test invocations don't have to plumb it.
     */
    chunkMacFn: suspend (chunk: FileChunkMessage) -> ByteArray? = { null },
  ) {
    val fileTransferId = messageRepository.insertFileTransfer(
      fileName = request.message.fileName,
      filePath = request.file.path,
      totalSize = request.message.fileSize,
      status = FileTransferStatus.IN_PROGRESS,
      mimeType = request.message.mimeType,
    )
    messageRepository.insertMessage(
      remoteDeviceId = toDeviceId,
      content = request.message.fileName,
      isSender = true,
      messageType = PersistenceMessageType.FILE,
      fileTransferId = fileTransferId,
      isRead = true,
      mimeType = request.message.mimeType,
    )

    coroutines.ioDispatcher.invoke {
      progressFlow.emit(MessengerSendProgress.InProgress(0))

      val buffer = ByteArray(FILE_CHUNK_SIZE)
      var totalSent = 0L
      var seq = 0
      var lastEmitTime = 0L
      var lastEmitPercent = -1

      // Probe whether the peer has a shared secret with us by asking for a MAC over the
      // empty input. If it comes back non-null, we have an HMAC key for this peer and can
      // authenticate every chunk individually (~µs each — basically free). Otherwise we
      // fall back to the per-file SHA-256 content hash on the (signed) header — the
      // option-2 path that pre-dated the persisted ECDH secret. Both are end-to-end
      // integrity, MAC just gives faster failure detection mid-stream.
      val canMacChunks = chunkMacFn(
        FileChunkMessage(fileMessageId = request.message.id, seq = -1, data = ByteArray(0), isLast = false),
      ) != null

      val outgoingHeader = if (canMacChunks) {
        request.message
      } else {
        val fileBytes = readFileBytes(request)
        val contentHash = crypto.sha256(fileBytes)
        request.message.copy(contentHash = contentHash)
      }

      val start = clock.currentTimeMillis()
      try {
        sendFramed(outgoingHeader)
        // awaitReady() can now throw TransferRejectedException if the receiver declined
        // the transfer before any bytes were streamed — handled distinctly below so the
        // file_transfers row is marked REJECTED instead of FAILED.
        awaitReady()

        log(
          "FileMessageHandler",
          "Sending file ${request.message.fileName} (${request.message.fileSize} bytes), per-chunk MAC: $canMacChunks",
        )

        suspend fun frameChunk(chunkSeq: Int, data: ByteArray, isLast: Boolean) {
          val chunk = FileChunkMessage(
            fileMessageId = request.message.id,
            seq = chunkSeq,
            data = data,
            isLast = isLast,
            mac = if (canMacChunks) chunkMacFn(
              FileChunkMessage(
                fileMessageId = request.message.id,
                seq = chunkSeq,
                data = data,
                isLast = isLast,
              ),
            ) else null,
          )
          sendFramed(chunk)
        }

        // Empty file: still send a single isLast=true chunk so the receiver finalizes.
        if (request.message.fileSize == 0L) {
          frameChunk(0, ByteArray(0), isLast = true)
        } else {
          fileManager.getReadStreamFrom(request.file).buffered().use { readBuffer ->
            while (totalSent < request.message.fileSize) {
              val bytesToRead = min(FILE_CHUNK_SIZE.toLong(), request.message.fileSize - totalSent).toInt()
              val bytesRead = readBuffer.readAtMostTo(buffer, 0, bytesToRead)
              if (bytesRead <= 0) break

              val isLast = totalSent + bytesRead >= request.message.fileSize
              // Copy the slice we actually filled — the receiver gets the chunk via the
              // serialized message, which already encodes a length-prefixed bytes field, but
              // we must not over-send the buffer's tail garbage on the final partial chunk.
              val chunkData = if (bytesRead == buffer.size) buffer.copyOf() else buffer.copyOf(bytesRead)
              frameChunk(seq++, chunkData, isLast = isLast)
              totalSent += bytesRead

              val progressValue = (totalSent * 100 / request.message.fileSize).toInt().coerceIn(0, 100)
              val now = clock.currentTimeMillis()
              if (progressValue >= lastEmitPercent + PROGRESS_EMIT_PERCENT_DELTA ||
                  now - lastEmitTime >= PROGRESS_EMIT_INTERVAL_MS) {
                lastEmitTime = now
                lastEmitPercent = progressValue
                progressFlow.emit(MessengerSendProgress.InProgress(progressValue))
              }
            }
          }
        }

        progressFlow.emit(MessengerSendProgress.InProgress(100))
        val durationMs = clock.currentTimeMillis() - start
        val kbPerSec = if (durationMs > 0) (totalSent * 1000 / durationMs / 1024) else 0
        log("FileMessageHandler", "Sent ${request.message.fileName} ($totalSent bytes) in ${durationMs}ms ($kbPerSec KB/s)")
        messageRepository.updateFileTransferStatus(fileTransferId, FileTransferStatus.COMPLETED)
      } catch (e: TransferRejectedException) {
        log("FileMessageHandler", "File rejected by recipient: ${request.message.fileName}")
        messageRepository.updateFileTransferStatus(fileTransferId, FileTransferStatus.REJECTED)
        throw e
      } catch (e: Throwable) {
        log("FileMessageHandler", "Error sending file", e)
        messageRepository.updateFileTransferStatus(fileTransferId, FileTransferStatus.FAILED)
        throw e
      }
    }
  }
}
