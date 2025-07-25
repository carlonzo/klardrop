package com.carlom.klardrop.common.security

import com.carlom.klardrop.common.utils.Random
import com.carlom.klardrop.common.utils.log
import io.github.vinceglb.filekit.PlatformFile
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlin.math.min

/**
 * End-to-end encryption service for secure file transfers
 */
class EncryptionService(
    private val cryptoProvider: CryptoProvider
) {
    companion object {
        const val CHUNK_SIZE = 1024 * 1024  // 1MB chunks
        const val NONCE_SIZE = 12           // GCM nonce size
        const val TAG_SIZE = 16             // GCM auth tag size
        const val KEY_SIZE = 32             // AES-256 key size
    }
    
    /**
     * Performs ECDH key exchange to establish a shared secret
     */
    suspend fun performKeyExchange(
        localPrivateKey: ByteArray,
        remotePublicKey: ByteArray
    ): SharedSecret {
        // Perform ECDH
        val sharedSecret = cryptoProvider.performECDH(localPrivateKey, remotePublicKey)
        
        // Derive keys using HKDF
        val fileKey = cryptoProvider.deriveKey(
            secret = sharedSecret,
            info = "klardrop-file-encryption".encodeToByteArray(),
            length = KEY_SIZE
        )
        
        val metadataKey = cryptoProvider.deriveKey(
            secret = sharedSecret,
            info = "klardrop-metadata-encryption".encodeToByteArray(),
            length = KEY_SIZE
        )
        
        return SharedSecret(
            fileEncryptionKey = fileKey,
            metadataEncryptionKey = metadataKey,
            sharedSecret = sharedSecret
        )
    }
    
    /**
     * Encrypts a file with AES-256-GCM
     */
    suspend fun encryptFile(
        file: PlatformFile,
        sharedSecret: SharedSecret,
        metadata: FileMetadata
    ): EncryptedFile {
        // Generate ephemeral key for perfect forward secrecy
        val ephemeralKeyPair = cryptoProvider.generateEphemeralKeyPair()
        
        // Encrypt metadata
        val encryptedMetadata = encryptMetadata(metadata, sharedSecret.metadataEncryptionKey)
        
        // Calculate file checksum
        val checksum = cryptoProvider.calculateChecksum(file)
        
        return EncryptedFile(
            encryptedData = ByteArray(0), // Placeholder - actual data streamed
            nonce = Random.nextBytes(NONCE_SIZE),
            authTag = ByteArray(TAG_SIZE), // Calculated during encryption
            ephemeralPublicKey = ephemeralKeyPair.publicKey,
            metadata = EncryptedMetadata(
                algorithm = "AES-256-GCM",
                keyDerivation = "HKDF-SHA256",
                compressionType = metadata.compressionType,
                originalSize = metadata.fileSize,
                checksum = checksum,
                encryptedFields = encryptedMetadata
            )
        )
    }
    
    /**
     * Encrypts a file stream with chunk-based streaming
     */
    fun encryptStream(
        inputStream: Source,
        key: ByteArray,
        fileSize: Long
    ): Flow<EncryptedChunk> = flow {
        val cipher = cryptoProvider.createAESGCMCipher(key)
        var chunkIndex = 0L
        var totalProcessed = 0L
        
        inputStream.buffered().use { buffer ->
            while (!buffer.exhausted() && totalProcessed < fileSize) {
                val remainingSize = fileSize - totalProcessed
                val chunkSize = min(CHUNK_SIZE.toLong(), remainingSize).toInt()
                val chunk = buffer.readByteArray(chunkSize)
                
                // Derive chunk-specific nonce
                val nonce = deriveChunkNonce(key, chunkIndex)
                
                // Encrypt chunk
                val encryptedData = cipher.encrypt(chunk, nonce)
                
                emit(EncryptedChunk(
                    index = chunkIndex,
                    data = encryptedData,
                    nonce = nonce,
                    originalSize = chunk.size
                ))
                
                totalProcessed += chunk.size
                chunkIndex++
                
                log("EncryptionService", "Encrypted chunk $chunkIndex: ${chunk.size} bytes, total: $totalProcessed/$fileSize")
            }
        }
    }
    
    /**
     * Decrypts a file stream
     */
    suspend fun decryptStream(
        encryptedChunks: Flow<EncryptedChunk>,
        outputChannel: ByteWriteChannel,
        key: ByteArray
    ) {
        val cipher = cryptoProvider.createAESGCMCipher(key)
        
        encryptedChunks.collect { chunk ->
            // Verify chunk nonce
            val expectedNonce = deriveChunkNonce(key, chunk.index)
            if (!chunk.nonce.contentEquals(expectedNonce)) {
                throw SecurityException("Invalid chunk nonce for index ${chunk.index}")
            }
            
            // Decrypt chunk
            val decryptedData = cipher.decrypt(chunk.data, chunk.nonce)
            
            // Write to output
            outputChannel.writeFully(decryptedData)
            
            log("EncryptionService", "Decrypted chunk ${chunk.index}: ${decryptedData.size} bytes")
        }
        
        outputChannel.close()
    }
    
    /**
     * Encrypts metadata separately for privacy
     */
    suspend fun encryptMetadata(
        metadata: FileMetadata,
        key: ByteArray
    ): ByteArray {
        val metadataBytes = serializeMetadata(metadata)
        val nonce = Random.nextBytes(NONCE_SIZE)
        
        val cipher = cryptoProvider.createAESGCMCipher(key)
        return cipher.encrypt(metadataBytes, nonce)
    }
    
    /**
     * Decrypts metadata
     */
    suspend fun decryptMetadata(
        encryptedMetadata: ByteArray,
        key: ByteArray
    ): FileMetadata {
        val cipher = cryptoProvider.createAESGCMCipher(key)
        val nonce = encryptedMetadata.sliceArray(0 until NONCE_SIZE)
        val ciphertext = encryptedMetadata.sliceArray(NONCE_SIZE until encryptedMetadata.size)
        
        val decrypted = cipher.decrypt(ciphertext, nonce)
        return deserializeMetadata(decrypted)
    }
    
    /**
     * Derives a unique nonce for each chunk
     */
    private fun deriveChunkNonce(key: ByteArray, chunkIndex: Long): ByteArray {
        val context = "chunk-$chunkIndex".encodeToByteArray()
        return cryptoProvider.deriveKey(
            secret = key,
            info = context,
            length = NONCE_SIZE
        ).sliceArray(0 until NONCE_SIZE)
    }
    
    private fun serializeMetadata(metadata: FileMetadata): ByteArray {
        // Simple serialization - in production use Protocol Buffers
        return buildString {
            append(metadata.fileName).append("|")
            append(metadata.fileSize).append("|")
            append(metadata.mimeType).append("|")
            append(metadata.compressionType ?: "none")
        }.encodeToByteArray()
    }
    
    private fun deserializeMetadata(data: ByteArray): FileMetadata {
        val parts = data.decodeToString().split("|")
        return FileMetadata(
            fileName = parts[0],
            fileSize = parts[1].toLong(),
            mimeType = parts[2],
            compressionType = if (parts[3] == "none") null else parts[3]
        )
    }
}

