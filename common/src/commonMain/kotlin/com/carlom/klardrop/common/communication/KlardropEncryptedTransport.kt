package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.utils.log
import com.carlonzo.ukey2.Ukey2Handshake
import com.carlonzo.ukey2.d2d.D2DConnectionContext
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeByteArray

/**
 * Per-frame transform applied to the Klardrop wire payload right before it is length-prefixed
 * and written, and right after a length-prefixed frame is read. It is the single seam where
 * transport encryption slots into the otherwise-unchanged `[1-byte type][protobuf]` envelope.
 *
 * - [FrameCipher.Plain] is the identity transform used for cleartext links (the UKEY2 handshake
 *   leg itself, the BLE transport, and any path that hasn't established a secure channel).
 * - [FrameCipher.Encrypted] wraps a UKEY2 [D2DConnectionContext], so every frame is AES-GCM
 *   encrypted + authenticated with per-direction keys and sequence numbers managed by the
 *   library.
 *
 * THREADING: [encode] mutates the context's send sequence number, so it MUST be called under the
 * same write mutex that serializes the actual socket write (see [ConnectionMessenger.writeLock]).
 * [decode] mutates the receive sequence number and is only ever called from the single-threaded
 * read loop, so it needs no extra synchronization.
 */
sealed interface FrameCipher {
  /**
   * True only when the encrypted channel has been bound to the peer's persistent device identity
   * (their stored ECDSA key signed the UKEY2 verification string). Drives whether the redundant
   * per-message ECDSA signing / per-chunk HMAC can be skipped — see [router.MessagesRouterImpl].
   * Opportunistic (trust-on-first-use) encryption to an unpaired peer is confidential but NOT
   * authenticated, so it reports `false`.
   */
  val authenticated: Boolean

  fun encode(payload: ByteArray): ByteArray
  fun decode(wire: ByteArray): ByteArray

  /** Identity transform: cleartext in, cleartext out. */
  object Plain : FrameCipher {
    override val authenticated: Boolean = false
    override fun encode(payload: ByteArray): ByteArray = payload
    override fun decode(wire: ByteArray): ByteArray = wire
  }

  /** AES-GCM frame encryption backed by a completed UKEY2 [D2DConnectionContext]. */
  class Encrypted(
    private val context: D2DConnectionContext,
    override val authenticated: Boolean,
  ) : FrameCipher {
    override fun encode(payload: ByteArray): ByteArray = context.encodeMessageToPeer(payload)
    override fun decode(wire: ByteArray): ByteArray = context.decodeMessageFromPeer(wire)
  }
}

/**
 * Runs the UKEY2 handshake over an already-open Klardrop connection (right after the cleartext
 * [com.carlom.klardrop.common.communication.message.HandshakeMessage] exchange) and binds the
 * resulting secure channel to the peer's persistent device identity.
 *
 * The handshake mirrors the Nearby Share path (`NearbyClient/ReceiverConnectionHandler`) but uses
 * the Klardrop `[4-byte length][bytes]` framing for the handshake messages. After the ephemeral
 * UKEY2 key agreement completes we run a short identity-binding exchange: each side signs the
 * UKEY2 verification string with its device ECDSA key and verifies the peer's signature against
 * the stored peer key. This converts the otherwise-anonymous (MITM-vulnerable) UKEY2 channel into
 * an authenticated one for already-trusted peers; for not-yet-paired peers it falls back to
 * trust-on-first-use (encrypted but unauthenticated — [FrameCipher.Encrypted.authenticated] is
 * `false`).
 */
object KlardropEncryptedTransport {

  private const val TAG = "KlardropEncryptedTransport"
  private const val VERIFICATION_STRING_LENGTH = 32

  /** Initiator (TCP/BLE client) side of the handshake. Sends the first UKEY2 message. */
  suspend fun runInitiatorHandshake(
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel,
    selfDeviceId: String,
    peerDeviceId: String,
    trustManager: TrustManager,
  ): FrameCipher.Encrypted {
    val client = Ukey2Handshake.forInitiator(Ukey2Handshake.HandshakeCipher.P256_SHA512)

    // Message 1 (Client Init)
    writeChannel.writeFrame(client.getNextHandshakeMessage())
    // Message 2 (Server Init)
    client.parseHandshakeMessage(readChannel.readByteArrayMessage())
    // Message 3 (Client Finish)
    writeChannel.writeFrame(client.getNextHandshakeMessage())

    val verificationString = client.getVerificationString(VERIFICATION_STRING_LENGTH)
    client.verifyHandshake()
    val context = client.toConnectionContext()

    // Initiator sends its binding first, then reads the peer's.
    val authenticated = exchangeBinding(
      context = context,
      verificationString = verificationString,
      selfDeviceId = selfDeviceId,
      peerDeviceId = peerDeviceId,
      trustManager = trustManager,
      sendFirst = true,
      readChannel = readChannel,
      writeChannel = writeChannel,
    )
    log(TAG, "Initiator UKEY2 handshake complete with $peerDeviceId (authenticated=$authenticated)")
    return FrameCipher.Encrypted(context, authenticated)
  }

