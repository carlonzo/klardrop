package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.FrameCipher
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.TransferAnchor
import com.carlom.klardrop.common.communication.message.AckType
import com.carlom.klardrop.common.communication.message.ClipboardSyncMessage
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
import com.carlom.klardrop.common.communication.message.TrustRevocationMessage
import com.carlom.klardrop.common.communication.message.TrustedMessage
import com.carlom.klardrop.common.communication.readMessage
import com.carlom.klardrop.common.communication.sendMessage
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.invoke
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

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
    cipher: FrameCipher = FrameCipher.Plain,
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
    cipher: FrameCipher = FrameCipher.Plain,
  )

  /**
   * The link to [deviceId] is gone. Any file receive from that peer that hadn't finished is now
   * unfinishable — no more chunks will ever arrive — so the router fails it here.
   *
   * Without this the pipeline is simply orphaned: its `file_transfers` row stays IN_PROGRESS
   * forever (rendering as a stuck bubble in chat until the next app start sweeps it), and — worse
   * on mobile — the [com.carlom.klardrop.common.communication.TransferAnchor] it opened is never
   * released, leaving Android's foreground service, wake lock and WifiLock held indefinitely.
   *
   * Default no-op so test fakes don't have to implement it.
   */
  suspend fun onPeerDisconnected(deviceId: String) = Unit
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
  /**
   * Keeps the process alive and the device awake while an inbound file transfer is in flight —
   * the receive-side counterpart to the anchoring [com.carlom.klardrop.common.communication.MessengerImpl]
   * does for sends. Receiving is the direction that needs it most: the user hits Accept and puts
   * the phone down, so the whole transfer happens with the app backgrounded and the screen off.
   */
  private val transferAnchor: TransferAnchor = TransferAnchor.None,
) : MessagesRouter {

  /**
   * In-flight chunked file receives, keyed by FILE header id. Populated when a [FileMessage]
   * header arrives, drained when the corresponding [FileChunkMessage] with isLast=true arrives.
   *
   * The router is a process singleton (one instance serves every connection), so this map spans
   * peers; FILE header ids are random Ints, which makes a cross-peer collision vanishingly
   * unlikely. Each pipeline carries its own [FileReceivePipeline.fromDeviceId] so
   * [onPeerDisconnected] can pick out the ones belonging to a link that just died.
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
   * decide. SupervisorJob so a single failure doesn't cancel siblings, and built through
   * [Coroutines.newScope] so it carries the platform's last-resort CoroutineExceptionHandler —
   * a throw out of `handleFileHeader` would otherwise reach kotlinx.coroutines' final resort and
   * abort the process on Kotlin/Native.
   */
  private val authorizationScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)

  /**
   * Bounded FIFO set of inbound TEXT wire-frame ids that have already been processed
   * (or are currently being processed) on this connection. When a sender retries after a
   * lost ACK the same wire-frame id arrives again; we skip `insertMessage` but still reply
   * with ACK_RECEIVED so the sender's retry is acknowledged without inserting a duplicate
   * DB row. Bounded to [PROCESSED_TEXT_IDS_MAX] entries so it doesn't grow unboundedly on
   * a long-lived connection. Access is guarded by [processedTextIdsMutex].
   */
  private val processedTextIds = LinkedHashSet<Int>()
  private val processedTextIdsMutex = Mutex()

  private suspend fun sendMessageToDevice(
    deviceId: String,
    message: Message,
    writeChannel: ByteWriteChannel,
    cipher: FrameCipher,
  ) {
    // On an authenticated-encrypted link the transport already provides confidentiality AND
    // peer authenticity (the channel is bound to the peer's device ECDSA key during the UKEY2
    // handshake), so the per-message TrustedMessage ECDSA wrapper is redundant — skip it. The
    // decision is driven by our OWN confirmed channel state ([FrameCipher.authenticated]), never
    // by a peer-supplied flag, so an attacker can't induce a downgrade. Cleartext / unauthenticated
    // links (BLE, opportunistic) keep signing for trusted peers.
    if (!cipher.authenticated && trustManager.isTrusted(deviceId)) {
      // Preserve the inner message id on the wire frame so the sender (which registers
      // pending-ACKs under message.id) can match the receiver's ack reply (which echoes
      // rawMessage.id from the wire). Without this, the wire-frame id is random and
      // every ack falls into the "Unexpected ACK ... no matching pending request" bucket.
      val trustedMessage = trustManager.signMessage(messageSerializer.serialize(message), id = message.id)
      if (trustedMessage != null) {
        writeChannel.sendMessage(trustedMessage, messageSerializer, cipher)
      } else {
        log("MessagesRouter", "Failed to sign message for trusted device $deviceId")
        writeChannel.sendMessage(message, messageSerializer, cipher)
      }
    } else {
      writeChannel.sendMessage(message, messageSerializer, cipher)
    }
  }

  override suspend fun onMessageIncoming(
    fromDeviceId: String,
    writeChannel: ByteWriteChannel,
    readChannel: ByteReadChannel,
    ackCallback: (suspend (MessageAcknowledgment) -> Unit),
    pongCallback: (suspend (PongMessage) -> Unit),
    writeLock: Mutex,
    cipher: FrameCipher,
  ) = coroutines.ioDispatcher {

    val rawMessage = readChannel.readMessage(messageSerializer, cipher)
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
        // Two distinct failure modes:
        //   (a) we have the sender's ECDSA key but signature is invalid → tampering / replay /
        //       key rotation glitch. Drop silently — we don't want to leak verification oracles
        //       or amplify spoofed traffic.
        //   (b) we have no key for the sender at all → we previously unpaired them, but they
        //       still believe we're paired. Tell them so they can clean up. We sign this with
        //       our own identity, which they still hold from the original pairing.
        val senderKnown = trustManager.isTrusted(rawMessage.senderId)
        val isValid = trustManager.verifyMessage(rawMessage)
        if (!isValid) {
          if (!senderKnown) {
            log("MessagesRouter", "Unknown sender ${rawMessage.senderId} sent TrustedMessage; replying with revocation")
            val revocation = trustManager.createRevocationMessage(
              targetDeviceId = rawMessage.senderId,
              reason = "device_unknown",
            )
            if (revocation != null) {
              writeLock.withLock {
                writeChannel.sendMessage(revocation, messageSerializer, cipher)
              }
            } else {
              log("MessagesRouter", "Failed to build revocation reply for ${rawMessage.senderId}")
            }
          } else {
            log("MessagesRouter", "SECURITY: signature verification failed for TrustedMessage from $fromDeviceId")
          }
          // Always send a terminal ACK_REJECTED so the sender fast-fails instead of
          // timing out and retrying — mirroring the FILE path's terminal-ACK contract. Both
          // sub-paths (unknown-sender and known-bad-signature) return without processing the
          // message, so neither is an ACK_RECEIVED situation.
          runCatching {
            writeLock.withLock {
              sendMessageToDevice(fromDeviceId, MessageAcknowledgment(AckType.REJECTED, ackId), writeChannel, cipher)
            }
          }.onFailure { e ->
            log("MessagesRouter", "Failed to send ACK_REJECTED for invalid TrustedMessage from $fromDeviceId", e)
          }
          return@ioDispatcher
        }
        messageSerializer.deserialize(rawMessage.payload)
      }

      else -> {
        val isTrustedDevice = trustManager.isTrusted(fromDeviceId)
        val isPairingMessage = rawMessage is com.carlom.klardrop.common.communication.message.TrustPairingRequest ||
            rawMessage is com.carlom.klardrop.common.communication.message.TrustPairingResponse ||
            // Revocations carry their own ECDSA signature inside the frame; the handler
            // verifies it against the sender's stored public key before applying. Wrapping
            // them in TrustedMessage doesn't work because we may have already removed our
            // local trust entry for the peer by the time we receive (or send) one.
            rawMessage is TrustRevocationMessage
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
        // On an authenticated-encrypted link the sender deliberately skips the TrustedMessage
        // wrapper (the channel already authenticates the peer), so unsigned application messages
        // from a trusted peer are EXPECTED — don't reject them. On cleartext/unauthenticated links
        // a trusted peer's application message must still be signed. Gate is driven by our own
        // channel state, so a downgrade can't slip an unsigned message past us.
        if (isTrustedDevice && !isPairingMessage && !isControlPlane && !cipher.authenticated) {
          log("MessagesRouter", "SECURITY: unsigned message from trusted device $fromDeviceId - rejecting")
          return@ioDispatcher
        }
        // Clipboard sync lands in the user's clipboard silently — there is no accept/reject
        // prompt in front of it, so pairing is the only consent that exists and it has to hold
        // at this layer too. A CLIPBOARD_SYNC arriving *unwrapped* means it was neither signed
        // (Messenger wraps clipboard frames in a TrustedMessage for every paired peer) nor sent
        // over a channel we bound to a known identity, so the sender is not a paired device
        // whatever id it claims. Drop it rather than pasting for whoever is on the LAN.
        if (rawMessage is ClipboardSyncMessage && !(isTrustedDevice && cipher.authenticated)) {
          log(
            "MessagesRouter",
            "SECURITY: dropping clipboard sync from $fromDeviceId " +
              "(trusted=$isTrustedDevice, authenticated=${cipher.authenticated})"
          )
          // Terminal ACK so the sender fails fast instead of retrying the push every few seconds.
          runCatching {
            writeLock.withLock {
              sendMessageToDevice(fromDeviceId, MessageAcknowledgment(AckType.REJECTED, ackId), writeChannel, cipher)
            }
          }.onFailure { e ->
            log("MessagesRouter", "Failed to send ACK_REJECTED for clipboard sync from $fromDeviceId", e)
          }
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
        sendMessageToDevice(fromDeviceId, PongMessage(pingId = message.id), writeChannel, cipher)
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
        handleFileHeader(message, ackId, fromDeviceId, writeChannel, writeLock, cipher)
      }
      return@ioDispatcher
    }
    if (message is FileChunkMessage) {
      handleFileChunk(message, fromDeviceId, writeChannel, writeLock, cipher)
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
    // (TRUST_PAIRING_*, CLIPBOARD_SYNC, CONNECTION_INFO) are not gated by a prompt —
    // pairing must succeed before trust exists, and CLIPBOARD_SYNC is instead dropped
    // outright above unless it came from a paired peer over an identity-bound channel.
    //
    // Same reasoning as the FILE header dispatch above: authorize() may suspend on a
    // human decision, and parking the read loop on it deadlocks the heartbeat. Spawn the
    // whole TEXT processing path off the read loop so PONGs continue to drain.
    if (message is TextMessage) {
      authorizationScope.launch {
        // Wrap the entire authorize→handle→ACK pipeline so any exception from the
        // authorizer or from TextMessageHandler.handleIncoming (e.g. a DB write failure) is
        // caught and replied to with ACK_REJECTED rather than being silently swallowed by the
        // SupervisorJob. Without this, the sender's ACK_RECEIVED wait times out and the TEXT
        // is retried up to maxRetries times, causing ghost duplicate deliveries.
        // Mirrors the FILE path's terminal-ACK contract in handleFileChunk.
        runCatching {
          val authorized = incomingAuthorizer.authorize(
            fromDeviceId = fromDeviceId,
            kind = IncomingAuthorizer.TransferKind.TEXT,
            headers = listOf(message),
            receiveFlow = receiveFlow,
            notifyAwaitingUser = {
              val ackAwaiting = MessageAcknowledgment(AckType.AWAITING_USER, ackId)
              writeLock.withLock {
                sendMessageToDevice(fromDeviceId, ackAwaiting, writeChannel, cipher)
              }
            },
          )
          if (!authorized) {
            val ackRejected = MessageAcknowledgment(AckType.REJECTED, ackId)
            writeLock.withLock {
              sendMessageToDevice(fromDeviceId, ackRejected, writeChannel, cipher)
            }
            return@runCatching
          }
          // Dedup inbound TEXT by wire-frame id. If this id was already processed
          // (the sender is retrying after a lost ACK), skip insertMessage but still
          // reply ACK_RECEIVED so the retry is acknowledged without duplicating the DB row.
          val alreadyProcessed = processedTextIdsMutex.withLock { ackId in processedTextIds }
          if (alreadyProcessed) {
            log("MessagesRouter", "Duplicate inbound TEXT id=$ackId from $fromDeviceId; re-ACKing without re-inserting")
            val ackReceived = MessageAcknowledgment(AckType.RECEIVED, ackId)
            writeLock.withLock {
              sendMessageToDevice(fromDeviceId, ackReceived, writeChannel, cipher)
            }
            return@runCatching
          }
          // Mark as in-flight before suspending on handleIncoming so a concurrent retry
          // sees the id as already-processed.
          processedTextIdsMutex.withLock {
            if (processedTextIds.size >= PROCESSED_TEXT_IDS_MAX) {
              processedTextIds.remove(processedTextIds.first())
            }
            processedTextIds.add(ackId)
          }
          val messageHandler = handlers[message.type] ?: run {
            log("MessagesRouter", "No handler for message type ${message.type}")
            return@runCatching
          }
          messageHandler.handleIncoming(message, readChannel, receiveFlow)
          val ackReceived = MessageAcknowledgment(AckType.RECEIVED, ackId)
          writeLock.withLock {
            sendMessageToDevice(fromDeviceId, ackReceived, writeChannel, cipher)
          }
        }.onFailure { error ->
          log("MessagesRouter", "Error processing inbound TEXT from $fromDeviceId (id=$ackId): ${error.message}", error)
          runCatching {
            writeLock.withLock {
              sendMessageToDevice(fromDeviceId, MessageAcknowledgment(AckType.REJECTED, ackId), writeChannel, cipher)
            }
          }.onFailure { e ->
            log("MessagesRouter", "Failed to send ACK_REJECTED for failed TEXT from $fromDeviceId", e)
          }
        }
      }
      return@ioDispatcher
    }

    if (message.hasPayload) {
      if (!isAckMessage) {
        val ackReady = MessageAcknowledgment(AckType.READY, ackId)
        writeLock.withLock {
          sendMessageToDevice(fromDeviceId, ackReady, writeChannel, cipher)
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
        sendMessageToDevice(fromDeviceId, ackReceived, writeChannel, cipher)
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
   *
   * The [transferAnchor] opens here rather than in [FileMessageHandler.beginReceive] so it also
   * covers the authorization wait: the sender is parked on ACK_READY while we wait for the user to
   * tap Accept, and on mobile that window is exactly when the screen locks and the process gets
   * frozen. Ownership then transfers to the pipeline, which releases it on the terminal state;
   * every path that returns before the pipeline exists releases it here instead.
   */
  private suspend fun handleFileHeader(
    header: FileMessage,
    ackId: Int,
    fromDeviceId: String,
    writeChannel: ByteWriteChannel,
    writeLock: Mutex,
    cipher: FrameCipher,
  ) {
    val anchorId = incomingAnchorId(fromDeviceId, header.id)
    var anchorHandedOff = false
    runCatching { transferAnchor.begin(anchorId, header.fileName, TransferAnchor.Direction.INCOMING) }
      .onFailure { log("MessagesRouter", "Transfer anchor begin failed for $anchorId", it) }
    try {
      receiveFileHeader(header, ackId, fromDeviceId, writeChannel, writeLock, cipher, anchorId) {
        anchorHandedOff = true
      }
    } finally {
      // Once a pipeline exists it owns the anchor (it's the only thing that knows when the last
      // chunk lands). Everything else — rejection, beginReceive failure, a throw out of the
      // authorizer — has to release it right here or the device never sleeps again.
      if (!anchorHandedOff) {
        runCatching { transferAnchor.end(anchorId) }
          .onFailure { log("MessagesRouter", "Transfer anchor end failed for $anchorId", it) }
      }
    }
  }

  private suspend fun receiveFileHeader(
    header: FileMessage,
    ackId: Int,
    fromDeviceId: String,
    writeChannel: ByteWriteChannel,
    writeLock: Mutex,
    cipher: FrameCipher,
    anchorId: String,
    onAnchorHandedOff: () -> Unit,
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
          sendMessageToDevice(fromDeviceId, ackAwaiting, writeChannel, cipher)
        }
      },
    )
    if (!authorized) {
      val ackRejected = MessageAcknowledgment(AckType.REJECTED, ackId)
      writeLock.withLock {
        sendMessageToDevice(fromDeviceId, ackRejected, writeChannel, cipher)
      }
      return
    }

    // On an authenticated-encrypted link every chunk frame is already AEAD-authenticated by the
    // transport, so the per-chunk HMAC is redundant — skip requiring it. On cleartext/
    // unauthenticated links, if we have a shared secret with this peer (paired
    // post-secret-persistence) we expect every chunk to carry an HMAC tag — anti-downgrade is
    // enforced locally: the receiver checks based on its own knowledge of the trust + channel
    // state, so an attacker can't strip the field to bypass verification.
    val verifyChunkMac: (suspend (chunk: FileChunkMessage) -> Boolean)? =
      if (!cipher.authenticated && trustManager.macKeyFor(fromDeviceId) != null) {
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
      fileMessageHandler.beginReceive(
        header,
        fromDeviceId,
        receiveFlow,
        verifyChunkMac,
        linkAuthenticated = cipher.authenticated,
        transferAnchor = transferAnchor,
        transferAnchorId = anchorId,
      )
    }.getOrElse { error ->
      log("MessagesRouter", "beginReceive failed for ${header.fileName}: ${error.message}", error)
      return
    }
    // From here on the pipeline releases the anchor — including on the ACK_READY failure path
    // below, which fails the pipeline rather than returning silently.
    onAnchorHandedOff()

    receiveMutex.withLock {
      receivePipelines[header.id]?.let { existing ->
        log("MessagesRouter", "Replacing in-flight pipeline for header id=${header.id} (likely duplicate header)")
        runCatching { existing.fail(IllegalStateException("Replaced by new header with same id")) }
      }
      receivePipelines[header.id] = pipeline
    }

    // If the connection dropped between accept and here, sending ACK_READY throws. Don't let that
    // escape uncaught (it crashed a dispatcher worker) and don't leak the registered pipeline — the
    // sender never got ACK_READY so it won't stream chunks; fail the transfer and drop the pipeline.
    val ackReady = MessageAcknowledgment(AckType.READY, ackId)
    val ackSent = runCatching { writeLock.withLock { sendMessageToDevice(fromDeviceId, ackReady, writeChannel, cipher) } }
    if (ackSent.isFailure) {
      val error = ackSent.exceptionOrNull() ?: IllegalStateException("ACK_READY send failed")
      log("MessagesRouter", "Failed to send ACK_READY for ${header.fileName} to $fromDeviceId (connection dropped?); failing transfer", error)
      receiveMutex.withLock { receivePipelines.remove(header.id) }
      runCatching { pipeline.fail(error) }
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
    cipher: FrameCipher,
  ) {
    val pipeline = receiveMutex.withLock { receivePipelines[chunk.fileMessageId] }
    if (pipeline == null) {
      log("MessagesRouter", "Dropping chunk for unknown fileMessageId=${chunk.fileMessageId}")
      return
    }

    val chunkResult = runCatching { pipeline.acceptChunk(chunk) }

    if (chunk.isLast || chunkResult.isFailure) {
      receiveMutex.withLock { receivePipelines.remove(chunk.fileMessageId) }

      // Did the transfer finish intact? A mid-stream chunk failure fails it outright;
      // otherwise complete() runs finalization and reports its own verdict (it never throws,
      // so a finalize/integrity failure fails only this transfer instead of bubbling up to
      // acceptIncomingMessages, which would close the whole connection).
      val completedOk = if (chunkResult.isFailure) {
        runCatching { pipeline.fail(chunkResult.exceptionOrNull()!!) }
        false
      } else {
        pipeline.complete()
      }

      // Always send a terminal ACK so the sender resolves immediately rather than blocking
      // until its ACK_RECEIVED timeout and then retrying: RECEIVED when the file landed
      // intact, REJECTED on any failure (which the sender treats as terminal — no retry).
      val ack = MessageAcknowledgment(
        ackType = if (completedOk) AckType.RECEIVED else AckType.REJECTED,
        id = chunk.fileMessageId,
      )
      writeLock.withLock {
        sendMessageToDevice(fromDeviceId, ack, writeChannel, cipher)
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
    cipher: FrameCipher,
  ) {
    coroutines.ioDispatcher {
      val message = sendMessageRequest.message

      if (message is TrustedMessage) {
        writeLock.withLock {
          writeChannel.sendMessage(message, messageSerializer, cipher)
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
            // On an authenticated-encrypted link sendMessageToDevice skips the signature
            // (the channel already authenticates the peer) but still encrypts the frame.
            if (framedMessage is FileMessage) {
              sendMessageToDevice(toDeviceId, framedMessage, writeChannel, cipher)
            } else {
              writeChannel.sendMessage(framedMessage, messageSerializer, cipher)
            }
          }
        }
        // Per-chunk HMAC tags via the per-pair key derived from the ECDH shared secret. On an
        // authenticated-encrypted link the AEAD transport already authenticates every chunk
        // frame, so we skip the redundant HMAC (a throughput win); cleartext/unauthenticated
        // links keep it. Returns null when skipped or when no shared secret is on file — the
        // handler then relies on the SHA-256 binding inside the (signed) header.
        val chunkMacFn: suspend (FileChunkMessage) -> ByteArray? = { chunk ->
          if (cipher.authenticated) {
            null
          } else {
            trustManager.computeChunkMac(
              deviceId = toDeviceId,
              fileMessageId = chunk.fileMessageId,
              seq = chunk.seq,
              isLast = chunk.isLast,
              data = chunk.data,
            )
          }
        }
        fileMessageHandler.handleOutgoingChunked(
          toDeviceId = toDeviceId,
          request = fileRequest,
          sendFramed = sendFramed,
          progressFlow = progress,
          awaitReady = awaitReadyAck,
          chunkMacFn = chunkMacFn,
          linkAuthenticated = cipher.authenticated,
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
          messageHandler.handleOutgoingWithReadyAck(toDeviceId, sendMessageRequest, writeChannel, progress, awaitReadyAck, cipher)
        } else {
          messageHandler.handleOutgoing(toDeviceId, sendMessageRequest, writeChannel, progress, cipher)
        }
      }
    }
  }

  override suspend fun onPeerDisconnected(deviceId: String) {
    // Identity is the device, not the connection, so in principle a reconnect that re-sent its
    // header before this ran would have its fresh pipeline failed here too. In practice the read
    // loop reaches this within microseconds of the socket closing while a reconnect is a dial plus
    // a handshake, and the worst case is a transfer that restarts from zero rather than one that
    // silently corrupts — the same outcome the duplicate-header path in receiveFileHeader gives.
    val orphans = receiveMutex.withLock {
      val matching = receivePipelines.filterValues { it.fromDeviceId == deviceId }
      matching.keys.forEach { receivePipelines.remove(it) }
      matching.values.toList()
    }
    if (orphans.isEmpty()) return
    log("MessagesRouter", "Connection to $deviceId dropped with ${orphans.size} receive(s) in flight; failing them")
    orphans.forEach { pipeline ->
      // fail() marks the DB row FAILED, updates the receive flow and — the part that matters for
      // battery — releases the transfer anchor this receive was holding.
      runCatching { pipeline.fail(IllegalStateException("Connection to $deviceId was lost mid-transfer")) }
        .onFailure { log("MessagesRouter", "Failed to clean up orphaned receive from $deviceId", it) }
    }
  }

  /**
   * Anchor ids have to be unique per header *arrival*, not per header id. A duplicate header
   * replaces the in-flight pipeline and fails the old one; if both shared an anchor id, the
   * replaced pipeline's release would pull the anchor out from under its replacement.
   */
  private fun incomingAnchorId(deviceId: String, headerId: Int): String =
    "$deviceId:in:$headerId:${Random.nextInt()}"

  private companion object {
    /** Maximum number of processed inbound TEXT ids retained for dedup. */
    const val PROCESSED_TEXT_IDS_MAX = 256
  }
}
