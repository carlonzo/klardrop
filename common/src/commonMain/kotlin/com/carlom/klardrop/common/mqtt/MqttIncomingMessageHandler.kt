package com.carlom.klardrop.common.mqtt

import kotlin.math.abs
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Receiver-side gating pipeline for MQTT messages.
 *
 * The point of this class is the **auto-accept invariant**: a transfer that
 * has gone through `handle(...)` and returned [Outcome.Deliver] is by
 * construction safe to forward straight to `ReceiveMessageStatus.Progress`,
 * skipping `ReceiveMessageStatus.PendingAuthorization`. No user prompt is
 * needed because:
 *
 *  - `senderDeviceId` is in [TrustedDeviceCache] (i.e. enrolled under the
 *    same user account, not yet revoked).
 *  - `signature` over the canonical envelope verifies against the cached
 *    public key — meaning the sender holds the private key issued at
 *    enrollment, even if the broker is malicious.
 *  - `(timestampMs, nonce)` is fresh per [ReplayProtector] — replays of a
 *    captured message are dropped silently.
 *  - `receiverDeviceId`, when present, matches the local device — defence
 *    in depth in case the broker delivered something it shouldn't have.
 *
 * Any failure becomes a typed [Outcome.Drop] result with a [DropReason] the
 * caller logs to metrics. We never throw on a bad envelope: an attacker
 * shouldn't be able to crash the receive pipeline.
 */
class MqttIncomingMessageHandler(
    private val localDeviceId: String,
    private val trustedDevices: TrustedDeviceCache,
    private val verifier: EnvelopeVerifier,
    private val replayProtector: ReplayProtector,
    private val clock: Clock,
    private val maxClockSkewMs: Long = DEFAULT_MAX_CLOCK_SKEW_MS,
    private val protobuf: ProtoBuf = DefaultProtoBuf
) {
    sealed class Outcome {
        data class Deliver(val sender: TrustedDevice, val payload: MqttPayload) : Outcome()
        data class Drop(val reason: DropReason, val senderDeviceId: String?) : Outcome()
    }

    enum class DropReason {
        NOT_TRUSTED,
        BAD_SIGNATURE,
        UNSUPPORTED_ALGORITHM,
        REPLAYED,
        CLOCK_SKEW,
        MISROUTED,
        MALFORMED
    }

    /**
     * Top-level entry point. [envelopeBytes] is the raw payload bytes the
     * platform MQTT client received — the topic only carries routing info
     * and is intentionally ignored here so the security check is independent
     * of the broker's view of the world.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun handle(envelopeBytes: ByteArray): Outcome {
        val envelope = runCatching { protobuf.decodeFromByteArray(SignedEnvelope.serializer(), envelopeBytes) }
            .getOrElse { return Outcome.Drop(DropReason.MALFORMED, senderDeviceId = null) }

        if (envelope.signatureAlgorithm != SignatureAlgorithm.ED25519) {
            return Outcome.Drop(DropReason.UNSUPPORTED_ALGORITHM, envelope.senderDeviceId)
        }

        val sender = trustedDevices.get(envelope.senderDeviceId)
            ?: return Outcome.Drop(DropReason.NOT_TRUSTED, envelope.senderDeviceId)

        if (envelope.receiverDeviceId.isNotEmpty() && envelope.receiverDeviceId != localDeviceId) {
            return Outcome.Drop(DropReason.MISROUTED, envelope.senderDeviceId)
        }

        val now = clock.nowMs()
        if (abs(now - envelope.timestampMs) > maxClockSkewMs) {
            return Outcome.Drop(DropReason.CLOCK_SKEW, envelope.senderDeviceId)
        }

        val canonical = SignedEnvelopeCanonical.bytesToSign(
            senderDeviceId = envelope.senderDeviceId,
            receiverDeviceId = envelope.receiverDeviceId,
            timestampMs = envelope.timestampMs,
            nonce = envelope.nonce,
            payload = envelope.payload
        )
        if (!verifier.verify(canonical, sender.publicKey, envelope.signature)) {
            return Outcome.Drop(DropReason.BAD_SIGNATURE, envelope.senderDeviceId)
        }

        if (!replayProtector.consume(envelope.senderDeviceId, envelope.nonce)) {
            return Outcome.Drop(DropReason.REPLAYED, envelope.senderDeviceId)
        }

        val payload = runCatching { protobuf.decodeFromByteArray(MqttPayload.serializer(), envelope.payload) }
            .getOrElse { return Outcome.Drop(DropReason.MALFORMED, envelope.senderDeviceId) }

        return Outcome.Deliver(sender = sender, payload = payload)
    }

    companion object {
        const val DEFAULT_MAX_CLOCK_SKEW_MS: Long = 60_000L

        @OptIn(ExperimentalSerializationApi::class)
        val DefaultProtoBuf: ProtoBuf = ProtoBuf { encodeDefaults = true }
    }
}

/**
 * Sender-side complement: package a payload into a SignedEnvelope using the
 * local signer + nonce + clock. Kept here so signing and verification stay
 * close together and any change to the canonical bytes layout is visible at
 * a glance.
 */
class MqttOutgoingMessageEncoder(
    private val localDeviceId: String,
    private val signer: EnvelopeSigner,
    private val clock: Clock,
    private val nonces: NonceProvider = RandomNonceProvider(),
    private val protobuf: ProtoBuf = MqttIncomingMessageHandler.DefaultProtoBuf
) {
    @OptIn(ExperimentalSerializationApi::class)
    fun encode(payload: MqttPayload, receiverDeviceId: String = ""): ByteArray {
        val payloadBytes = protobuf.encodeToByteArray(MqttPayload.serializer(), payload)
        val nonce = nonces.next()
        val timestampMs = clock.nowMs()
        val canonical = SignedEnvelopeCanonical.bytesToSign(
            senderDeviceId = localDeviceId,
            receiverDeviceId = receiverDeviceId,
            timestampMs = timestampMs,
            nonce = nonce,
            payload = payloadBytes
        )
        val signature = signer.sign(canonical)
        val envelope = SignedEnvelope(
            payload = payloadBytes,
            senderDeviceId = localDeviceId,
            receiverDeviceId = receiverDeviceId,
            timestampMs = timestampMs,
            nonce = nonce,
            signature = signature
        )
        return protobuf.encodeToByteArray(SignedEnvelope.serializer(), envelope)
    }
}
