package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.AckType
import com.carlom.klardrop.common.communication.message.FileChunkMessage
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.FileMessageHandler
import com.carlom.klardrop.common.communication.message.FileReceivePipeline
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.MessageAcknowledgment
import com.carlom.klardrop.common.communication.message.MessageHandlers
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.message.PingMessage
import com.carlom.klardrop.common.communication.message.PongMessage
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.TrustedMessage
import com.carlom.klardrop.common.communication.readMessage
import com.carlom.klardrop.common.communication.sendMessage
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.invoke
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface MessagesRouter {
  /**
   * [writeLock] serializes every write the router does on [writeChannel] (PONG replies,
   * ACK_READY, ACK_RECEIVED) so they don't race with outgoing sends or the heartbeat
   * ping. The caller (ConnectionMessenger) keeps one mutex per connection and passes it
   * to both [onMessageIncoming] and [onSendingMessage]. The default value is per-call
   * Mutex purely so test fakes don't have to care about it.
   */
  suspend fun onMessageIncoming(
    fromDeviceId: String,
    writeChannel: ByteWriteChannel,
    readChannel: ByteReadChannel,
    ackCallback: (suspend (MessageAcknowledgment) -> Unit),
    pongCallback: (suspend (PongMessage) -> Unit) = {},
    writeLock: Mutex = Mutex(),
  )

  /**
   * Sends a message. For payload-bearing messages, [awaitReadyAck] should be a
   * callback that suspends until the receiver has sent ACK_READY for this
   * message id; the handler is responsible for invoking it between the
   * header write and the payload stream. Default no-op for backward-compat
   * with message types that have no payload.
   *
   * The router holds [writeLock] for the duration of the handler call so that the
   * header bytes and the (potentially huge) raw payload bytes stay contiguous on the
   * wire. Concurrent writers (heartbeat, incoming-reply path) wait on the same mutex.
   *
   * EXCEPTION: for [FileMessage] the lock is NOT held across the whole call. The
   * chunked-send path takes the lock per-chunk (see [FileMessageHandler.handleOutgoingChunked])
   * so heartbeats / acks / unrelated messages can interleave between chunks.
   */
  suspend fun <S : SendMessageRequest> onSendingMessage(
    toDeviceId: String,
    sendMessageRequest: S,
    writeChannel: ByteWriteChannel,
    readChannel: ByteReadChannel,
    progress: MutableSharedFlow<MessengerSendProgress>,
    awaitReadyAck: suspend () -> Unit = {},
    writeLock: Mutex = Mutex(),
  )
}

