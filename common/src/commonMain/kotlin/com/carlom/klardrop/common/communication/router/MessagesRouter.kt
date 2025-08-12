package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.AckType
import com.carlom.klardrop.common.communication.message.MessageAcknowledgment
import com.carlom.klardrop.common.communication.message.MessageHandlers
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.message.SendMessageRequest
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
    ackCallback: (suspend (MessageAcknowledgment) -> Unit)
  )

  suspend fun <S : SendMessageRequest> onSendingMessage(
    toDeviceId: String,
    sendMessageRequest: S,
    writeChannel: ByteWriteChannel,
    readChannel: ByteReadChannel,
    progress: MutableSharedFlow<MessengerSendProgress>
  )
}

internal class MessagesRouterImpl(
  private val handlers: MessageHandlers,
  private val messageSerializer: MessageSerializer,
  private val coroutines: Coroutines,
  private val messengeReceiver: MessageReceiver,
  private val trustManager: TrustManager
) : MessagesRouter {
  override suspend fun onMessageIncoming(
    fromDeviceId: String,
    writeChannel: ByteWriteChannel,
    readChannel: ByteReadChannel,
    ackCallback: (suspend (MessageAcknowledgment) -> Unit)
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
      rawMessage is com.carlom.klardrop.common.communication.message.TrustedMessage -> {
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

    // Skip ACK generation for ACK messages to prevent loops
    val isAckMessage = message.type == MessageType.ACK_READY || message.type == MessageType.ACK_RECEIVED

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
      writeChannel.sendMessage(ackReceived, messageSerializer)
      log("MessagesRouter", "Successfully sent ACK_RECEIVED for message ${message.id} to $fromDeviceId")
    }
  }

  override suspend fun <S : SendMessageRequest> onSendingMessage(
    toDeviceId: String,
    sendMessageRequest: S,
    writeChannel: ByteWriteChannel,
    readChannel: ByteReadChannel,
    progress: MutableSharedFlow<MessengerSendProgress>
  ) {
    coroutines.ioDispatcher {
      val message = sendMessageRequest.message

      // NOTE: Signature verification moved to onMessageIncoming() for security
      // We should not verify our own outgoing messages - they should be verified by the recipient

      if (message.hasPayload) {
        // message has extra payload. we need to handle it
        val messageHandler = handlers[message.type] ?: run {
          log("MessagesRouter", "No handler for message type ${message.type}")
          return@ioDispatcher
        }
        messageHandler.handleOutgoing(toDeviceId, sendMessageRequest, writeChannel, progress) // Passed toDeviceId
      } else {
        // message has no payload. we can send it directly
        // Note: Message insertion is handled optimistically by the ViewModel layer
        log("MessagesRouter", "[DEBUG] Sending message to $toDeviceId: type=${message.type}, id=${message.id}")
        writeChannel.sendMessage(message, messageSerializer)
      }
    }
  }
}
