package com.carlom.klardrop.common.mqtt

/**
 * iOS Ed25519 implementation — currently a stub.
 *
 * Real implementation will use Apple CryptoKit via cinterop:
 *   - `Curve25519.Signing.PrivateKey()` for keypair generation.
 *   - `.signature(for: data)` to produce 64-byte Ed25519 signatures.
 *   - `Curve25519.Signing.PublicKey(rawRepresentation: data)` for verification.
 *
 * Tracked under M3-iOS in `docs/mqtt-rollout-progress.md`.
 */

actual fun generateEd25519KeyPair(): Ed25519KeyPair =
    error("Ed25519 not yet implemented on iOS — see M3-iOS in docs/mqtt-rollout-progress.md")

actual fun ed25519Signer(privateKeySeed: ByteArray): EnvelopeSigner =
    error("Ed25519 not yet implemented on iOS — see M3-iOS in docs/mqtt-rollout-progress.md")

actual fun ed25519Verifier(): EnvelopeVerifier =
    error("Ed25519 not yet implemented on iOS — see M3-iOS in docs/mqtt-rollout-progress.md")
