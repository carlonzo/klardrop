package com.carlom.klardrop.common.trust.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.digest.SHA256
import dev.whyoleg.cryptography.algorithms.digest.SHA512
import dev.whyoleg.cryptography.BinarySize
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.coroutines.runBlocking

interface CryptoProvider {
    // Key generation
    suspend fun generateECDSAKeypair(): ECDSAKeyPair
    suspend fun generateECDHKeypair(): ECDHKeyPair
    suspend fun generateAESKey(): ByteArray
    
    // ECDSA operations
    suspend fun signECDSA(data: ByteArray, privateKey: ByteArray): ByteArray
    suspend fun verifyECDSA(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
    
    // ECDH operations
    suspend fun computeECDHSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray
    
    // AES-GCM operations
    suspend fun encryptAESGCM(data: ByteArray, key: ByteArray): EncryptedPayload
    suspend fun decryptAESGCM(payload: EncryptedPayload, key: ByteArray): ByteArray
    
    // Key derivation
    suspend fun deriveKey(
        secret: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = 32
    ): ByteArray
    
    // Utilities
    fun generateNonce(): ByteArray
    fun generateRandomBytes(length: Int): ByteArray
    fun hash(data: ByteArray): ByteArray
}

data class ECDSAKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ECDSAKeyPair
        return publicKey.contentEquals(other.publicKey) && 
               privateKey.contentEquals(other.privateKey)
    }
    
    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + privateKey.contentHashCode()
        return result
    }
}

data class ECDHKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ECDHKeyPair
        return publicKey.contentEquals(other.publicKey) && 
               privateKey.contentEquals(other.privateKey)
    }
    
    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + privateKey.contentHashCode()
        return result
    }
}

data class EncryptedPayload(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val tag: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as EncryptedPayload
        return ciphertext.contentEquals(other.ciphertext) && 
               nonce.contentEquals(other.nonce) &&
               tag.contentEquals(other.tag)
    }
    
    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + tag.contentHashCode()
        return result
    }
}

class CryptoProviderImpl : CryptoProvider {
    private val provider = CryptographyProvider.Default
    
    // Use P-256 (secp256r1) curve for compatibility
    private val ecdsaAlgorithm = provider.get(ECDSA)
    private val ecdhAlgorithm = provider.get(ECDH)
    private val aesGcmAlgorithm = provider.get(AES.GCM)
    private val hkdfAlgorithm = provider.get(HKDF)
    private val sha256 = provider.get(SHA256)
    
    override suspend fun generateECDSAKeypair(): ECDSAKeyPair {
        val keyPair = ecdsaAlgorithm.keyPairGenerator(EC.Curve.P256).generateKey()
        val publicKey = keyPair.publicKey.encodeToByteArray(EC.PublicKey.Format.DER)
        val privateKey = keyPair.privateKey.encodeToByteArray(EC.PrivateKey.Format.DER)
        
        return ECDSAKeyPair(
            publicKey = publicKey,
            privateKey = privateKey
        )
    }
    
    override suspend fun generateECDHKeypair(): ECDHKeyPair {
        val keyPair = ecdhAlgorithm.keyPairGenerator(EC.Curve.P256).generateKey()
        val publicKey = keyPair.publicKey.encodeToByteArray(EC.PublicKey.Format.DER)
        val privateKey = keyPair.privateKey.encodeToByteArray(EC.PrivateKey.Format.DER)
        
        return ECDHKeyPair(
            publicKey = publicKey,
            privateKey = privateKey
        )
    }
    
    override suspend fun generateAESKey(): ByteArray {
        val key = aesGcmAlgorithm.keyGenerator(keySize = AES.Key.Size.B256).generateKey()
        return key.encodeToByteArray(AES.Key.Format.RAW)
    }
    
    override suspend fun signECDSA(data: ByteArray, privateKey: ByteArray): ByteArray {
        require(data.isNotEmpty()) { "Data cannot be empty" }
        require(privateKey.isNotEmpty()) { "Private key cannot be empty" }
        
        val key = ecdsaAlgorithm.privateKeyDecoder(EC.Curve.P256)
            .decodeFromByteArray(EC.PrivateKey.Format.DER, privateKey)
        val signature = key.signatureGenerator(digest = SHA256, format = ECDSA.SignatureFormat.DER)
            .generateSignature(data)
        return signature
    }
    
    override suspend fun verifyECDSA(
        data: ByteArray,
        signature: ByteArray,
        publicKey: ByteArray
    ): Boolean {
        require(data.isNotEmpty()) { "Data cannot be empty" }
        require(signature.isNotEmpty()) { "Signature cannot be empty" }
        require(publicKey.isNotEmpty()) { "Public key cannot be empty" }
        
        return try {
            val key = ecdsaAlgorithm.publicKeyDecoder(EC.Curve.P256)
                .decodeFromByteArray(EC.PublicKey.Format.DER, publicKey)
            val verifier = key.signatureVerifier(digest = SHA256, format = ECDSA.SignatureFormat.DER)
            
            // Use tryVerifySignature which returns Boolean instead of throwing
            verifier.tryVerifySignature(data, signature)
        } catch (e: IllegalArgumentException) {
            // Malformed key or signature data
            false
        } catch (e: IllegalStateException) {
            // Key decoding or signature verification failed
            false
        }
    }
    
