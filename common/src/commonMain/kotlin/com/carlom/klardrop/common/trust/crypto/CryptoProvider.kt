package com.carlom.klardrop.common.trust.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.random.CryptographyRandom
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.digest.SHA256
import dev.whyoleg.cryptography.algorithms.digest.SHA512

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

/**
 * Real implementation of CryptoProvider using cryptography-kotlin library.
 * This implementation provides secure cryptographic operations.
 */
class CryptoProviderImpl : CryptoProvider {
    
    private val provider = CryptographyProvider.Default
    
    override suspend fun generateECDSAKeypair(): ECDSAKeyPair {
        val ecdsa = provider.get(ECDSA)
        val keyPairGenerator = ecdsa.keyPairGenerator(EC.Curve.P256)
        val keyPair = keyPairGenerator.generateKey()
        
        return ECDSAKeyPair(
            publicKey = keyPair.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW),
            privateKey = keyPair.privateKey.encodeToByteArray(EC.PrivateKey.Format.RAW)
        )
    }
    
    override suspend fun generateECDHKeypair(): ECDHKeyPair {
        val ecdh = provider.get(ECDH)
        val keyPairGenerator = ecdh.keyPairGenerator(EC.Curve.P256)
        val keyPair = keyPairGenerator.generateKey()
        
        return ECDHKeyPair(
            publicKey = keyPair.publicKey.encodeToByteArray(EC.PublicKey.Format.RAW),
            privateKey = keyPair.privateKey.encodeToByteArray(EC.PrivateKey.Format.RAW)
        )
    }
    
    override suspend fun generateAESKey(): ByteArray {
        val aesGcm = provider.get(AES.GCM)
        val keyGenerator = aesGcm.keyGenerator(keySize = AES.Key.Size.B256)
        val key = keyGenerator.generateKey()
        return key.encodeToByteArray(AES.Key.Format.RAW)
    }
    
    override suspend fun signECDSA(data: ByteArray, privateKey: ByteArray): ByteArray {
        val ecdsa = provider.get(ECDSA)
        val ecdsaPrivateKey = ecdsa.privateKeyDecoder(EC.Curve.P256)
            .decodeFromByteArray(EC.PrivateKey.Format.RAW, privateKey)
        
        return ecdsaPrivateKey.signatureGenerator(
            digest = SHA256,
            format = ECDSA.SignatureFormat.DER
        ).generateSignature(data)
    }
    
    override suspend fun verifyECDSA(
        data: ByteArray,
        signature: ByteArray,
        publicKey: ByteArray
    ): Boolean {
        return try {
            val ecdsa = provider.get(ECDSA)
            val ecdsaPublicKey = ecdsa.publicKeyDecoder(EC.Curve.P256)
                .decodeFromByteArray(EC.PublicKey.Format.RAW, publicKey)
            
            ecdsaPublicKey.signatureVerifier(
                digest = SHA256,
                format = ECDSA.SignatureFormat.DER
            ).tryVerifySignature(data, signature)
        } catch (e: Exception) {
            false
        }
    }
    
    override suspend fun computeECDHSecret(
        privateKey: ByteArray,
        publicKey: ByteArray
    ): ByteArray {
        val ecdh = provider.get(ECDH)
        val ecdhPrivateKey = ecdh.privateKeyDecoder(EC.Curve.P256)
            .decodeFromByteArray(EC.PrivateKey.Format.RAW, privateKey)
        val ecdhPublicKey = ecdh.publicKeyDecoder(EC.Curve.P256)
            .decodeFromByteArray(EC.PublicKey.Format.RAW, publicKey)
        
        return ecdhPrivateKey.sharedSecretDerivation().deriveSharedSecret(ecdhPublicKey)
    }
    
    override suspend fun encryptAESGCM(data: ByteArray, key: ByteArray): EncryptedPayload {
        val aesGcm = provider.get(AES.GCM)
        val aesKey = aesGcm.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key)
        
        val cipher = aesKey.cipher()
        val nonce = generateNonce()
        
        // AES-GCM with authenticated encryption returns ciphertext + tag combined
        val encryptedData = cipher.encrypt(data, nonce)
        
        // Split the result - last 16 bytes are the tag, rest is ciphertext
        val ciphertext = encryptedData.dropLast(16).toByteArray()
        val tag = encryptedData.takeLast(16).toByteArray()
        
        return EncryptedPayload(
            ciphertext = ciphertext,
            nonce = nonce,
            tag = tag
        )
    }
    
    override suspend fun decryptAESGCM(payload: EncryptedPayload, key: ByteArray): ByteArray {
        val aesGcm = provider.get(AES.GCM)
        val aesKey = aesGcm.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key)
        
        val cipher = aesKey.cipher()
        
        // Combine ciphertext and tag for decryption
        val encryptedData = payload.ciphertext + payload.tag
        
        return cipher.decrypt(encryptedData, payload.nonce)
    }
    
    override suspend fun deriveKey(
        secret: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int
    ): ByteArray {
        val hkdf = provider.get(HKDF)
        val secretDerivation = hkdf.secretDerivation(SHA256)
        
        return secretDerivation.deriveSecret(
            sharedSecret = secret,
            keyLength = length,
            salt = salt,
            info = info
        )
    }
    
    override fun generateNonce(): ByteArray = CryptographyRandom.nextBytes(12)
    
    override fun generateRandomBytes(length: Int): ByteArray {
        return CryptographyRandom.nextBytes(length)
    }
    
    override fun hash(data: ByteArray): ByteArray {
        val sha256 = provider.get(SHA256)
        return sha256.hasher().hash(data)
    }
}