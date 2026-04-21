package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlin.random.Random

enum class MessageType(val id: Byte) {

  HANDSHAKE(0),
  TEXT(1),
  FILE(2),
  ACK_READY(3),
  ACK_RECEIVED(4),
  
  // Trust system messages
  TRUST_PAIRING_REQUEST(10),
  TRUST_PAIRING_RESPONSE(11),
  TRUSTED_MESSAGE(12), // Handled directly in MessagesRouter for security verification
  CLIPBOARD_SYNC(13),
  TRUST_REVOCATION(14),

  ;

  companion object {
    fun fromId(id: Byte): MessageType {
      return MessageType.entries.first { it.id == id }
    }
  }

}

sealed class Message {
  open val id: Int = Random.nextInt()

  abstract val type: MessageType
  abstract val hasPayload: Boolean
}

sealed interface SendMessageRequest {
  val message: Message
}

/**
 * Signature wrapper for trusted messages - encapsulates all cryptographic data.
 */
@Serializable
data class MessageSignature(
    val signature: ByteArray,
    val timestamp: Long,
    val nonce: ByteArray,
    val senderId: String
) {
    /**
     * ByteArray-safe content comparison for MessageSignature.
     */
    fun contentEquals(other: MessageSignature?): Boolean {
        if (other == null) return false
        return signature.contentEquals(other.signature) && 
               timestamp == other.timestamp &&
               nonce.contentEquals(other.nonce) &&
               senderId == other.senderId
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageSignature) return false
        
        if (!signature.contentEquals(other.signature)) return false
        if (timestamp != other.timestamp) return false
        if (!nonce.contentEquals(other.nonce)) return false
        if (senderId != other.senderId) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = signature.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + senderId.hashCode()
        return result
    }
}

/**
 * SendMessageRequest with optional signature for trusted device communication.
 */
interface SignedSendMessageRequest : SendMessageRequest {
    val messageSignature: MessageSignature?
    
    /**
     * Returns true if this request contains a signature.
     */
    fun isSigned(): Boolean = messageSignature != null
    
    /**
     * Returns true if this request requires signature verification.
     */
    fun requiresVerification(): Boolean = isSigned()
}

fun Message.toSimpleSendRequest(messageSignature: MessageSignature? = null): SendMessageRequest {
  if (hasPayload) {
    throw IllegalStateException("Message has payload. Cant use an empty send request")
  }

  return SimpleSendMessageRequest(this, messageSignature)
}

class SimpleSendMessageRequest(
    override val message: Message,
    override val messageSignature: MessageSignature? = null
) : SignedSendMessageRequest

enum class AckType {
  READY,
  RECEIVED
}

@Serializable
data class MessageAcknowledgment(
  val ackType: AckType,
  override val id: Int = Random.nextInt(),
) : Message() {
  override val type: MessageType = when (ackType) {
    AckType.READY -> MessageType.ACK_READY
    AckType.RECEIVED -> MessageType.ACK_RECEIVED
  }
  override val hasPayload: Boolean = false
}

interface MessageHandler<E : Message, R : SendMessageRequest> {

  suspend fun handleIncoming(message: E, readChannel: ByteReadChannel, receiveFlow: MutableStateFlow<ReceiveMessageUpdate>)
  suspend fun handleOutgoing(toDeviceId: String, request: R, writeChannel: ByteWriteChannel, progressFlow: MutableSharedFlow<MessengerSendProgress>)

  /**
   * Variant for messages that have a payload. The handler MUST send the
   * message header first, then call [awaitReady] (which will block until the
   * receiver has sent ACK_READY for this message id), then stream the payload.
   *
   * Default implementation ignores [awaitReady] and delegates to [handleOutgoing] -
   * only payload-bearing handlers need to override this to add the ACK_READY
   * handshake between header and payload streaming.
   */
  suspend fun handleOutgoingWithReadyAck(
    toDeviceId: String,
    request: R,
    writeChannel: ByteWriteChannel,
    progressFlow: MutableSharedFlow<MessengerSendProgress>,
    awaitReady: suspend () -> Unit,
  ) {
    handleOutgoing(toDeviceId, request, writeChannel, progressFlow)
  }
}
