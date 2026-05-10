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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.invoke
import kotlinx.coroutines.launch
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
  /**
   * Called for every wire frame we successfully read from a peer. Wired in production to
   * [com.carlom.klardrop.common.discovery.VisibleDevices.touchLastSeen] so any active TCP
   * peer (heartbeat PINGs/PONGs included) refreshes its visibility timestamp — the
   * VisibleDevices stale-eviction loop would otherwise drop peers whose mDNS announcement
   * hasn't triggered a fresh `onServiceUpdated` from NsdManager (Android only fires it on
   * actual SRV/TXT changes, not periodic refreshes). Default no-op so test fakes don't
   * have to wire it.
   */
  private val onPeerLiveness: (deviceId: String) -> Unit = {},
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

  /**
   * Fire-and-forget scope for work that may suspend on a human accept/reject decision
   * (FILE header authorization, untrusted-TEXT first contact). Running these inline on
   * the read loop's coroutine deadlocks the connection: the read loop is the only thing
   * that consumes incoming bytes, so while it's parked in `IncomingAuthorizer.authorize`
   * waiting for the user to tap, the peer's heartbeat PONGs sit unread in the buffer and
   * our heartbeat sender hits its 5s timeout and tears the link down before the user can
   * decide. SupervisorJob so a single failure doesn't cancel siblings.
   */
  private val authorizationScope = CoroutineScope(SupervisorJob() + coroutines.ioDispatcher)

  private suspend fun sendMessageToDevice(
    deviceId: String,
    message: Message,
    writeChannel: ByteWriteChannel,
  ) {
    if (trustManager.isTrusted(deviceId)) {
      // Preserve the inner message id on the wire frame so the sender (which registers
      // pending-ACKs under message.id) can match the receiver's ack reply (which echoes
      // rawMessage.id from the wire). Without this, the wire-frame id is random and
      // every ack falls into the "Unexpected ACK ... no matching pending request" bucket.
      val trustedMessage = trustManager.signMessage(messageSerializer.serialize(message), id = message.id)
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
    // Any wire frame from this peer is proof of life — refresh visibility so the
    // 5-min mDNS-derived TTL doesn't evict a peer we're actively talking to.
    onPeerLiveness(fromDeviceId)
    // The id the sender's ConnectionMessenger.send registered its pending-ACK channel
    // under is the wire-frame id, i.e. the OUTER TrustedMessage id when the message is
    // signed. The inner deserialized application message has its own (different) id that
    // the sender never tracks. Always reply to ACKs using rawMessage.id so the sender's
    // pendingAcks lookup matches.
    val ackId = rawMessage.id

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
        // Control-plane frames (heartbeat ping/pong, ACKs) carry no application data and
        // are exempt from the signed-from-trusted requirement. Heartbeat in particular
        // is sent from ConnectionMessenger which has no TrustManager handle, so we can't
        // sign it there — and rejecting an unsigned PING would tear the connection down.
        //
        // FileChunkMessage is also exempt: file transfers sign only the header (which
        // includes a SHA-256 of the file bytes). Chunks flow unsigned for performance —
        // ECDSA-per-chunk would add seconds to a typical multi-MB transfer. Tampering is
        // caught at the receive pipeline's complete() step by hash verification against
        // the signed header.
        //
        // Application messages (TEXT, FILE, CLIPBOARD_SYNC, CONNECTION_INFO) still must be
        // signed when coming from a trusted peer.
        val isControlPlane = rawMessage is PingMessage ||
            rawMessage is PongMessage ||
            rawMessage is MessageAcknowledgment ||
            rawMessage is FileChunkMessage
        if (isTrustedDevice && !isPairingMessage && !isControlPlane) {
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
        sendMessageToDevice(fromDeviceId, PongMessage(pingId = message.id), writeChannel)
      }
      return@ioDispatcher
    }
    if (message is PongMessage) {
      pongCallback(message)
      return@ioDispatcher
    }

    // ===== FILE chunked transfer special-case =====
    if (message is FileMessage) {
      // Spawn off the read loop: handleFileHeader suspends inside IncomingAuthorizer
      // until the user accepts/rejects, and we MUST keep draining the read channel in
      // the meantime so heartbeat PONGs from the peer get processed (otherwise our own
      // heartbeat times out and kills the connection before the user can decide). The
      // sender doesn't push chunks until ACK_READY, so reordering with subsequent chunks
      // for the same header isn't possible.
      authorizationScope.launch {
        handleFileHeader(message, ackId, fromDeviceId, writeChannel, writeLock)
      }
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
    //
    // Same reasoning as the FILE header dispatch above: authorize() may suspend on a
    // human decision, and parking the read loop on it deadlocks the heartbeat. Spawn the
    // whole TEXT processing path off the read loop so PONGs continue to drain.
    if (message is TextMessage) {
      authorizationScope.launch {
        val authorized = incomingAuthorizer.authorize(
          fromDeviceId = fromDeviceId,
          kind = IncomingAuthorizer.TransferKind.TEXT,
          headers = listOf(message),
          receiveFlow = receiveFlow,
          notifyAwaitingUser = {
            val ackAwaiting = MessageAcknowledgment(AckType.AWAITING_USER, ackId)
            writeLock.withLock {
              sendMessageToDevice(fromDeviceId, ackAwaiting, writeChannel)
            }
          },
        )
        if (!authorized) {
          val ackRejected = MessageAcknowledgment(AckType.REJECTED, ackId)
          writeLock.withLock {
            sendMessageToDevice(fromDeviceId, ackRejected, writeChannel)
          }
          return@launch
        }
        val messageHandler = handlers[message.type] ?: run {
          log("MessagesRouter", "No handler for message type ${message.type}")
          return@launch
        }
        messageHandler.handleIncoming(message, readChannel, receiveFlow)
        val ackReceived = MessageAcknowledgment(AckType.RECEIVED, ackId)
        writeLock.withLock {
          sendMessageToDevice(fromDeviceId, ackReceived, writeChannel)
        }
      }
      return@ioDispatcher
    }

    if (message.hasPayload) {
      if (!isAckMessage) {
        val ackReady = MessageAcknowledgment(AckType.READY, ackId)
        writeLock.withLock {
          sendMessageToDevice(fromDeviceId, ackReady, writeChannel)
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
      val ackReceived = MessageAcknowledgment(AckType.RECEIVED, ackId)
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
    ackId: Int,
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
      notifyAwaitingUser = {
        val ackAwaiting = MessageAcknowledgment(AckType.AWAITING_USER, ackId)
        writeLock.withLock {
          sendMessageToDevice(fromDeviceId, ackAwaiting, writeChannel)
        }
      },
    )
    if (!authorized) {
      val ackRejected = MessageAcknowledgment(AckType.REJECTED, ackId)
      writeLock.withLock {
        sendMessageToDevice(fromDeviceId, ackRejected, writeChannel)
      }
      return
    }

    // If we have a shared secret with this peer (paired post-secret-persistence), we
    // expect every chunk to carry an HMAC tag — anti-downgrade is enforced locally:
    // the receiver checks based on its own knowledge of the trust state, so an attacker
    // can't strip the field to bypass verification.
    val verifyChunkMac: (suspend (chunk: FileChunkMessage) -> Boolean)? =
      if (trustManager.macKeyFor(fromDeviceId) != null) {
        { chunk ->
          val tag = chunk.mac
          if (tag == null) false
          else trustManager.verifyChunkMac(
            deviceId = fromDeviceId,
            fileMessageId = chunk.fileMessageId,
            seq = chunk.seq,
            isLast = chunk.isLast,
            data = chunk.data,
            tag = tag,
          )
        }
      } else {
        null
      }

    val pipeline = runCatching {
      fileMessageHandler.beginReceive(header, fromDeviceId, receiveFlow, verifyChunkMac)
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

    val ackReady = MessageAcknowledgment(AckType.READY, ackId)
    writeLock.withLock {
      sendMessageToDevice(fromDeviceId, ackReady, writeChannel)
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
      //
      // Trust signing strategy for files: SIGN the header, leave chunks unsigned. The
      // header carries SHA-256 of the file content and is signed (one ECDSA op for the
      // whole transfer). The receiver hashes chunks as they arrive and verifies the
      // assembled hash against the signed header before marking the transfer complete —
      // any chunk tampering fails the integrity check. This trades per-chunk authenticity
      // (which we don't need given the signed binding) for a ~24× speedup on a 6 MB file.
      if (message is FileMessage) {
        @Suppress("UNCHECKED_CAST")
        val fileRequest = sendMessageRequest as FileMessage.FileSendRequest
        val sendFramed: suspend (Message) -> Unit = { framedMessage ->
          writeLock.withLock {
            // Sign the FILE header (binds the transfer to this device's identity + the
            // contentHash inside the header); send chunks as raw frames. The receiver
            // accepts unsigned FileChunkMessage from trusted peers because the security
            // gate exempts them — see onMessageIncoming's `isControlPlane`-style check.
            if (framedMessage is FileMessage) {
              sendMessageToDevice(toDeviceId, framedMessage, writeChannel)
            } else {
              writeChannel.sendMessage(framedMessage, messageSerializer)
            }
          }
        }
        // Compute per-chunk HMAC tags via the per-pair key derived from the ECDH shared
        // secret. Returns null if no shared secret on file — handler then falls back to
        // the option-2 SHA-256 binding inside the (signed) header.
        val chunkMacFn: suspend (FileChunkMessage) -> ByteArray? = { chunk ->
          trustManager.computeChunkMac(
            deviceId = toDeviceId,
            fileMessageId = chunk.fileMessageId,
            seq = chunk.seq,
            isLast = chunk.isLast,
            data = chunk.data,
          )
        }
        fileMessageHandler.handleOutgoingChunked(
          toDeviceId = toDeviceId,
          request = fileRequest,
          sendFramed = sendFramed,
          progressFlow = progress,
          awaitReady = awaitReadyAck,
          chunkMacFn = chunkMacFn,
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
