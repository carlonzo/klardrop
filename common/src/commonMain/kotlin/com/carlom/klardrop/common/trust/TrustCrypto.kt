package com.carlom.klardrop.common.trust

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * Cryptographic operations for the trust system.
 * Provides ECDH key exchange and ECDSA signature operations for secure device pairing.
 * 
 * This is a placeholder implementation that will be completed once we can verify the correct API.
 */
class TrustCrypto {
    
    companion object {
        private const val NONCE_SIZE = 16
    }
    
    private val provider = CryptographyProvider.Default
    
    // TODO: Implement ECDH and ECDSA operations once we verify correct imports
    
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