  /** Responder (TCP/BLE server) side of the handshake. Reads the first UKEY2 message. */
  suspend fun runResponderHandshake(
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel,
    selfDeviceId: String,
    peerDeviceId: String,
    trustManager: TrustManager,
  ): FrameCipher.Encrypted {
    val server = Ukey2Handshake.forResponder(Ukey2Handshake.HandshakeCipher.P256_SHA512)

    // Message 1 (Client Init)
    server.parseHandshakeMessage(readChannel.readByteArrayMessage())
    // Message 2 (Server Init)
    writeChannel.writeFrame(server.getNextHandshakeMessage())
    // Message 3 (Client Finish)
    server.parseHandshakeMessage(readChannel.readByteArrayMessage())

    val verificationString = server.getVerificationString(VERIFICATION_STRING_LENGTH)
    server.verifyHandshake()
    val context = server.toConnectionContext()

    // Responder reads the peer's binding first, then sends its own.
    val authenticated = exchangeBinding(
      context = context,
      verificationString = verificationString,
      selfDeviceId = selfDeviceId,
      peerDeviceId = peerDeviceId,
      trustManager = trustManager,
      sendFirst = false,
      readChannel = readChannel,
      writeChannel = writeChannel,
    )
    log(TAG, "Responder UKEY2 handshake complete with $peerDeviceId (authenticated=$authenticated)")
    return FrameCipher.Encrypted(context, authenticated)
  }

  /**
   * Exchange and verify the identity-binding signatures over the freshly established (but
   * not-yet-trusted) UKEY2 channel.
   *
   * @return true if the peer's signature verified against its stored ECDSA key (authenticated
   *   channel for a trusted peer). For a not-yet-trusted peer there is no stored key to verify
   *   against, so we accept trust-on-first-use and return false (encrypted but unauthenticated).
   * @throws IllegalStateException if the peer IS trusted but the binding signature does not verify
   *   — an active MITM relaying both UKEY2 handshakes cannot produce a valid signature over its
   *   own verification string with the peer's device key, so a mismatch means we abort.
   */
  private suspend fun exchangeBinding(
    context: D2DConnectionContext,
    verificationString: ByteArray,
    selfDeviceId: String,
    peerDeviceId: String,
    trustManager: TrustManager,
    sendFirst: Boolean,
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel,
  ): Boolean {
    val ourSignature = trustManager.signUkey2Binding(verificationString)
    val ourFrame = context.encodeMessageToPeer(encodeBinding(selfDeviceId, ourSignature))

    val peerBinding: Binding
    if (sendFirst) {
      writeChannel.writeFrame(ourFrame)
      peerBinding = decodeBinding(context.decodeMessageFromPeer(readChannel.readByteArrayMessage()))
    } else {
      peerBinding = decodeBinding(context.decodeMessageFromPeer(readChannel.readByteArrayMessage()))
      writeChannel.writeFrame(ourFrame)
    }

    if (!trustManager.isTrusted(peerDeviceId)) {
      // First contact / not yet paired: no stored key to authenticate the channel against.
      // Encryption still protects against passive sniffing; the human accept/reject prompt
      // remains the trust gate (and pairing carries the ECDSA keys in-band).
      log(TAG, "Peer $peerDeviceId not trusted; using opportunistic (unauthenticated) encryption")
      return false
    }

    val signature = peerBinding.signature
    val verified = peerBinding.senderId == peerDeviceId &&
      signature != null &&
      trustManager.verifyUkey2Binding(peerDeviceId, verificationString, signature)
    if (!verified) {
      throw IllegalStateException(
        "UKEY2 identity binding failed for trusted peer $peerDeviceId (possible MITM); aborting connection"
      )
    }
    return true
  }

  private data class Binding(val senderId: String, val signature: ByteArray?)

  /** Layout: [2-byte BE senderId length][senderId utf-8][signature bytes]. Empty signature == none. */
  private fun encodeBinding(senderId: String, signature: ByteArray?): ByteArray {
    val idBytes = senderId.encodeToByteArray()
    val sig = signature ?: ByteArray(0)
    val out = ByteArray(2 + idBytes.size + sig.size)
    out[0] = (idBytes.size ushr 8).toByte()
    out[1] = idBytes.size.toByte()
    idBytes.copyInto(out, 2)
    sig.copyInto(out, 2 + idBytes.size)
    return out
  }

  private fun decodeBinding(bytes: ByteArray): Binding {
    require(bytes.size >= 2) { "Malformed binding frame" }
    val idLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
    require(bytes.size >= 2 + idLen) { "Malformed binding frame" }
    val senderId = bytes.decodeToString(2, 2 + idLen)
    val sig = if (bytes.size > 2 + idLen) bytes.copyOfRange(2 + idLen, bytes.size) else null
    return Binding(senderId, sig?.takeIf { it.isNotEmpty() })
  }
}

/** Writes a raw `[4-byte big-endian length][bytes]` frame — the framing the Klardrop read path expects. */
internal suspend fun ByteWriteChannel.writeFrame(bytes: ByteArray) {
  val length = ByteArray(4)
  length[0] = (bytes.size ushr 24).toByte()
  length[1] = (bytes.size ushr 16).toByte()
  length[2] = (bytes.size ushr 8).toByte()
  length[3] = bytes.size.toByte()
  writeByteArray(length)
  writeByteArray(bytes)
}