    override suspend fun computeECDHSecret(
        privateKey: ByteArray,
        publicKey: ByteArray
    ): ByteArray {
        require(privateKey.isNotEmpty()) { "Private key cannot be empty" }
        require(publicKey.isNotEmpty()) { "Public key cannot be empty" }
        
        val privKey = ecdhAlgorithm.privateKeyDecoder(EC.Curve.P256)
            .decodeFromByteArray(EC.PrivateKey.Format.DER, privateKey)
        val pubKey = ecdhAlgorithm.publicKeyDecoder(EC.Curve.P256)
            .decodeFromByteArray(EC.PublicKey.Format.DER, publicKey)
        
        return privKey.sharedSecretGenerator().generateSharedSecret(pubKey).toByteArray()
    }
    
    override suspend fun encryptAESGCM(data: ByteArray, key: ByteArray): EncryptedPayload {
        require(data.isNotEmpty()) { "Data cannot be empty" }
        require(key.isNotEmpty()) { "Key cannot be empty" }
        
        val aesKey = aesGcmAlgorithm.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key)
        val cipher = aesKey.cipher()
        
        val nonce = generateNonce()
        val ciphertext = cipher.encrypt(data)
        
        // In AES-GCM, the authentication tag is included in the ciphertext
        // We need to separate it manually (last 16 bytes for GCM)
        val tagLength = 16
        val actualCiphertext = ciphertext.dropLast(tagLength).toByteArray()
        val tag = ciphertext.takeLast(tagLength).toByteArray()
        
        return EncryptedPayload(
            ciphertext = actualCiphertext,
            nonce = nonce,
            tag = tag
        )
    }
    
    override suspend fun decryptAESGCM(payload: EncryptedPayload, key: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "Key cannot be empty" }
        require(payload.ciphertext.isNotEmpty()) { "Ciphertext cannot be empty" }
        require(payload.nonce.isNotEmpty()) { "Nonce cannot be empty" }
        require(payload.tag.isNotEmpty()) { "Authentication tag cannot be empty" }
        
        val aesKey = aesGcmAlgorithm.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key)
        val cipher = aesKey.cipher()
        
        // Combine ciphertext and tag for GCM decryption
        val fullCiphertext = payload.ciphertext + payload.tag
        
        return cipher.decrypt(fullCiphertext)
    }
    
    override suspend fun deriveKey(
        secret: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int
    ): ByteArray {
        require(secret.isNotEmpty()) { "Secret cannot be empty" }
        require(salt.isNotEmpty()) { "Salt cannot be empty" }
        require(info.isNotEmpty()) { "Info cannot be empty" }
        require(length > 0) { "Length must be greater than 0" }
        
        val outputSize = when (length) {
            16 -> AES.Key.Size.B128
            24 -> AES.Key.Size.B192  
            32 -> AES.Key.Size.B256
            else -> throw IllegalArgumentException("Unsupported key length: $length. Supported: 16, 24, 32 bytes")
        }
        
        // For now, use a simple fallback implementation using HMAC
        // This is a simplified HKDF-like implementation
        val hmac = provider.get(dev.whyoleg.cryptography.algorithms.HMAC)
        val keyGen = hmac.keyGenerator(SHA256)
        val saltKey = hmac.keyDecoder(SHA256).decodeFromByteArray(
            dev.whyoleg.cryptography.algorithms.HMAC.Key.Format.RAW, 
            if (salt.isNotEmpty()) salt else ByteArray(32) // Default salt if empty
        )
        
        // Extract phase - PRK = HMAC(salt, IKM)
        val prk = saltKey.signatureGenerator().generateSignature(secret)
        
        // Expand phase - simplified version
        val prkKey = hmac.keyDecoder(SHA256).decodeFromByteArray(
            dev.whyoleg.cryptography.algorithms.HMAC.Key.Format.RAW,
            prk
        )
        val expandInput = info + byteArrayOf(0x01) // Simple expand with counter
        val expanded = prkKey.signatureGenerator().generateSignature(expandInput)
        
        return expanded.take(length).toByteArray()
    }
    
    override fun generateNonce(): ByteArray = generateRandomBytes(12) // 96-bit nonce for GCM
    
    override fun generateRandomBytes(length: Int): ByteArray {
        return CryptographyRandom.nextBytes(length)
    }
    
    override fun hash(data: ByteArray): ByteArray {
        // For non-suspend hash, use a synchronous wrapper or blocking call
        return runBlocking { sha256.hasher().hash(data) }
    }
}