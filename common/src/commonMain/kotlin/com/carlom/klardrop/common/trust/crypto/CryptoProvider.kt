package com.carlom.klardrop.common.trust.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.digest.SHA256
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString

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
    private val ecdsaAlgorithm = provider.get(ECDSA).EC.P256
    private val ecdhAlgorithm = provider.get(ECDH).EC.P256
    private val aesAlgorithm = provider.get(AES).GCM
    private val hkdfAlgorithm = provider.get(HKDF)
    private val sha256 = provider.get(SHA256)
    
    override suspend fun generateECDSAKeypair(): ECDSAKeyPair {
        val keyPair = ecdsaAlgorithm.keyPairGenerator().generateKey()
        val publicKey = keyPair.publicKey.encodeTo(ECDSA.PublicKey.Format.DER)
        val privateKey = keyPair.privateKey.encodeTo(ECDSA.PrivateKey.Format.DER)
        
        return ECDSAKeyPair(
            publicKey = publicKey,
            privateKey = privateKey
        )
    }
    
    override suspend fun generateECDHKeypair(): ECDHKeyPair {
        val keyPair = ecdhAlgorithm.keyPairGenerator().generateKey()
        val publicKey = keyPair.publicKey.encodeTo(ECDH.PublicKey.Format.DER)
        val privateKey = keyPair.privateKey.encodeTo(ECDH.PrivateKey.Format.DER)
        
        return ECDHKeyPair(
            publicKey = publicKey,
            privateKey = privateKey
        )
    }
    
    override suspend fun generateAESKey(): ByteArray {
        val key = aesAlgorithm.keyGenerator(keySize = AES.Key.Size.B256).generateKey()
        return key.encodeTo(AES.Key.Format.RAW)
    }
    
    override suspend fun signECDSA(data: ByteArray, privateKey: ByteArray): ByteArray {
        val key = ecdsaAlgorithm.privateKeyDecoder(ECDSA.PrivateKey.Format.DER)
            .decodeFrom(privateKey)
        val signature = key.signatureGenerator(digest = ECDSA.SignatureAlgorithm.SHA256)
            .generateSignature(data)
        return signature.encodeTo(ECDSA.SignatureFormat.DER)
    }
    
    override suspend fun verifyECDSA(
        data: ByteArray,
        signature: ByteArray,
        publicKey: ByteArray
    ): Boolean {
        return try {
            val key = ecdsaAlgorithm.publicKeyDecoder(ECDSA.PublicKey.Format.DER)
                .decodeFrom(publicKey)
            val sig = ecdsaAlgorithm.signatureDecoder(ECDSA.SignatureFormat.DER)
                .decodeFrom(signature)
            key.signatureVerifier(digest = ECDSA.SignatureAlgorithm.SHA256)
                .verifySignature(data, sig)
        } catch (e: Exception) {
            false
        }
    }
    
    override suspend fun computeECDHSecret(
        privateKey: ByteArray,
        publicKey: ByteArray
    ): ByteArray {
        val privKey = ecdhAlgorithm.privateKeyDecoder(ECDH.PrivateKey.Format.DER)
            .decodeFrom(privateKey)
        val pubKey = ecdhAlgorithm.publicKeyDecoder(ECDH.PublicKey.Format.DER)
            .decodeFrom(publicKey)
        
        return privKey.sharedSecretGenerator().generateSharedSecret(pubKey)
    }
    
    override suspend fun encryptAESGCM(data: ByteArray, key: ByteArray): EncryptedPayload {
        val aesKey = aesAlgorithm.keyDecoder(AES.Key.Format.RAW).decodeFrom(key)
        val cipher = aesKey.cipher()
        
        val nonce = generateNonce()
        val encrypted = cipher.encrypt(nonce, data)
        
        return EncryptedPayload(
            ciphertext = encrypted.ciphertext,
            nonce = nonce,
            tag = encrypted.authenticationTag ?: ByteArray(0)
        )
    }
    
    override suspend fun decryptAESGCM(payload: EncryptedPayload, key: ByteArray): ByteArray {
        val aesKey = aesAlgorithm.keyDecoder(AES.Key.Format.RAW).decodeFrom(key)
        val cipher = aesKey.cipher()
        
        return cipher.decrypt(
            nonce = payload.nonce,
            ciphertext = payload.ciphertext,
            authenticationTag = payload.tag.takeIf { it.isNotEmpty() }
        )
    }
    
    override suspend fun deriveKey(
        secret: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int
    ): ByteArray {
        val derivation = hkdfAlgorithm.derivation(
            digest = HKDF.Digest.SHA256,
            outputSize = AES.Key.Size.B256
        )
        
        return derivation.deriveKey(
            ikm = secret,
            salt = salt,
            info = info
        ).encodeTo(HKDF.Key.Format.RAW)
    }
    
    override fun generateNonce(): ByteArray = generateRandomBytes(12) // 96-bit nonce for GCM
    
    override fun generateRandomBytes(length: Int): ByteArray {
        return provider.random.nextBytes(length)
    }
    
    override fun hash(data: ByteArray): ByteArray {
        val hasher = sha256.hasher()
        hasher.update(data)
        return hasher.hash()
    }
}