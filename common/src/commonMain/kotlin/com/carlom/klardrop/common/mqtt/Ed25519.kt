package com.carlom.klardrop.common.mqtt

/**
 * Platform-provided Ed25519 implementation of [EnvelopeSigner] /
 * [EnvelopeVerifier].
 *
 * Why Ed25519:
 *  - 32-byte public keys → fit comfortably in `TrustEvent.publicKey` and
 *    a `TrustedDevice` cache row.
 *  - Deterministic — no platform RNG hazards inside `sign`.
 *  - Native on Java 15+ and Android API 33+, no third-party dep.
 *
 * Public API is plain `fun`s rather than `expect class` so the SPI surface
 * is minimal and `EnvelopeSigner`/`EnvelopeVerifier` can stay platform-pure
 * as functional interfaces.
 *
 * Key shape (cross-platform):
 *  - **Public key** — raw 32-byte little-endian Ed25519 point (RFC 8032).
 *  - **Private key** — raw 32-byte seed (RFC 8032). On JVM/Android we
 *    derive the keypair via `EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed)`.
 *
 * Equivalent shape on iOS CryptoKit's `Curve25519.Signing.PrivateKey.rawRepresentation`.
 */

/** Returns a freshly generated Ed25519 keypair, raw (32-byte) encoded. */
expect fun generateEd25519KeyPair(): Ed25519KeyPair

/** Builds a signer from a raw 32-byte Ed25519 seed. Throws on wrong length. */
expect fun ed25519Signer(privateKeySeed: ByteArray): EnvelopeSigner

/**
 * A verifier that takes the public key per call. Stateless — share one
 * instance across the app.
 */
expect fun ed25519Verifier(): EnvelopeVerifier

data class Ed25519KeyPair(
    /** 32 bytes, raw seed. Treat as secret. */
    val privateKeySeed: ByteArray,
    /** 32 bytes, raw point. Safe to share. */
    val publicKey: ByteArray
) {
    init {
        require(privateKeySeed.size == 32) { "Ed25519 seed must be 32 bytes, was ${privateKeySeed.size}" }
        require(publicKey.size == 32) { "Ed25519 public key must be 32 bytes, was ${publicKey.size}" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ed25519KeyPair) return false
        return privateKeySeed.contentEquals(other.privateKeySeed) &&
            publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int =
        31 * privateKeySeed.contentHashCode() + publicKey.contentHashCode()
}
