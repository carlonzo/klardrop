package com.carlom.klardrop.common.trust

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.EC
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
class TrustCrypto {

  companion object {
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

  // Key pair wrapper classes
  data class ECDHKeyPair(
    val publicKey: ECDHPublicKey,
    val privateKey: ECDHPrivateKey
  )

  data class ECDHPublicKey(
    val data: ByteArray
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is ECDHPublicKey) return false
      return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
  }

  data class ECDHPrivateKey(
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

  data class ECDSAKeyPair(
    val publicKey: ECDSAPublicKey,
    val privateKey: ECDSAPrivateKey
  )

  data class ECDSAPublicKey(
    val data: ByteArray
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is ECDSAPublicKey) return false
      return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
  }

  data class ECDSAPrivateKey(
    val data: ByteArray
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
    fun clear() {
      data.fill(0)
    }
  }

  /**
   * Generate ECDH keypair for key exchange.
   * Uses P-256 curve for optimal security and performance.
   * @return ECDH keypair
   */
  suspend fun generateECDHKeyPair(): ECDHKeyPair = withContext(Dispatchers.Default) {
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
  suspend fun computeECDHSecret(
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
  suspend fun generateECDSAKeyPair(): ECDSAKeyPair = withContext(Dispatchers.Default) {
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
  suspend fun signWithECDSA(
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
  suspend fun verifyECDSA(
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
  fun encodePublicKey(publicKey: ECDHPublicKey): ByteArray {
    return publicKey.data
  }

  /**
   * Encode ECDSA public key to bytes.
   * This is now a pass-through function as the wrapper already holds the raw bytes.
   * @param publicKey ECDSA public key
   * @return Public key bytes
   */
  fun encodeECDSAPublicKey(publicKey: ECDSAPublicKey): ByteArray {
    return publicKey.data
  }

  /**
   * Generate cryptographically secure random nonce.
   * @return 16 random bytes
   */
  fun generateNonce(): ByteArray {
    return CryptographyRandom.nextBytes(NONCE_SIZE)
  }

  /**
   * Combine multiple byte arrays for signing/verification.
   * Used to create data to sign from payload + timestamp + nonce.
   */
  fun combineForSigning(payload: ByteArray, timestamp: Long, nonce: ByteArray): ByteArray {
    val timestampBytes = timestamp.toByteArray()
    return payload + timestampBytes + nonce
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