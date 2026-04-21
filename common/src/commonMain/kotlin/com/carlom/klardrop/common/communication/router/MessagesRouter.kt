package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.AckType
import com.carlom.klardrop.common.communication.message.MessageAcknowledgment
import com.carlom.klardrop.common.communication.message.MessageHandlers
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.message.PingMessage
import com.carlom.klardrop.common.communication.message.PongMessage
import com.carlom.klardrop.common.communication.message.SendMessageRequest
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

interface MessagesRouter {
  suspend fun onMessageIncoming(
    fromDeviceId: String,
    writeChannel: ByteWriteChannel,
    readChannel: ByteReadChannel,
    ackCallback: (suspend (MessageAcknowledgment) -> Unit),
    pongCallback: (suspend (PongMessage) -> Unit) = {},
  )

  /**
   * Sends a message. For payload-bearing messages, [awaitReadyAck] should be a
   * callback that suspends until the receiver has sent ACK_READY for this
   * message id; the handler is responsible for invoking it between the
   * header write and the payload stream. Default no-op for backward-compat
   * with message types that have no payload.
   */
  suspend fun <S : SendMessageRequest> onSendingMessage(
    toDeviceId: String,
    sendMessageRequest: S,
    writeChannel: ByteWriteChannel,
    readChannel: ByteReadChannel,
    progress: MutableSharedFlow<MessengerSendProgress>,
    awaitReadyAck: suspend () -> Unit = {},
  )
}

