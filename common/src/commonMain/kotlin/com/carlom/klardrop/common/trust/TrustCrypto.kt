package com.carlom.klardrop.common.trust

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cryptographic operations for the trust system.
 * Provides ECDH key exchange and ECDSA signature operations for secure device pairing.
 *
 * This implementation uses the cryptography-kotlin library for all cryptographic
 * operations, ensuring secure and multiplatform-compatible cryptography.
 */
public class TrustCrypto public constructor() {

  internal companion object {
    private const val NONCE_SIZE = 16
    private const val ECDH_PUBLIC_KEY_SIZE = 65  // P-256 uncompressed public key
    private const val ECDH_PRIVATE_KEY_SIZE = 32  // P-256 private key
    private const val ECDSA_PUBLIC_KEY_SIZE = 65  // P-256 uncompressed public key
    private const val ECDSA_PRIVATE_KEY_SIZE = 32  // P-256 private key
    private const val ECDSA_SIGNATURE_SIZE = 64  // P-256 ECDSA signature (r,s)
    private const val SHARED_SECRET_SIZE = 32  // P-256 shared secret
  }

  // Algorithm instances initialized once for efficiency
  private val provider = CryptographyProvider.Default
  private val ecdsa = provider.get(ECDSA)
  private val ecdh = provider.get(ECDH)
  private val sha256 = provider.get(SHA256)
  private val hmac = provider.get(HMAC)
  private val hkdf = provider.get(HKDF)

  // Key pair wrapper classes
  internal data class ECDHKeyPair(
    val publicKey: ECDHPublicKey,
    val privateKey: ECDHPrivateKey
  )

