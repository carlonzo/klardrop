package com.carlom.klardrop.common.mqtt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MqttIncomingMessageHandlerTest {

    @Test
    fun trusted_signed_fresh_envelope_is_delivered_without_prompt() {
        val f = fixture()
        val envelope = f.encoder.encodeRequest("tid-1")

        val outcome = f.handler.handle(envelope)

        val deliver = assertIs<MqttIncomingMessageHandler.Outcome.Deliver>(outcome)
        val request = assertIs<MqttPayload.TransferRequest>(deliver.payload)
        assertEquals("tid-1", request.transferId)
        assertEquals(SENDER_DEVICE_ID, deliver.sender.deviceId)
    }

    @Test
    fun unknown_sender_is_dropped() {
        val f = fixture(includeSenderInTrust = false)
        val envelope = f.encoder.encodeRequest("tid-2")

        val outcome = f.handler.handle(envelope)

        val drop = assertIs<MqttIncomingMessageHandler.Outcome.Drop>(outcome)
        assertEquals(MqttIncomingMessageHandler.DropReason.NOT_TRUSTED, drop.reason)
    }

    @Test
    fun forged_signature_is_dropped() {
        val f = fixture(verifierAcceptsAnything = false)
        val envelope = f.encoder.encodeRequest("tid-3")

        val outcome = f.handler.handle(envelope)

        val drop = assertIs<MqttIncomingMessageHandler.Outcome.Drop>(outcome)
        assertEquals(MqttIncomingMessageHandler.DropReason.BAD_SIGNATURE, drop.reason)
    }

    @Test
    fun replayed_envelope_is_delivered_once_then_dropped() {
        val f = fixture()
        val envelope = f.encoder.encodeRequest("tid-4")

        assertIs<MqttIncomingMessageHandler.Outcome.Deliver>(f.handler.handle(envelope))
        val replay = f.handler.handle(envelope)

        val drop = assertIs<MqttIncomingMessageHandler.Outcome.Drop>(replay)
        assertEquals(MqttIncomingMessageHandler.DropReason.REPLAYED, drop.reason)
    }

    @Test
    fun envelope_outside_clock_skew_is_dropped() {
        val f = fixture(maxClockSkewMs = 1_000)
        val envelope = f.encoder.encodeRequest("tid-5")
        f.clock.advanceBy(10 * 60 * 1000L)

        val drop = assertIs<MqttIncomingMessageHandler.Outcome.Drop>(f.handler.handle(envelope))
        assertEquals(MqttIncomingMessageHandler.DropReason.CLOCK_SKEW, drop.reason)
    }

    @Test
    fun misrouted_envelope_is_dropped() {
        val f = fixture()
        val envelope = f.encoder.encodeRequest("tid-6", receiverDeviceId = "other-device")

        val drop = assertIs<MqttIncomingMessageHandler.Outcome.Drop>(f.handler.handle(envelope))
        assertEquals(MqttIncomingMessageHandler.DropReason.MISROUTED, drop.reason)
    }

    @Test
    fun malformed_bytes_dropped_without_throwing() {
        val f = fixture()
        val drop = assertIs<MqttIncomingMessageHandler.Outcome.Drop>(f.handler.handle(byteArrayOf(0, 1, 2)))
        assertEquals(MqttIncomingMessageHandler.DropReason.MALFORMED, drop.reason)
    }

    @Test
    fun broadcast_envelope_with_blank_receiver_passes_misroute_check() {
        val f = fixture()
        val envelope = f.encoder.encodeRequest("tid-7", receiverDeviceId = "")

        assertIs<MqttIncomingMessageHandler.Outcome.Deliver>(f.handler.handle(envelope))
    }

    @Test
    fun trusted_set_revocation_takes_effect_immediately() {
        val f = fixture()
        val first = f.encoder.encodeRequest("tid-8a")
        assertIs<MqttIncomingMessageHandler.Outcome.Deliver>(f.handler.handle(first))

        f.cache.remove(SENDER_DEVICE_ID)

        val second = f.encoder.encodeRequest("tid-8b")
        val drop = assertIs<MqttIncomingMessageHandler.Outcome.Drop>(f.handler.handle(second))
        assertEquals(MqttIncomingMessageHandler.DropReason.NOT_TRUSTED, drop.reason)
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private fun fixture(
        includeSenderInTrust: Boolean = true,
        verifierAcceptsAnything: Boolean = true,
        maxClockSkewMs: Long = MqttIncomingMessageHandler.DEFAULT_MAX_CLOCK_SKEW_MS
    ): Fixture {
        val publicKey = byteArrayOf(1, 2, 3, 4)
        val cache = InMemoryTrustedDeviceCache()
        if (includeSenderInTrust) {
            cache.upsert(
                TrustedDevice(
                    deviceId = SENDER_DEVICE_ID,
                    deviceName = "Alice's iPhone",
                    publicKey = publicKey,
                    enrolledAtMs = 1_000_000
                )
            )
        }
        val clock = MutableClock(2_000_000)
        val signer = EnvelopeSigner { canonical -> canonical + STUB_SIG_TAG }
        val verifier = EnvelopeVerifier { canonical, key, sig ->
            verifierAcceptsAnything &&
                key.contentEquals(publicKey) &&
                sig.contentEquals(canonical + STUB_SIG_TAG)
        }
        val encoder = TestEncoder(
            MqttOutgoingMessageEncoder(
                localDeviceId = SENDER_DEVICE_ID,
                signer = signer,
                clock = clock,
                nonces = SequentialNonceProvider()
            )
        )
        val handler = MqttIncomingMessageHandler(
            localDeviceId = LOCAL_DEVICE_ID,
            trustedDevices = cache,
            verifier = verifier,
            replayProtector = ReplayProtector(clock = clock),
            clock = clock,
            maxClockSkewMs = maxClockSkewMs
        )
        return Fixture(handler, encoder, cache, clock)
    }

    private class Fixture(
        val handler: MqttIncomingMessageHandler,
        val encoder: TestEncoder,
        val cache: InMemoryTrustedDeviceCache,
        val clock: MutableClock
    )

    /** Wraps the encoder so tests don't have to repeat the `MqttPayload`
     *  construction boilerplate. */
    private class TestEncoder(private val delegate: MqttOutgoingMessageEncoder) {
        fun encodeRequest(transferId: String, receiverDeviceId: String = LOCAL_DEVICE_ID): ByteArray =
            delegate.encode(
                MqttPayload.TransferRequest(transferId = transferId, files = emptyList(), totalBytes = 0),
                receiverDeviceId = receiverDeviceId
            )
    }

    private class MutableClock(initialMs: Long) : Clock {
        private var nowMs = initialMs
        override fun nowMs(): Long = nowMs
        fun advanceBy(deltaMs: Long) { nowMs += deltaMs }
    }

    private class SequentialNonceProvider : NonceProvider {
        private var counter = 0
        override fun next(byteCount: Int): ByteArray {
            counter += 1
            val out = ByteArray(byteCount)
            for (i in 0 until 4.coerceAtMost(byteCount)) {
                out[i] = ((counter ushr ((3 - i) * 8)) and 0xFF).toByte()
            }
            return out
        }
    }

    companion object {
        private const val SENDER_DEVICE_ID = "alice-device"
        private const val LOCAL_DEVICE_ID = "bob-device"
        private val STUB_SIG_TAG = byteArrayOf(0x73, 0x69, 0x67) // "sig"
    }
}
