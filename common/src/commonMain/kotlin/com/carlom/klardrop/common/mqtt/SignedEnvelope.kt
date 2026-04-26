package com.carlom.klardrop.common.mqtt

import kotlinx.serialization.Serializable

/**
 * Application-layer envelope wrapped around every MQTT publish that carries
 * transfer or trust state. Provides three properties that auto-accept on the
 * receiver side relies on:
 *
 *  1. **Origin authentication** — `signature` is verified against the sender's
 *     enrolled public key, fetched from the trusted-device cache. Even if the
 *     broker (or anything sitting on the wire) is compromised, a third party
 *     can't forge messages from a trusted device.
 *  2. **Replay protection** — `(timestampMs, nonce)` is checked against a
 *     short TTL replay-window cache; a captured envelope replayed later is
 *     rejected.
 *  3. **Routing context** — `senderDeviceId` and `receiverDeviceId` (when
 *     unicast) let the receiver discard misrouted publishes without having to
 *     deserialize the payload.
 *
 * The encoded form is protobuf via kotlinx-serialization-protobuf.
 */
@Serializable
data class SignedEnvelope(
    /** Serialized MqttPayload, protobuf-encoded. */
    val payload: ByteArray,
    /** Sender's stable deviceId (must be in the receiver's trusted set). */
    val senderDeviceId: String,
    /**
     * Receiver's stable deviceId for unicast messages, empty for fan-out
     * (presence / trust events) where the broker ACL already scopes delivery.
     */
    val receiverDeviceId: String = "",
    /** Wall-clock at signing. Receiver enforces |now - this| <= MAX_SKEW. */
    val timestampMs: Long,
    /** 16+ bytes of randomness; receiver tracks recent values to reject replays. */
    val nonce: ByteArray,
    /** Signature over canonical(senderDeviceId | receiverDeviceId | timestampMs | nonce | payload). */
    val signature: ByteArray,
    /** Identifies the signing algorithm; protects against future rotation. */
    val signatureAlgorithm: SignatureAlgorithm = SignatureAlgorithm.ED25519
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignedEnvelope) return false
        return senderDeviceId == other.senderDeviceId &&
            receiverDeviceId == other.receiverDeviceId &&
            timestampMs == other.timestampMs &&
            payload.contentEquals(other.payload) &&
            nonce.contentEquals(other.nonce) &&
            signature.contentEquals(other.signature) &&
            signatureAlgorithm == other.signatureAlgorithm
    }

    override fun hashCode(): Int {
        var result = senderDeviceId.hashCode()
        result = 31 * result + receiverDeviceId.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + signatureAlgorithm.hashCode()
        return result
    }
}

@Serializable
enum class SignatureAlgorithm {
    ED25519,
    /** Reserved for future ECDSA-P256 fallback if a platform lacks Ed25519. */
    ECDSA_P256
}

/**
 * Builds the canonical byte sequence over which `SignedEnvelope.signature`
 * is computed. Order is fixed so that sender and receiver always agree on
 * the bytes that go into the signature.
 *
 * Format:
 *
 *   [4 bytes BE] senderDeviceId.length
 *   senderDeviceId UTF-8 bytes
 *   [4 bytes BE] receiverDeviceId.length
 *   receiverDeviceId UTF-8 bytes
 *   [8 bytes BE] timestampMs
 *   [4 bytes BE] nonce.length
 *   nonce
 *   [4 bytes BE] payload.length
 *   payload
 */
object SignedEnvelopeCanonical {
    fun bytesToSign(
        senderDeviceId: String,
        receiverDeviceId: String,
        timestampMs: Long,
        nonce: ByteArray,
        payload: ByteArray
    ): ByteArray {
        val sender = senderDeviceId.encodeToByteArray()
        val receiver = receiverDeviceId.encodeToByteArray()
        val total = 4 + sender.size + 4 + receiver.size + 8 + 4 + nonce.size + 4 + payload.size
        val out = ByteArray(total)
        var i = 0
        i = writeIntBe(out, i, sender.size)
        sender.copyInto(out, i); i += sender.size
        i = writeIntBe(out, i, receiver.size)
        receiver.copyInto(out, i); i += receiver.size
        i = writeLongBe(out, i, timestampMs)
        i = writeIntBe(out, i, nonce.size)
        nonce.copyInto(out, i); i += nonce.size
        i = writeIntBe(out, i, payload.size)
        payload.copyInto(out, i); i += payload.size
        check(i == total)
        return out
    }

    private fun writeIntBe(out: ByteArray, offset: Int, value: Int): Int {
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
        return offset + 4
    }

    private fun writeLongBe(out: ByteArray, offset: Int, value: Long): Int {
        for (shift in 7 downTo 0) {
            out[offset + (7 - shift)] = (value ushr (shift * 8)).toByte()
        }
        return offset + 8
    }
}
