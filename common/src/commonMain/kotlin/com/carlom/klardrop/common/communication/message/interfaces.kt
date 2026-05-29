package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.communication.FrameCipher
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
  ACK_REJECTED(5),
  // Sent by the receiver as soon as it starts blocking on a user accept/reject decision.
  // The sender treats it as "stop counting against the ACK_READY/RECEIVED timeout — the
  // peer is alive but waiting on a human" and switches to a much longer wait window.
  ACK_AWAITING_USER(6),

  // Trust system messages
  TRUST_PAIRING_REQUEST(10),
  TRUST_PAIRING_RESPONSE(11),
  TRUSTED_MESSAGE(12), // Handled directly in MessagesRouter for security verification
  CLIPBOARD_SYNC(13),
  TRUST_REVOCATION(14),

  // Connection info sharing (e.g. Wi-Fi credentials between own devices)
  CONNECTION_INFO(15),

  // Application-level liveness probes (heartbeat)
  PING(20),
  PONG(21),

  // Per-chunk frame for chunked file transfers. The FILE message is a header that
  // declares fileSize/name/mime; the actual bytes flow as a sequence of FILE_CHUNK
  // messages keyed by the header's id. Framing each chunk lets unrelated messages
  // (PING, ACK, TEXT, other FILE_CHUNK from another transfer) interleave between
  // chunks - no single writer holds the wire for the whole transfer.
  FILE_CHUNK(16),

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
  RECEIVED,
  REJECTED,
  AWAITING_USER,
}

@Serializable
data class MessageAcknowledgment(
  val ackType: AckType,
  override val id: Int = Random.nextInt(),
) : Message() {
  override val type: MessageType = when (ackType) {
    AckType.READY -> MessageType.ACK_READY
    AckType.RECEIVED -> MessageType.ACK_RECEIVED
    AckType.REJECTED -> MessageType.ACK_REJECTED
    AckType.AWAITING_USER -> MessageType.ACK_AWAITING_USER
  }
  override val hasPayload: Boolean = false
}

/**
 * Thrown when the receiver explicitly declined a transfer. Distinct from a generic ACK
 * timeout because the rejection is a deliberate user action, so retrying doesn't help —
 * Messenger surfaces this as a terminal error rather than triggering reconnect-and-retry.
 */
class TransferRejectedException(val messageId: Int) :
  RuntimeException("Transfer rejected by recipient (messageId=$messageId)")

interface MessageHandler<E : Message, R : SendMessageRequest> {

  suspend fun handleIncoming(message: E, readChannel: ByteReadChannel, receiveFlow: MutableStateFlow<ReceiveMessageUpdate>)

  /**
   * Sends [request]. [cipher] is the connection's transport cipher — handlers MUST pass it to
   * every `writeChannel.sendMessage(...)` call so the frame is encrypted on an encrypted link
   * ([FrameCipher.Plain] is a no-op for cleartext links).
   */
  suspend fun handleOutgoing(toDeviceId: String, request: R, writeChannel: ByteWriteChannel, progressFlow: MutableSharedFlow<MessengerSendProgress>, cipher: FrameCipher)

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
    cipher: FrameCipher,
  ) {
    handleOutgoing(toDeviceId, request, writeChannel, progressFlow, cipher)
  }
}
