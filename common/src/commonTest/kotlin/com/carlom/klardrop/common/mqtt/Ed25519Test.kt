package com.carlom.klardrop.common.mqtt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end round-trip and tamper-detection tests for the Ed25519 actuals.
 *
 * Lives in commonTest so the same suite runs on every target — the actuals
 * are platform-specific but their contract is identical (RFC 8032 raw
 * 32-byte seed / 32-byte public key / 64-byte signature).
 *
 * Skipped on iOS for now (the iOS actual is a stub; tracked under M3-iOS).
 */
class Ed25519Test {

    @Test
    fun sign_then_verify_round_trip() {
        val keys = generateEd25519KeyPair()
        val signer = ed25519Signer(keys.privateKeySeed)
        val verifier = ed25519Verifier()

        val payload = "klardrop transfer envelope".encodeToByteArray()
        val sig = signer.sign(payload)

        assertEquals(64, sig.size, "Ed25519 signature must be 64 bytes")
        assertTrue(verifier.verify(payload, keys.publicKey, sig))
    }

    @Test
    fun tampered_payload_fails_verification() {
        val keys = generateEd25519KeyPair()
        val sig = ed25519Signer(keys.privateKeySeed).sign(byteArrayOf(1, 2, 3))
        val verifier = ed25519Verifier()

        assertFalse(verifier.verify(byteArrayOf(1, 2, 4), keys.publicKey, sig))
    }

    @Test
    fun signature_with_different_key_fails_verification() {
        val keysA = generateEd25519KeyPair()
        val keysB = generateEd25519KeyPair()
        val payload = byteArrayOf(7, 7, 7)
        val sigByA = ed25519Signer(keysA.privateKeySeed).sign(payload)

        // Right payload, right signature, but wrong public key.
        assertFalse(ed25519Verifier().verify(payload, keysB.publicKey, sigByA))
    }

    @Test
    fun garbage_signature_does_not_throw() {
        val keys = generateEd25519KeyPair()
        val verifier = ed25519Verifier()

        assertFalse(verifier.verify("hi".encodeToByteArray(), keys.publicKey, ByteArray(64)))
        assertFalse(verifier.verify("hi".encodeToByteArray(), keys.publicKey, byteArrayOf(0)))
    }

    @Test
    fun keypair_round_trip_through_signed_envelope() {
        val keys = generateEd25519KeyPair()
        val signer = ed25519Signer(keys.privateKeySeed)
        val verifier = ed25519Verifier()
        val clock = object : Clock {
            override fun nowMs(): Long = 1_700_000_000_000L
        }

        val cache = InMemoryTrustedDeviceCache().apply {
            upsert(
                TrustedDevice(
                    deviceId = "alice",
                    deviceName = "Alice",
                    publicKey = keys.publicKey,
                    enrolledAtMs = 1_000_000
                )
            )
        }
        val encoder = MqttOutgoingMessageEncoder(
            localDeviceId = "alice",
            signer = signer,
            clock = clock
        )
        val handler = MqttIncomingMessageHandler(
            localDeviceId = "bob",
            trustedDevices = cache,
            verifier = verifier,
            replayProtector = ReplayProtector(clock = clock),
            clock = clock
        )

        val envelope = encoder.encode(
            MqttPayload.TransferRequest("tid", emptyList(), 0L),
            receiverDeviceId = "bob"
        )

        val outcome = handler.handle(envelope)
        assertTrue(outcome is MqttIncomingMessageHandler.Outcome.Deliver)
    }
}
