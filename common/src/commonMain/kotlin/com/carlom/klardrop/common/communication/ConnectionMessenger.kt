package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.AckType
import com.carlom.klardrop.common.communication.message.MessageAcknowledgment
import com.carlom.klardrop.common.communication.message.PingMessage
import com.carlom.klardrop.common.communication.message.PongMessage
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.TransferRejectedException
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.invoke
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.ExperimentalTime

class ConnectionMessenger internal constructor(
  private val coroutines: Coroutines,
  private val connection: Connection,
  private val messagesRouter: MessagesRouter,
  private val readChannel: ByteReadChannel,
  private val writeChannel: ByteWriteChannel,
  private val ackTimeoutConfig: AckTimeoutConfig = AckTimeoutConfig.DEFAULT,
  private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig.DEFAULT,
  private val messageSerializer: MessageSerializer? = null,
) {

  /** True when this messenger is bound to a BLE GATT session rather than a TCP socket. */
  val isBleTransport: Boolean get() = connection is Connection.Ble

  // Test-only constructor that lets a test override every ACK timeout with a single value.
  internal constructor(
    coroutines: Coroutines,
    connection: Connection,
    messagesRouter: MessagesRouter,
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel,
    ackTimeoutMs: Long,
  ) : this(
    coroutines = coroutines,
    connection = connection,
    messagesRouter = messagesRouter,
    readChannel = readChannel,
    writeChannel = writeChannel,
    ackTimeoutConfig = AckTimeoutConfig.uniform(ackTimeoutMs),
    heartbeatConfig = HeartbeatConfig(enabled = false),
  )

  // ACK correlation system - keyed by (messageId, ackType) since the same message
  // id can have both an ACK_READY and an ACK_RECEIVED outstanding (for payload sends).
  private data class AckKey(val messageId: Int, val ackType: AckType)

  private val pendingAcks = mutableMapOf<AckKey, Channel<Unit>>()
  private val ackMutex = Mutex()

  // Heartbeat correlation: ping id → channel signalled when the matching pong arrives.
  private val pendingPongs = mutableMapOf<Int, Channel<Unit>>()
  private val pongMutex = Mutex()
  private val heartbeatScope: CoroutineScope = CoroutineScope(SupervisorJob() + coroutines.ioDispatcher)
  private var heartbeatJob: Job? = null

  // Serializes every write through [writeChannel]. Ktor's ByteChannel rejects concurrent
  // writers with ConcurrentIOException, and three coroutines write here:
  //   - heartbeat ping (this class), tryLock so an in-flight transfer doesn't get killed
  //   - outgoing send (file/text payload, via MessagesRouter.onSendingMessage)
  //   - incoming-message reply (PONG, ACK_READY, ACK_RECEIVED, via MessagesRouter.onMessageIncoming)
  // All three honor this single mutex; the router takes it as a parameter.
  private val writeLock = Mutex()

  init {
    if (connection.isClosed) {
      throw IllegalStateException("Connection with ${connection.deviceId} is closed.")
    }
  }

  //  activates read from socket
  suspend fun acceptIncomingMessages() = coroutines.ioDispatcher {
    startHeartbeat()
    while (!readChannel.isClosedForRead) {
      log("ConnectionMessenger: Listening for new messages from ${connection.deviceId}")

      runCatching {
        // Use the existing router but register ourselves for ACK + PONG handling
        messagesRouter.onMessageIncoming(
          fromDeviceId = connection.deviceId,
          writeChannel = writeChannel,
          readChannel = readChannel,
          ackCallback = { ack ->
            log("ConnectionMessenger: Received ACK callback for message ${ack.id}, ackType: ${ack.ackType}")
            handleAckMessage(ack)
          },
          pongCallback = { pong ->
            log("ConnectionMessenger: Received PONG callback for ping ${pong.pingId}")
            handlePongMessage(pong)
          },
          writeLock = writeLock,
        )
      }.onFailure {
        log("ConnectionMessenger: Exception in acceptIncomingMessages loop for ${connection.deviceId}: ${it::class.simpleName}: ${it.message}")
        log("ConnectionMessenger: Error while listening for messages from ${connection.deviceId}. Closing connection.", it)
        close()
      }
    }

    log("ConnectionMessenger: Stop listening for messages from ${connection.deviceId}")
    stopHeartbeat()
  }

  /**
   * Public hook for the application-level liveness probe. Visible-for-test so
   * unit tests can drive the heartbeat without a full read loop.
   */
  fun startHeartbeat() {
    if (!heartbeatConfig.enabled) return
    if (heartbeatJob?.isActive == true) return
    val serializer = messageSerializer ?: run {
      log("ConnectionMessenger: heartbeat enabled but no MessageSerializer provided; skipping for ${connection.deviceId}")
      return
    }
    heartbeatJob = heartbeatScope.launch {
      heartbeatLoop(serializer)
    }
  }

  private fun stopHeartbeat() {
    heartbeatJob?.cancel()
    heartbeatJob = null
  }

  private suspend fun heartbeatLoop(serializer: MessageSerializer) {
    while (!isClosed()) {
      delay(heartbeatConfig.interval)
      if (isClosed()) return

      // If another writer (file send / ACK reply) is currently holding the write lock,
      // skip this heartbeat tick — an in-flight write IS evidence the link is alive,
      // and queuing a PING behind a multi-MB transfer would just stall the heartbeat
      // until the transfer finishes (and might never get a PONG back, since the peer
      // is busy reading our payload bytes — they'd treat any PING bytes mid-stream as
      // payload, corrupting it).
      if (!writeLock.tryLock()) {
        log("ConnectionMessenger: Heartbeat skipped for ${connection.deviceId} (write in flight)")
        continue
      }

      val pingId = Random.nextInt()
      val pongChannel = Channel<Unit>(capacity = 1)
      pongMutex.withLock { pendingPongs[pingId] = pongChannel }

      val writeOk = runCatching {
        writeChannel.sendMessage(PingMessage(id = pingId), serializer)
        true
      }.getOrElse {
        log("ConnectionMessenger: Heartbeat write failed for ${connection.deviceId}: ${it.message}")
        false
      }
      writeLock.unlock()

      if (!writeOk) {
        pongMutex.withLock { pendingPongs.remove(pingId) }
        close()
        return
      }

      // Heartbeat runs entirely on real time (ioDispatcher) so it remains
      // independent of the virtual-time clock the test dispatcher uses for ACK
      // timeouts. We don't want a stalled main dispatcher to mask a dead peer.
      val gotPong = runCatching {
        withTimeout(heartbeatConfig.timeout.inWholeMilliseconds) { pongChannel.receive() }
        true
      }.getOrElse {
        false
      }
      pongMutex.withLock { pendingPongs.remove(pingId) }

      if (!gotPong) {
        log("ConnectionMessenger: Heartbeat missed PONG for ${connection.deviceId} (ping=$pingId), closing connection")
        close()
        return
      }
    }
  }

  /** Public hook for tests: signal arrival of a PONG matching an outstanding ping. */
  suspend fun handlePongMessage(pong: PongMessage) {
    pongMutex.withLock {
      val channel = pendingPongs[pong.pingId]
      if (channel != null) {
        channel.trySend(Unit)
        pendingPongs.remove(pong.pingId)
      } else {
        log("ConnectionMessenger: Unexpected PONG for ping ${pong.pingId} - no matching pending heartbeat")
      }
    }
  }


  // Public method for ACK message handling - called by MessagesRouter or AckMessageHandler
  suspend fun handleAckMessage(ack: MessageAcknowledgment) {
    log("ConnectionMessenger: Received ACK ${ack.ackType} for message ${ack.id} from ${connection.deviceId}")

    val key = AckKey(ack.id, ack.ackType)
    ackMutex.withLock {
      val channel = pendingAcks[key]
      if (channel != null) {
        // Signal the waiting sender
        val sendResult = channel.trySend(Unit)
        if (sendResult.isSuccess) {
          pendingAcks.remove(key)
          log("ConnectionMessenger: Successfully signaled ACK ${ack.ackType} for message ${ack.id}")
        } else {
          log("ConnectionMessenger: Failed to signal ACK ${ack.ackType} for message ${ack.id}: ${sendResult.exceptionOrNull()}")
        }
      } else {
        log("ConnectionMessenger: Unexpected ACK ${ack.ackType} for message ${ack.id} - no matching pending request")
      }
    }
  }

  /**
   * Registers a pending ACK request BEFORE sending the message.
   * This prevents race conditions where ACK arrives before registration.
   */
  private suspend fun registerPendingAck(messageId: Int, ackType: AckType): Channel<Unit> {
    val channel = Channel<Unit>(capacity = 1)
    log("ConnectionMessenger: [DEBUG] Registering pending ACK $ackType for message $messageId to ${connection.deviceId}")

    ackMutex.withLock {
      pendingAcks[AckKey(messageId, ackType)] = channel
    }

    return channel
  }

  /**
   * Waits for a previously registered ACK to arrive, or for a rejection ACK to arrive
   * on [rejectedChannel]. If rejection wins, throws [TransferRejectedException]; the
   * caller should treat it as a terminal (non-retryable) failure rather than a transport
   * error. If [awaitingUserChannel] is supplied and ACK_AWAITING_USER arrives first, the
   * wait restarts with [AckTimeoutConfig.userResponseTimeout] — the peer told us a human
   * is being prompted, so the short wire-level timeout would otherwise spuriously fire and
   * trigger a retry that produces a duplicate prompt on the receiver. If nothing arrives
   * within the timeout, throws [IllegalStateException] (which Messenger interprets as a
   * transport-level retryable failure).
   */
  @OptIn(ExperimentalTime::class)
  private suspend fun awaitRegisteredAck(
    messageId: Int,
    ackType: AckType,
    channel: Channel<Unit>,
    hasPayload: Boolean,
    rejectedChannel: Channel<Unit>? = null,
    awaitingUserChannel: Channel<Unit>? = null,
  ) {
    val initialTimeoutMs = ackTimeoutConfig.timeoutFor(ackType, hasPayload).inWholeMilliseconds
    val userTimeoutMs = ackTimeoutConfig.userResponseTimeout.inWholeMilliseconds
    log("ConnectionMessenger: [DEBUG] Awaiting ACK $ackType for message $messageId from ${connection.deviceId} (timeout: ${initialTimeoutMs}ms)")

    try {
      withContext(coroutines.mainDispatcher) {
        var timeoutMs = initialTimeoutMs
        var awaitingUserActive = awaitingUserChannel
        while (true) {
          val outcome = withTimeout(timeoutMs) {
            select<AckOutcome> {
              channel.onReceive { AckOutcome.Acked }
              if (rejectedChannel != null) {
                rejectedChannel.onReceive { AckOutcome.Rejected }
              }
              if (awaitingUserActive != null) {
                awaitingUserActive.onReceive { AckOutcome.AwaitingUser }
              }
            }
          }
          when (outcome) {
            AckOutcome.Acked -> return@withContext
            AckOutcome.Rejected -> throw TransferRejectedException(messageId)
            AckOutcome.AwaitingUser -> {
              log("ConnectionMessenger: [DEBUG] Peer signalled awaiting-user for message $messageId; extending timeout to ${userTimeoutMs}ms")
              timeoutMs = userTimeoutMs
              // Only honor the AWAITING_USER signal once per wait so a misbehaving peer
              // can't pin the sender open indefinitely by re-sending it.
              awaitingUserActive = null
            }
          }
        }
      }
      log("ConnectionMessenger: [DEBUG] Successfully received ACK $ackType for message $messageId from ${connection.deviceId}")
    } catch (e: TransferRejectedException) {
      log("ConnectionMessenger: [DEBUG] Transfer rejected for message $messageId by ${connection.deviceId}")
      ackMutex.withLock {
        pendingAcks.remove(AckKey(messageId, ackType))
        pendingAcks.remove(AckKey(messageId, AckType.REJECTED))
        pendingAcks.remove(AckKey(messageId, AckType.AWAITING_USER))
      }
      channel.close()
      rejectedChannel?.close()
      awaitingUserChannel?.close()
      throw e
    } catch (e: Exception) {
      log("ConnectionMessenger: [DEBUG] ACK timeout for message $messageId, cleaning up pending request")
      // Cleanup pending ACK on timeout or error
      ackMutex.withLock {
        pendingAcks.remove(AckKey(messageId, ackType))
        if (rejectedChannel != null) pendingAcks.remove(AckKey(messageId, AckType.REJECTED))
        if (awaitingUserChannel != null) pendingAcks.remove(AckKey(messageId, AckType.AWAITING_USER))
      }
      channel.close()
      rejectedChannel?.close()
      awaitingUserChannel?.close()
      throw IllegalStateException("ACK timeout: Expected $ackType for message $messageId from ${connection.deviceId}")
    }
  }

  private enum class AckOutcome { Acked, Rejected, AwaitingUser }

  suspend fun <S : SendMessageRequest> send(sendRequest: S, flow: MutableSharedFlow<MessengerSendProgress>) {
    val message = sendRequest.message

    if (isClosed()) {
      throw IllegalStateException("Connection with ${connection.deviceId} is closed")
    }

    try {
      coroutines.ioDispatcher {
        // Register pending ACKs BEFORE sending message to prevent race conditions
        // (ACK could otherwise arrive before we register and be dropped as
        // "unexpected"). REJECTED and AWAITING_USER are registered alongside
        // RECEIVED/READY so a peer that declined the transfer (or is blocking on a
        // human accept/reject prompt) can short-circuit the wait — REJECTED ends it
        // terminally, AWAITING_USER extends the timeout window.
        val receivedChannel = registerPendingAck(message.id, AckType.RECEIVED)
        val rejectedChannel = registerPendingAck(message.id, AckType.REJECTED)
        val awaitingUserChannel = registerPendingAck(message.id, AckType.AWAITING_USER)

        if (message.hasPayload) {
          // Two-phase: header → wait ACK_READY (or ACK_REJECTED) → payload → wait
          // ACK_RECEIVED (or ACK_REJECTED). For files the rejection almost always
          // arrives in place of READY (receiver decides before any bytes flow);
          // racing rejection in the RECEIVED wait too is just a safety net.
          val readyChannel = registerPendingAck(message.id, AckType.READY)
          val awaitReady: suspend () -> Unit = {
            awaitRegisteredAck(
              message.id, AckType.READY, readyChannel,
              hasPayload = true,
              rejectedChannel = rejectedChannel,
              awaitingUserChannel = awaitingUserChannel,
            )
          }
          messagesRouter.onSendingMessage(
            connection.deviceId, sendRequest, writeChannel, readChannel, flow, awaitReady, writeLock,
          )
        } else {
          messagesRouter.onSendingMessage(
            connection.deviceId, sendRequest, writeChannel, readChannel, flow, writeLock = writeLock,
          )
        }

        awaitRegisteredAck(
          message.id, AckType.RECEIVED, receivedChannel,
          hasPayload = message.hasPayload,
          rejectedChannel = rejectedChannel,
          // For payload-bearing messages the AWAITING_USER signal already fired during the
          // ACK_READY wait, so no need to re-arm it here. For no-payload (TEXT) messages
          // the receiver sends AWAITING_USER before the (single) ACK_RECEIVED, so we need
          // to honor it on this wait.
          awaitingUserChannel = if (message.hasPayload) null else awaitingUserChannel,
        )
      }
    } catch (exception: Throwable) {
      log("ConnectionMessenger: Exception while sending message ${message.id} to ${connection.deviceId}", exception)
      // Close the socket so the next send forces a fresh connection and so the
      // pool's isClosed() check evicts this entry. Do NOT emit a terminal flow
      // event here - retry/terminal is owned by Messenger.handleKlardropTransfer.
      // EXCEPTION: TransferRejectedException is a deliberate user decision, not a
      // transport failure — the connection is still healthy and reusable for other
      // sends. Don't close it just because this transfer was declined.
      if (exception !is TransferRejectedException) {
        close()
      }
      throw exception
    }
  }


  fun close() = runCatching {
    if (!connection.isClosed) {
      log("ConnectionMessenger: [DEBUG] Explicitly closing connection with ${connection.deviceId}")
      connection.close()
      log("ConnectionMessenger: [DEBUG] Connection closed for ${connection.deviceId}")
    } else {
      log("ConnectionMessenger: [DEBUG] close() called but connection already closed for ${connection.deviceId}")
    }
  }

  fun isClosed(): Boolean {
    // Check if the transport is explicitly closed (socket / BLE session).
    if (connection.isClosed) {
      log("ConnectionMessenger: [DEBUG] isClosed() = true - transport is explicitly closed for ${connection.deviceId}")
      return true
    }

    // Check if read/write channels are closed (indicates remote closure).
    val readClosed = readChannel.isClosedForRead
    val writeClosed = writeChannel.isClosedForWrite

    log("ConnectionMessenger: [DEBUG] isClosed() check for ${connection.deviceId}: readClosed=$readClosed, writeClosed=$writeClosed")

    if (readClosed || writeClosed) {
      log("ConnectionMessenger: [DEBUG] Detected channel closure for ${connection.deviceId}, closing transport (readClosed=$readClosed, writeClosed=$writeClosed)")
      runCatching { connection.close() }
        .onFailure { log("Failed closing the connection", it) }
      return true
    }

    log("ConnectionMessenger: [DEBUG] isClosed() = false for ${connection.deviceId}")
    return false
  }
}
