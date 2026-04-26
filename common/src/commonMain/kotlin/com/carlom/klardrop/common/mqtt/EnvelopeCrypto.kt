package com.carlom.klardrop.common.mqtt

/**
 * Sign canonical envelope bytes with the local device's private key.
 *
 * The actual implementation is platform-specific — JVM/Android uses
 * `java.security` Ed25519 (Java 15+ / BouncyCastle), iOS uses CryptoKit's
 * Curve25519 — but the interface keeps commonMain testable with a stub.
 */
fun interface EnvelopeSigner {
    fun sign(canonicalBytes: ByteArray): ByteArray
}

/**
 * Verify a signature against a known public key. Implementations should be
 * total functions: invalid signatures return false rather than throw.
 */
fun interface EnvelopeVerifier {
    fun verify(canonicalBytes: ByteArray, publicKey: ByteArray, signature: ByteArray): Boolean
}

/**
 * Source of the clock used by the envelope pipeline. Production passes a
 * wall-clock; tests inject a controllable one.
 */
fun interface Clock {
    fun nowMs(): Long
}

/**
 * Source of envelope nonces. Default uses [kotlin.random.Random] (good enough
 * for replay rejection — we only need uniqueness within the replay window,
 * not cryptographic unpredictability of all bits). Tests can override.
 */
interface NonceProvider {
    fun next(byteCount: Int = DEFAULT_NONCE_BYTES): ByteArray

    companion object {
        const val DEFAULT_NONCE_BYTES: Int = 16
    }
}

class RandomNonceProvider : NonceProvider {
    override fun next(byteCount: Int): ByteArray {
        val bytes = ByteArray(byteCount)
        for (i in bytes.indices) {
            bytes[i] = kotlin.random.Random.nextInt(0, 256).toByte()
        }
        return bytes
    }
}

/**
 * Outcome of verifying a SignedEnvelope. Distinct cases let the caller log
 * and surface different failures (so a clock-skew issue is debuggable
 * separately from a forged signature).
 */
sealed class EnvelopeVerificationResult {
    data class Ok(val payload: ByteArray) : EnvelopeVerificationResult() {
        override fun equals(other: Any?): Boolean =
            other is Ok && payload.contentEquals(other.payload)

        override fun hashCode(): Int = payload.contentHashCode()
    }

    data object UnknownSender : EnvelopeVerificationResult()
    data object InvalidSignature : EnvelopeVerificationResult()
    data object ClockSkewExceeded : EnvelopeVerificationResult()
    data object ReplayedNonce : EnvelopeVerificationResult()
    data object UnsupportedAlgorithm : EnvelopeVerificationResult()
    data object MisroutedReceiver : EnvelopeVerificationResult()
}