  internal data class ECDHPublicKey(
    val data: ByteArray
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is ECDHPublicKey) return false
      return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
  }

  internal data class ECDHPrivateKey(
    val data: ByteArray
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is ECDHPrivateKey) return false
      return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()

    override fun toString(): String = "ECDHPrivateKey(data=[REDACTED])"

    /**
     * Securely clear the private key data from memory.
     * Call this when the key is no longer needed.
     */
    fun clear() {
      data.fill(0)
    }
  }

  public data class ECDSAKeyPair(
    public val publicKey: ECDSAPublicKey,
    public val privateKey: ECDSAPrivateKey
  )

  public data class ECDSAPublicKey(
    public val data: ByteArray
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is ECDSAPublicKey) return false
      return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
  }

  public data class ECDSAPrivateKey(
    public val data: ByteArray
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is ECDSAPrivateKey) return false
      return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()

    override fun toString(): String = "ECDSAPrivateKey(data=[REDACTED])"

    /**
     * Securely clear the private key data from memory.
     * Call this when the key is no longer needed.
     */
    public fun clear() {
      data.fill(0)
    }
  }

  /**
   * Generate ECDH keypair for key exchange.
   * Uses P-256 curve for optimal security and performance.
   * @return ECDH keypair
   */
  internal suspend fun generateECDHKeyPair(): ECDHKeyPair = withContext(Dispatchers.Default) {
    try {
      val keyPairGenerator = ecdh.keyPairGenerator(EC.Curve.P256)
      val keyPair = keyPairGenerator.generateKey()

      val publicKeyData = keyPair.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW)
      val privateKeyData = keyPair.privateKey.encodeToByteArray(EC.PrivateKey.Format.RAW)

      ECDHKeyPair(
        publicKey = ECDHPublicKey(publicKeyData),
        privateKey = ECDHPrivateKey(privateKeyData)
      )
    } catch (e: Exception) {
      throw RuntimeException("Failed to generate ECDH key pair", e)
    }
  }

  /**
   * Compute shared secret from ECDH key exchange.
   * @param privateKey Our private key
   * @param peerPublicKeyBytes Peer's public key as bytes
   * @return Shared secret bytes
   */
  internal suspend fun computeECDHSecret(
    privateKey: ECDHPrivateKey,
    peerPublicKeyBytes: ByteArray
  ): ByteArray = withContext(Dispatchers.Default) {
    try {
      // Validate input size
      if (peerPublicKeyBytes.size != ECDH_PUBLIC_KEY_SIZE) {
        throw IllegalArgumentException("Invalid peer public key size: ${peerPublicKeyBytes.size}")
      }

      val privateKeyDecoder = ecdh.privateKeyDecoder(EC.Curve.P256)
      val ourPrivateKey = privateKeyDecoder.decodeFromByteArray(EC.PrivateKey.Format.RAW, privateKey.data)

      val publicKeyDecoder = ecdh.publicKeyDecoder(EC.Curve.P256)
      val peerPublicKey = publicKeyDecoder.decodeFromByteArray(EC.PublicKey.Format.RAW, peerPublicKeyBytes)

      val sharedSecretGenerator = ourPrivateKey.sharedSecretGenerator()
      sharedSecretGenerator.generateSharedSecret(peerPublicKey).toByteArray()
    } catch (e: Exception) {
      throw RuntimeException("Failed to compute ECDH shared secret", e)
    }
  }

  /**
   * Generate ECDSA keypair for digital signatures.
   * Uses P-256 curve with SHA256 for signing.
   * @return ECDSA keypair
   */
  public suspend fun generateECDSAKeyPair(): ECDSAKeyPair = withContext(Dispatchers.Default) {
    try {
      val keyPairGenerator = ecdsa.keyPairGenerator(EC.Curve.P256)
      val keyPair = keyPairGenerator.generateKey()

      val publicKeyData = keyPair.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW)
      val privateKeyData = keyPair.privateKey.encodeToByteArray(EC.PrivateKey.Format.RAW)

      ECDSAKeyPair(
        publicKey = ECDSAPublicKey(publicKeyData),
        privateKey = ECDSAPrivateKey(privateKeyData)
      )
    } catch (e: Exception) {
      throw RuntimeException("Failed to generate ECDSA key pair", e)
    }
  }

  /**
   * Sign data with ECDSA private key.
   * @param privateKey ECDSA private key
   * @param data Data to sign
   * @return Signature bytes in RAW format (r || s)
   */
  public suspend fun signWithECDSA(
    privateKey: ECDSAPrivateKey,
    data: ByteArray
  ): ByteArray = withContext(Dispatchers.Default) {
    try {
      val privateKeyDecoder = ecdsa.privateKeyDecoder(EC.Curve.P256)
      val signingKey = privateKeyDecoder.decodeFromByteArray(EC.PrivateKey.Format.RAW, privateKey.data)

      val signatureGenerator = signingKey.signatureGenerator(digest = SHA256, format = ECDSA.SignatureFormat.RAW)
      signatureGenerator.generateSignature(data)
    } catch (e: Exception) {
      throw RuntimeException("Failed to sign data with ECDSA", e)
    }
  }

  /**
   * Verify ECDSA signature.
   * @param publicKeyBytes Public key as bytes
   * @param data Original data that was signed
   * @param signature Signature to verify
   * @return true if signature is valid
   */
  public suspend fun verifyECDSA(
    publicKeyBytes: ByteArray,
    data: ByteArray,
    signature: ByteArray
  ): Boolean = withContext(Dispatchers.Default) {
    try {
      // Fast-fail for obviously incorrect inputs
      if (publicKeyBytes.size != ECDSA_PUBLIC_KEY_SIZE) return@withContext false
      if (signature.size != ECDSA_SIGNATURE_SIZE) return@withContext false

      val publicKeyDecoder = ecdsa.publicKeyDecoder(EC.Curve.P256)
      val verificationKey = publicKeyDecoder.decodeFromByteArray(EC.PublicKey.Format.RAW, publicKeyBytes)

      val signatureVerifier = verificationKey.signatureVerifier(digest = SHA256, format = ECDSA.SignatureFormat.RAW)
      signatureVerifier.tryVerifySignature(data, signature)
    } catch (_: Exception) {
      // Log generic error for debugging without exposing internal details
      println("ECDSA signature verification failed")
      false
    }
  }

  /**
   * Encode public key to bytes.
   * This is now a pass-through function as the wrapper already holds the raw bytes.
   * @param publicKey ECDH public key
   * @return Public key bytes
   */
  internal fun encodePublicKey(publicKey: ECDHPublicKey): ByteArray {
    return publicKey.data
  }

  /**
   * Encode ECDSA public key to bytes.
   * This is now a pass-through function as the wrapper already holds the raw bytes.
   * @param publicKey ECDSA public key
   * @return Public key bytes
   */
  internal fun encodeECDSAPublicKey(publicKey: ECDSAPublicKey): ByteArray {
    return publicKey.data
  }

  /**
   * Generate cryptographically secure random nonce.
   * @return 16 random bytes
   */
  internal fun generateNonce(): ByteArray {
    return CryptographyRandom.nextBytes(NONCE_SIZE)
  }

  /**
   * Combine multiple byte arrays for signing/verification.
   * Used to create data to sign from payload + timestamp + nonce.
   */
  internal fun combineForSigning(payload: ByteArray, timestamp: Long, nonce: ByteArray): ByteArray {
    val timestampBytes = timestamp.toByteArray()
    return payload + timestampBytes + nonce
  }

  /**
   * SHA-256 of [data]. Used by the file-transfer path to bind a signed header to the content
   * bytes that follow without paying ECDSA cost on every chunk: the header carries
   * `SHA256(file)` and is signed once; chunks flow unsigned; the receiver hashes as it
   * accumulates and rejects on mismatch at completion time.
   */
  internal suspend fun sha256(data: ByteArray): ByteArray {
    // No explicit Dispatchers.Default switch here: callers in the file path are already
    // running on coroutines.ioDispatcher, and offloading via withContext breaks runTest
    // ordering for tests that observe interleaved progress emissions across the call.
    // SHA-256 over our typical file sizes (<100MB) is fast enough that this isn't a
    // throughput concern.
    val hasher = sha256.hasher()
    return hasher.hash(data)
  }

  /**
   * Stateful SHA-256 accumulator. Caller feeds chunks via [Sha256Accumulator.update] and reads
   * the final digest via [Sha256Accumulator.digest]. Used by the receive pipeline so we don't
   * have to re-read the file from disk after assembling it.
   */
  internal fun sha256Accumulator(): Sha256Accumulator = Sha256AccumulatorImpl()

  internal interface Sha256Accumulator {
    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size)
    suspend fun digest(): ByteArray
  }

  /**
   * Derive a 32-byte key from [sharedSecret] (typically the ECDH-derived secret) using
   * HKDF-SHA256 with [salt] and [info]. The same (sharedSecret, salt, info) tuple always
   * produces the same key, so both peers can independently derive the same MAC key.
   *
   * Use a [info] string that's specific to the purpose (e.g. "klardrop-chunk-mac-v1") so
   * keys derived for different uses are mutually orthogonal — that way compromising one
   * doesn't leak the others, and you can rotate per use case.
   */
  internal suspend fun hkdfSha256(
    sharedSecret: ByteArray,
    info: ByteArray,
    salt: ByteArray = ByteArray(0),
    outputSize: Int = 32,
  ): ByteArray {
    val derivation = hkdf.secretDerivation(SHA256, outputSize.bytes, salt, info)
    return derivation.deriveSecretToByteArray(sharedSecret)
  }

  /**
   * Compute HMAC-SHA256 of [data] under [key]. Constant time for the inputs we use.
   * Pair with [verifyHmacSha256] for symmetric-key authenticity.
   */
  internal suspend fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val hmacKey = hmac.keyDecoder(SHA256).decodeFromByteArray(HMAC.Key.Format.RAW, key)
    return hmacKey.signatureGenerator().generateSignature(data)
  }

  /** Verify an HMAC-SHA256 [tag] over [data] under [key]. */
  internal suspend fun verifyHmacSha256(key: ByteArray, data: ByteArray, tag: ByteArray): Boolean {
    val hmacKey = hmac.keyDecoder(SHA256).decodeFromByteArray(HMAC.Key.Format.RAW, key)
    return hmacKey.signatureVerifier().tryVerifySignature(data, tag)
  }

  private inner class Sha256AccumulatorImpl : Sha256Accumulator {
    // Incremental: cryptography-kotlin's HashFunction keeps only SHA-256 state (a few dozen
    // bytes), never the input. The previous version buffered every chunk in an ArrayList and
    // hashed the concatenation at digest() time, which made hashing O(file size) in MEMORY —
    // a multi-GB send OOM'd the sender at ART's 256 MB heap limit before a byte left the device,
    // and the receive pipeline had the same ceiling on the way in.
    private val function = sha256.hasher().createHashFunction()

    override fun update(data: ByteArray, offset: Int, length: Int) {
      function.update(data, offset, offset + length)
    }

    override suspend fun digest(): ByteArray = function.use { it.hashToByteArray() }
  }


  private fun Long.toByteArray(): ByteArray {
    return byteArrayOf(
      (this shr 56).toByte(),
      (this shr 48).toByte(),
      (this shr 40).toByte(),
      (this shr 32).toByte(),
      (this shr 24).toByte(),
      (this shr 16).toByte(),
      (this shr 8).toByte(),
      this.toByte()
    )
  }
}