internal class MessagesRouterImpl(
  private val handlers: MessageHandlers,
  private val fileMessageHandler: FileMessageHandler,
  private val messageSerializer: MessageSerializer,
  private val coroutines: Coroutines,
  private val messengeReceiver: MessageReceiver,
  private val trustManager: TrustManager,
  private val incomingAuthorizer: IncomingAuthorizer,
) : MessagesRouter {

  /**
   * In-flight chunked file receives, keyed by FILE header id. Populated when a [FileMessage]
   * header arrives, drained when the corresponding [FileChunkMessage] with isLast=true arrives.
   *
   * Scoped per router instance which is per-connection (via the DI graph), so id collisions
   * only matter within one peer's stream — and FILE header ids are random Int so that's fine.
   */
  private val receivePipelines = mutableMapOf<Int, FileReceivePipeline>()
  private val receiveMutex = Mutex()

  private suspend fun sendMessageToDevice(
    deviceId: String,
    message: Message,
    writeChannel: ByteWriteChannel,
  ) {
    if (trustManager.isTrusted(deviceId)) {
      val trustedMessage = trustManager.signMessage(messageSerializer.serialize(message))
      if (trustedMessage != null) {
        writeChannel.sendMessage(trustedMessage, messageSerializer)
      } else {
        log("MessagesRouter", "Failed to sign message for trusted device $deviceId")
        writeChannel.sendMessage(message, messageSerializer)
      }
    } else {
      writeChannel.sendMessage(message, messageSerializer)
    }
  }

  override suspend fun onMessageIncoming(
    fromDeviceId: String,
    writeChannel: ByteWriteChannel,
    readChannel: ByteReadChannel,
    ackCallback: (suspend (MessageAcknowledgment) -> Unit),
    pongCallback: (suspend (PongMessage) -> Unit),
    writeLock: Mutex,
  ) = coroutines.ioDispatcher {

    val rawMessage = readChannel.readMessage(messageSerializer)
    log(
      "MessagesRouter",
      "[DEBUG] Raw message received from $fromDeviceId: type=${rawMessage.type}, id=${rawMessage.id}, hasPayload=${rawMessage.hasPayload}"
    )

    val message = when {
      rawMessage is TrustedMessage -> {
        val isValid = trustManager.verifyMessage(rawMessage)
        if (!isValid) {
          log("MessagesRouter", "SECURITY: signature verification failed for TrustedMessage from $fromDeviceId")
          return@ioDispatcher
        }
        messageSerializer.deserialize(rawMessage.payload)
      }

      else -> {
        val isTrustedDevice = trustManager.isTrusted(fromDeviceId)
        val isPairingMessage = rawMessage is com.carlom.klardrop.common.communication.message.TrustPairingRequest ||
            rawMessage is com.carlom.klardrop.common.communication.message.TrustPairingResponse
        if (isTrustedDevice && !isPairingMessage) {
          log("MessagesRouter", "SECURITY: unsigned message from trusted device $fromDeviceId - rejecting")
          return@ioDispatcher
        }
        rawMessage
      }
    }

    if (message is MessageAcknowledgment) {
      ackCallback(message)
      return@ioDispatcher
    }

    if (message is PingMessage) {
      writeLock.withLock {
        writeChannel.sendMessage(PongMessage(pingId = message.id), messageSerializer)
      }
      return@ioDispatcher
    }
    if (message is PongMessage) {
      pongCallback(message)
      return@ioDispatcher
    }

    // ===== FILE chunked transfer special-case =====
    if (message is FileMessage) {
      handleFileHeader(message, fromDeviceId, writeChannel, writeLock)
      return@ioDispatcher
    }
    if (message is FileChunkMessage) {
      handleFileChunk(message, fromDeviceId, writeChannel, writeLock)
      return@ioDispatcher
    }
    // ===== end FILE special-case =====

    val isAckMessage = message.type == MessageType.ACK_READY ||
        message.type == MessageType.ACK_RECEIVED ||
        message.type == MessageType.ACK_REJECTED ||
        message.type == MessageType.PING ||
        message.type == MessageType.PONG

    val receiveFlow = messengeReceiver.onReceiveMessage(fromDeviceId)

    // Authorization gate for TEXT messages from untrusted senders. Files are gated
    // separately in handleFileHeader (because rejection there must skip beginReceive
    // entirely, before the receive pipeline opens a sink). Trust-based control messages
    // (TRUST_PAIRING_*, CLIPBOARD_SYNC, CONNECTION_INFO) are not gated — pairing must
    // succeed before trust exists, and the others are already trust-restricted upstream.
    if (message is TextMessage) {
      val authorized = incomingAuthorizer.authorize(
        fromDeviceId = fromDeviceId,
        kind = IncomingAuthorizer.TransferKind.TEXT,
        headers = listOf(message),
        receiveFlow = receiveFlow,
      )
      if (!authorized) {
        val ackRejected = MessageAcknowledgment(AckType.REJECTED, message.id)
        writeLock.withLock {
          sendMessageToDevice(fromDeviceId, ackRejected, writeChannel)
        }
        return@ioDispatcher
      }
    }

    if (message.hasPayload) {
      if (!isAckMessage) {
        val ackReady = MessageAcknowledgment(AckType.READY, message.id)
        writeLock.withLock {
          writeChannel.sendMessage(ackReady, messageSerializer)
        }
      }

      val messageHandler = handlers[message.type] ?: run {
        log("MessagesRouter", "No handler for message type ${message.type}")
        return@ioDispatcher
      }
      messageHandler.handleIncoming(message, readChannel, receiveFlow)
    } else {
      val messageHandler = handlers[message.type]
      if (messageHandler != null) {
        messageHandler.handleIncoming(message, readChannel, receiveFlow)
      } else {
        error("No handler found in MessagesRouter for message type ${message.type} with id ${message.id}")
      }
    }

    if (!isAckMessage) {
      val ackReceived = MessageAcknowledgment(AckType.RECEIVED, message.id)
      writeLock.withLock {
        sendMessageToDevice(fromDeviceId, ackReceived, writeChannel)
      }
    }
  }

  /**
   * Receive-side handling for a FILE header. Opens the receive pipeline (which inserts DB rows
   * and opens the platform sink), registers it under the header id, and sends ACK_READY so the
   * sender can start streaming chunks. ACK_RECEIVED is *not* sent here — the chunk handler
   * sends it when isLast arrives, so the sender's `await ACK_RECEIVED` truly means
   * "all bytes delivered and finalized."
   *
   * If [FileMessageHandler.beginReceive] throws (permission denied, disk full), we drop the
   * transfer silently — no ACK_READY, sender will time out on its readyAck wait and report
   * the error.
   */
  private suspend fun handleFileHeader(
    header: FileMessage,
    fromDeviceId: String,
    writeChannel: ByteWriteChannel,
    writeLock: Mutex,
  ) {
    val receiveFlow = messengeReceiver.onReceiveMessage(fromDeviceId)

    // Files always prompt for untrusted senders, even if they previously accepted text.
    // The gate runs BEFORE beginReceive() so rejection skips DB row creation and sink
    // allocation entirely; the sender sees ACK_REJECTED in place of ACK_READY and
    // never streams chunks.
    val authorized = incomingAuthorizer.authorize(
      fromDeviceId = fromDeviceId,
      kind = IncomingAuthorizer.TransferKind.FILE,
      headers = listOf(header),
      receiveFlow = receiveFlow,
    )
    if (!authorized) {
      val ackRejected = MessageAcknowledgment(AckType.REJECTED, header.id)
      writeLock.withLock {
        writeChannel.sendMessage(ackRejected, messageSerializer)
      }
      return
    }

    val pipeline = runCatching {
      fileMessageHandler.beginReceive(header, fromDeviceId, receiveFlow)
    }.getOrElse { error ->
      log("MessagesRouter", "beginReceive failed for ${header.fileName}: ${error.message}", error)
      return
    }

    receiveMutex.withLock {
      receivePipelines[header.id]?.let { existing ->
        log("MessagesRouter", "Replacing in-flight pipeline for header id=${header.id} (likely duplicate header)")
        runCatching { existing.fail(IllegalStateException("Replaced by new header with same id")) }
      }
      receivePipelines[header.id] = pipeline
    }

    val ackReady = MessageAcknowledgment(AckType.READY, header.id)
    writeLock.withLock {
      writeChannel.sendMessage(ackReady, messageSerializer)
    }
  }

  /**
   * Receive-side handling for one FILE_CHUNK. Looks up the pipeline by [FileChunkMessage.fileMessageId]
   * and feeds bytes into it. On [FileChunkMessage.isLast], finalizes (closes sink, marks DB) and
   * sends ACK_RECEIVED for the *header* id (not the chunk id) — that's what the sender awaits.
   *
   * A chunk for an unknown id is logged and dropped. Could happen if the header was lost
   * (impossible on TCP) or the receiver previously failed and removed the pipeline.
   */
  private suspend fun handleFileChunk(
    chunk: FileChunkMessage,
    fromDeviceId: String,
    writeChannel: ByteWriteChannel,
    writeLock: Mutex,
  ) {
    val pipeline = receiveMutex.withLock { receivePipelines[chunk.fileMessageId] }
    if (pipeline == null) {
      log("MessagesRouter", "Dropping chunk for unknown fileMessageId=${chunk.fileMessageId}")
      return
    }

    val chunkResult = runCatching { pipeline.acceptChunk(chunk) }

    if (chunk.isLast || chunkResult.isFailure) {
      receiveMutex.withLock { receivePipelines.remove(chunk.fileMessageId) }
      if (chunkResult.isFailure) {
        pipeline.fail(chunkResult.exceptionOrNull()!!)
        // No ACK_RECEIVED on failure — sender will time out and report.
      } else {
        pipeline.complete()
        val ackReceived = MessageAcknowledgment(AckType.RECEIVED, chunk.fileMessageId)
        writeLock.withLock {
          sendMessageToDevice(fromDeviceId, ackReceived, writeChannel)
        }
      }
    }
  }

  override suspend fun <S : SendMessageRequest> onSendingMessage(
    toDeviceId: String,
    sendMessageRequest: S,
    writeChannel: ByteWriteChannel,
    readChannel: ByteReadChannel,
    progress: MutableSharedFlow<MessengerSendProgress>,
    awaitReadyAck: suspend () -> Unit,
    writeLock: Mutex,
  ) {
    coroutines.ioDispatcher {
      val message = sendMessageRequest.message

      if (message is TrustedMessage) {
        writeLock.withLock {
          writeChannel.sendMessage(message, messageSerializer)
        }
        return@ioDispatcher
      }

      // ===== FILE chunked send special-case =====
      // Don't hold writeLock for the whole send. The chunked path takes the lock per single
      // framed write (header, then each chunk) so the heartbeat ping and any inbound-reply
      // writes can interleave between chunks. For multi-GB transfers this is the difference
      // between "heartbeat starves and connection gets killed mid-transfer" and "everything
      // works as expected."
      if (message is FileMessage) {
        @Suppress("UNCHECKED_CAST")
        val fileRequest = sendMessageRequest as FileMessage.FileSendRequest
        val sendFramed: suspend (Message) -> Unit = { framedMessage ->
          writeLock.withLock {
            writeChannel.sendMessage(framedMessage, messageSerializer)
          }
        }
        fileMessageHandler.handleOutgoingChunked(
          toDeviceId = toDeviceId,
          request = fileRequest,
          sendFramed = sendFramed,
          progressFlow = progress,
          awaitReady = awaitReadyAck,
        )
        return@ioDispatcher
      }
      // ===== end FILE special-case =====

      val messageHandler = handlers[message.type] ?: run {
        log("MessagesRouter", "No handler for message type ${message.type}")
        return@ioDispatcher
      }

      writeLock.withLock {
        if (message.hasPayload) {
          messageHandler.handleOutgoingWithReadyAck(toDeviceId, sendMessageRequest, writeChannel, progress, awaitReadyAck)
        } else {
          messageHandler.handleOutgoing(toDeviceId, sendMessageRequest, writeChannel, progress)
        }
      }
    }
  }
}