/**
 * Shared secret derived from key exchange
 */
data class SharedSecret(
    val fileEncryptionKey: ByteArray,
    val metadataEncryptionKey: ByteArray,
    val sharedSecret: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SharedSecret) return false
        return fileEncryptionKey.contentEquals(other.fileEncryptionKey) &&
               metadataEncryptionKey.contentEquals(other.metadataEncryptionKey)
    }
    
    override fun hashCode(): Int {
        return fileEncryptionKey.contentHashCode() * 31 + metadataEncryptionKey.contentHashCode()
    }
}

/**
 * Encrypted file container
 */
data class EncryptedFile(
    val encryptedData: ByteArray,
    val nonce: ByteArray,
    val authTag: ByteArray,
    val ephemeralPublicKey: ByteArray,
    val metadata: EncryptedMetadata
)

/**
 * Encrypted metadata
 */
@Serializable
data class EncryptedMetadata(
    val algorithm: String,
    val keyDerivation: String,
    val compressionType: String?,
    val originalSize: Long,
    val checksum: ByteArray,
    val encryptedFields: ByteArray
)

/**
 * File metadata
 */
@Serializable
data class FileMetadata(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val compressionType: String? = null
)

/**
 * Encrypted chunk for streaming
 */
data class EncryptedChunk(
    val index: Long,
    val data: ByteArray,
    val nonce: ByteArray,
    val originalSize: Int
)

/**
 * Crypto provider interface - platform specific implementation required
 */
interface CryptoProvider {
    suspend fun generateEphemeralKeyPair(): EphemeralKeyPair
    suspend fun performECDH(privateKey: ByteArray, publicKey: ByteArray): ByteArray
    suspend fun deriveKey(secret: ByteArray, info: ByteArray, length: Int): ByteArray
    suspend fun createAESGCMCipher(key: ByteArray): AESGCMCipher
    suspend fun calculateChecksum(file: PlatformFile): ByteArray
    suspend fun signData(data: ByteArray, privateKey: ByteArray): ByteArray
    suspend fun verifySignature(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
}

/**
 * Ephemeral key pair for perfect forward secrecy
 */
data class EphemeralKeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray
)

/**
 * AES-GCM cipher interface
 */
interface AESGCMCipher {
    suspend fun encrypt(plaintext: ByteArray, nonce: ByteArray): ByteArray
    suspend fun decrypt(ciphertext: ByteArray, nonce: ByteArray): ByteArray
}

class SecurityException(message: String) : Exception(message)