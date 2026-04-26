package com.carlom.klardrop.common.mqtt

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.EdECPublicKey
import java.security.spec.EdECPoint
import java.security.spec.EdECPrivateKeySpec
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec

/**
 * JVM / Android Ed25519 implementation, using the JDK's native provider
 * (Java 15+, Android API 33+). No third-party crypto dep.
 *
 * Encoding: raw 32-byte seed for the private key, raw 32-byte little-endian
 * public-key point for the public key — RFC 8032 standard wire format.
 * The JDK's `EdECPrivateKeySpec` / `EdECPublicKeySpec` accept these directly.
 */

private val ED25519_PARAMS = NamedParameterSpec.ED25519

actual fun generateEd25519KeyPair(): Ed25519KeyPair {
    val gen = KeyPairGenerator.getInstance("Ed25519")
    gen.initialize(ED25519_PARAMS, SecureRandom())
    val pair = gen.generateKeyPair()

    // Extract raw 32-byte seed from PKCS8 encoded form. The JDK doesn't
    // expose the seed via a stable API; PKCS8 layout for Ed25519 is fixed:
    //   30 2e 02 01 00 30 05 06 03 2b 65 70 04 22 04 20 <32 byte seed>
    // The last 32 bytes are the seed.
    val pkcs8 = pair.private.encoded
    require(pkcs8.size >= 32) { "unexpected PKCS8 encoding for Ed25519: ${pkcs8.size} bytes" }
    val seed = pkcs8.copyOfRange(pkcs8.size - 32, pkcs8.size)

    val publicRaw = encodePublicKeyRaw(pair.public as EdECPublicKey)
    return Ed25519KeyPair(privateKeySeed = seed, publicKey = publicRaw)
}

actual fun ed25519Signer(privateKeySeed: ByteArray): EnvelopeSigner {
    require(privateKeySeed.size == 32) {
        "Ed25519 private key seed must be 32 bytes, was ${privateKeySeed.size}"
    }
    val privateKey = KeyFactory.getInstance("Ed25519")
        .generatePrivate(EdECPrivateKeySpec(ED25519_PARAMS, privateKeySeed))
    return EnvelopeSigner { canonical ->
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(privateKey)
        sig.update(canonical)
        sig.sign()
    }
}

actual fun ed25519Verifier(): EnvelopeVerifier {
    val keyFactory = KeyFactory.getInstance("Ed25519")
    return EnvelopeVerifier { canonical, publicKey, signature ->
        if (publicKey.size != 32 || signature.size != 64) return@EnvelopeVerifier false
        runCatching {
            val point = decodePublicKeyPoint(publicKey)
            val pub = keyFactory.generatePublic(EdECPublicKeySpec(ED25519_PARAMS, point))
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(pub)
            sig.update(canonical)
            sig.verify(signature)
        }.getOrDefault(false)
    }
}

/**
 * Convert a Java `EdECPublicKey` to the 32-byte raw RFC 8032 form.
 *
 * RFC 8032 §5.1.2: the encoded point is the y-coordinate in little-endian,
 * with the high bit of the last byte set to the parity of x.
 */
private fun encodePublicKeyRaw(key: EdECPublicKey): ByteArray {
    val point = key.point
    val yBytes = point.y.toByteArray()
    // toByteArray() is big-endian and may be longer (sign byte) or shorter than 32.
    val little = ByteArray(32)
    for (i in yBytes.indices) {
        val srcIdx = yBytes.size - 1 - i
        if (srcIdx >= 0 && i < 32) little[i] = yBytes[srcIdx]
    }
    if (point.isXOdd) {
        little[31] = (little[31].toInt() or 0x80).toByte()
    }
    return little
}

/** Inverse of [encodePublicKeyRaw]. */
private fun decodePublicKeyPoint(rawLittleEndian: ByteArray): EdECPoint {
    val copy = rawLittleEndian.copyOf()
    val xOdd = (copy[31].toInt() and 0x80) != 0
    copy[31] = (copy[31].toInt() and 0x7F).toByte()
    // Reverse to big-endian for BigInteger.
    val be = ByteArray(copy.size)
    for (i in copy.indices) be[i] = copy[copy.size - 1 - i]
    val y = java.math.BigInteger(1, be)
    return EdECPoint(xOdd, y)
}

/**
 * Optional helper exposed for tests / key migration: derive the public key
 * from a raw 32-byte seed without a roundtrip through [generateEd25519KeyPair].
 *
 * Implementation note: the JDK doesn't directly expose a "seed → public key"
 * function, but generating the private key from the seed and asking for its
 * public counterpart works the same way internally as the spec describes.
 */
@Suppress("unused")
internal fun derivePublicKeyFromSeed(seed: ByteArray): ByteArray {
    require(seed.size == 32) { "Ed25519 seed must be 32 bytes, was ${seed.size}" }
    val priv = KeyFactory.getInstance("Ed25519")
        .generatePrivate(EdECPrivateKeySpec(ED25519_PARAMS, seed))
    // Round-trip through a Signature init/sign-verify isn't enough; instead
    // we re-use the JDK by constructing a KeyPair via reflection-free path:
    // generate, replace seed, regenerate is nontrivial. A pragmatic approach:
    // generate, and force-replace via PKCS8. Skipped here because we always
    // get the public key from the same generation that produced the seed.
    @Suppress("UNUSED_VARIABLE")
    val unused = priv          // referenced to keep the spec parsed and validated
    error("derivePublicKeyFromSeed: not implemented — keep public key alongside seed in SecureKeyStore")
}

@Suppress("unused")
private fun sha512(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(input)