internal class MessagesRouterImpl(
  private val handlers: MessageHandlers,
  private val messageSerializer: MessageSerializer,
  private val coroutines: Coroutines,
  private val messengeReceiver: MessageReceiver,
  private val trustManager: TrustManager
) : MessagesRouter {

  /**
   * Unified method to send a message with automatic signing for trusted devices.
   * If the device is trusted, the message will be signed. Otherwise, it's sent as-is.
   */
  private suspend fun sendMessageToDevice(
    deviceId: String,
    message: com.carlom.klardrop.common.communication.message.Message,
    writeChannel: ByteWriteChannel
  ) {
    if (trustManager.isTrusted(deviceId)) {
      val trustedMessage = trustManager.signMessage(messageSerializer.serialize(message))
      if (trustedMessage != null) {
        writeChannel.sendMessage(trustedMessage, messageSerializer)
      } else {
        log("MessagesRouter", "Failed to sign message for trusted device $deviceId")
        writeChannel.sendMessage(message, messageSerializer) // fallback to unsigned
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
  ) = coroutines.ioDispatcher {

    val rawMessage = readChannel.readMessage(messageSerializer)
    log(
      "MessagesRouter",
      "[DEBUG] Raw message received from $fromDeviceId: type=${rawMessage.type}, id=${rawMessage.id}, hasPayload=${rawMessage.hasPayload}"
    )
    log("MessagesRouter", "Received message from $fromDeviceId: $rawMessage")

    // SECURITY: Verify signatures for trusted device communication
    val message = when {
      // Handle TrustedMessage verification
      rawMessage is TrustedMessage -> {
        log("MessagesRouter", "Processing TrustedMessage from $fromDeviceId")

        // Verify the trusted message signature
        val isValid = trustManager.verifyMessage(rawMessage)
        if (!isValid) {
          log("MessagesRouter", "SECURITY: Signature verification failed for TrustedMessage from $fromDeviceId")
          // Silent drop - do not process invalid signed messages
          return@ioDispatcher
        }

        log("MessagesRouter", "SECURITY: Signature verification successful for TrustedMessage from $fromDeviceId")

        // Deserialize the original message from the payload
        val originalMessage = messageSerializer.deserialize(rawMessage.payload)
        log("MessagesRouter", "Extracted original message: type=${originalMessage.type}, id=${originalMessage.id}")
        originalMessage
      }

      // Handle regular messages - check if they should be signed
      else -> {
        val isTrustedDevice = trustManager.isTrusted(fromDeviceId)

        // Skip signature requirements for pairing messages (they're part of trust establishment)
        val isPairingMessage = rawMessage is com.carlom.klardrop.common.communication.message.TrustPairingRequest ||
            rawMessage is com.carlom.klardrop.common.communication.message.TrustPairingResponse

        if (isTrustedDevice && !isPairingMessage) {
          log("MessagesRouter", "SECURITY: Unsigned message from trusted device $fromDeviceId - rejecting")
          // Silent drop - trusted devices should send signed messages
          return@ioDispatcher
        }

        if (!isTrustedDevice) {
          log("MessagesRouter", "Processing unsigned message from untrusted device $fromDeviceId")
        } else {
          log("MessagesRouter", "Processing pairing message from trusted device $fromDeviceId")
        }

        rawMessage
      }
    }

    // Handle ACK messages specially - call the callback instead of normal processing
    if (message is MessageAcknowledgment) {
      log("MessagesRouter", "Received ACK message: ${message.ackType} for message ${message.id}")

      ackCallback(message)
      log("MessagesRouter", "ACK callback completed for message ${message.id}")
      return@ioDispatcher
    }

    // Heartbeat: PING => reply with PONG immediately; PONG => signal sender via callback.
    if (message is PingMessage) {
      log("MessagesRouter", "Received PING ${message.id} from $fromDeviceId, replying with PONG")
      writeChannel.sendMessage(PongMessage(pingId = message.id), messageSerializer)
      return@ioDispatcher
    }
    if (message is PongMessage) {
      log("MessagesRouter", "Received PONG for ping ${message.pingId} from $fromDeviceId")
      pongCallback(message)
      return@ioDispatcher
    }

    // Skip ACK generation for ACK / heartbeat messages to prevent loops
    val isAckMessage = message.type == MessageType.ACK_READY ||
        message.type == MessageType.ACK_RECEIVED ||
        message.type == MessageType.PING ||
        message.type == MessageType.PONG

    val receiveFlow = messengeReceiver.onReceiveMessage(fromDeviceId)

    if (message.hasPayload) {
      // Send ACK_READY for payload messages before processing
      if (!isAckMessage) {
        val ackReady = MessageAcknowledgment(AckType.READY, message.id)
        writeChannel.sendMessage(ackReady, messageSerializer)
        log("MessagesRouter", "Sent ACK_READY for message ${message.id} to $fromDeviceId")
      }

      // message has extra payload. we need to handle it
      val messageHandler = handlers[message.type] ?: run {
        log("MessagesRouter", "No handler for message type ${message.type}")
        return@ioDispatcher
      }
      messageHandler.handleIncoming(message, readChannel, receiveFlow)
    } else {
      // For messages without payload, process them through handler if available, otherwise directly
      val messageHandler = handlers[message.type]
      if (messageHandler != null) {
        messageHandler.handleIncoming(message, readChannel, receiveFlow)
      } else {
        error("No handler found in MessagesRouter for message type ${message.type} with id ${message.id}")
      }
    }

    // Send ACK_RECEIVED after successful processing (for all non-ACK messages)
    if (!isAckMessage) {
      val ackReceived = MessageAcknowledgment(AckType.RECEIVED, message.id)
      log("MessagesRouter", "About to send ACK_RECEIVED for message ${message.id} to $fromDeviceId")

      sendMessageToDevice(fromDeviceId, ackReceived, writeChannel)

      log("MessagesRouter", "Successfully sent ACK_RECEIVED for message ${message.id} to $fromDeviceId")
    }
  }

  override suspend fun <S : SendMessageRequest> onSendingMessage(
    toDeviceId: String,
    sendMessageRequest: S,
    writeChannel: ByteWriteChannel,
    readChannel: ByteReadChannel,
    progress: MutableSharedFlow<MessengerSendProgress>,
    awaitReadyAck: suspend () -> Unit,
  ) {
    coroutines.ioDispatcher {
      val message = sendMessageRequest.message

      // TrustedMessage is already signed, send it directly
      if (message is TrustedMessage) {
        writeChannel.sendMessage(message, messageSerializer)
        return@ioDispatcher
      }

      val messageHandler = handlers[message.type] ?: run {
        log("MessagesRouter", "No handler for message type ${message.type}")
        return@ioDispatcher
      }

      if (message.hasPayload) {
        messageHandler.handleOutgoingWithReadyAck(toDeviceId, sendMessageRequest, writeChannel, progress, awaitReadyAck)
      } else {
        messageHandler.handleOutgoing(toDeviceId, sendMessageRequest, writeChannel, progress)
      }
    }
  }
}
