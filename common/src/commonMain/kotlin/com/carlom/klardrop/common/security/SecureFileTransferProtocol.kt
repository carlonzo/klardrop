package com.carlom.klardrop.common.security

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Random
import com.carlom.klardrop.common.utils.log
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.flow.*
import kotlinx.io.buffered
import kotlinx.serialization.Serializable

/**
 * Secure file transfer protocol implementation for cloud-based transfers
 */
class SecureFileTransferProtocol(
    private val mqttClient: SecureMqttClient,
    private val encryptionService: EncryptionService,
    private val deviceManager: DeviceManagementService,
    private val fileManager: FileManager,
    private val cryptoProvider: CryptoProvider,
    private val clock: Clock
) {
    companion object {
        const val CHUNK_SIZE = 256 * 1024  // 256KB chunks for MQTT
        const val MAX_CONCURRENT_CHUNKS = 5
        const val CHUNK_TIMEOUT_MS = 30_000L
        const val SESSION_TIMEOUT_MS = 3600_000L  // 1 hour
    }
    
    private val activeSessions = mutableMapOf<String, TransferSession>()
    
    /**
     * Initiates a secure file transfer to a recipient
     */
    suspend fun initiateTransfer(
        file: PlatformFile,
        recipient: RegisteredDevice,
        options: TransferOptions = TransferOptions()
    ): TransferResult {
        log("SecureFileTransfer", "Initiating transfer of ${file.name} to ${recipient.deviceName}")
        
        // Verify recipient is trusted
        if (recipient.trustLevel == TrustLevel.REVOKED) {
            throw SecurityException("Cannot transfer to revoked device")
        }
        
        // Create transfer session
        val session = createTransferSession(file, recipient, options)
        activeSessions[session.sessionId] = session
        
        try {
            // Perform key exchange
            val sharedSecret = performKeyExchange(session)
            session.sharedSecret = sharedSecret
            
            // Prepare file metadata
            val metadata = createFileMetadata(file, options)
            
            // Encrypt and send metadata
            val encryptedMetadata = encryptionService.encryptMetadata(
                metadata,
                sharedSecret.metadataEncryptionKey
            )
            
            // Publish transfer request
            publishTransferRequest(session, encryptedMetadata)
            
            // Wait for recipient acceptance
            val accepted = waitForAcceptance(session)
            if (!accepted) {
                return TransferResult.Rejected(session.sessionId, "Transfer rejected by recipient")
            }
            
            // Start file transfer
            val transferFlow = performFileTransfer(session, file, sharedSecret)
            
            // Collect transfer progress
            transferFlow.collect { progress ->
                when (progress) {
                    is TransferProgress.Uploading -> {
                        log("SecureFileTransfer", "Upload progress: ${progress.percentage}%")
                    }
                    is TransferProgress.Completed -> {
                        log("SecureFileTransfer", "Transfer completed successfully")
                        return TransferResult.Success(
                            sessionId = session.sessionId,
                            duration = clock.currentTimeMillis() - session.createdAt,
                            bytesTransferred = file.size()
                        )
                    }
                    is TransferProgress.Failed -> {
                        log("SecureFileTransfer", "Transfer failed: ${progress.reason}")
                        return TransferResult.Failed(session.sessionId, progress.reason)
                    }
                }
            }
            
            return TransferResult.Failed(session.sessionId, "Transfer ended unexpectedly")
            
        } catch (e: Exception) {
            log("SecureFileTransfer", "Transfer failed with exception", e)
            return TransferResult.Failed(session.sessionId, e.message ?: "Unknown error")
        } finally {
            // Clean up session
            cleanupSession(session)
        }
    }
    
    /**
     * Handles incoming transfer requests
     */
    suspend fun handleIncomingTransfer(
        request: TransferRequest
    ): Flow<ReceiveProgress> = flow {
        log("SecureFileTransfer", "Handling incoming transfer request: ${request.sessionId}")
        
        // Verify sender is trusted
        val sender = deviceManager.trustedDevices.value.find { 
            it.deviceId == request.sender 
        } ?: throw SecurityException("Unknown sender: ${request.sender}")
        
        if (sender.trustLevel == TrustLevel.REVOKED) {
            throw SecurityException("Transfer from revoked device")
        }
        
        // Create receive session
        val session = createReceiveSession(request, sender)
        activeSessions[session.sessionId] = session
        
        try {
            // Complete key exchange
            val sharedSecret = completeKeyExchange(session, request.ephemeralPublicKey)
            session.sharedSecret = sharedSecret
            
            // Decrypt metadata
            val metadata = encryptionService.decryptMetadata(
                request.encryptedMetadata,
                sharedSecret.metadataEncryptionKey
            )
            
            emit(ReceiveProgress.Metadata(metadata))
            
            // Accept or reject transfer
            emit(ReceiveProgress.WaitingForAcceptance)
            
            // If accepted, receive file
            val fileTransfer = fileManager.prepareSaveFile(
                fileName = metadata.fileName,
                mimeType = metadata.mimeType
            )
            
            // Subscribe to data chunks
            val chunkFlow = subscribeToChunks(session.sessionId)
            
            // Decrypt and save chunks
            var receivedBytes = 0L
            chunkFlow.collect { encryptedChunk ->
                val decryptedData = decryptChunk(
                    encryptedChunk,
                    sharedSecret.fileEncryptionKey
                )
                
                fileTransfer.bufferedSink.use { sink ->
                    sink.write(decryptedData)
                }
                
                receivedBytes += decryptedData.size
                val progress = ((receivedBytes * 100) / metadata.fileSize).toInt()
                
                emit(ReceiveProgress.Downloading(progress, receivedBytes, metadata.fileSize))
                
                if (receivedBytes >= metadata.fileSize) {
                    fileTransfer.onTransferCompleted()
                    emit(ReceiveProgress.Completed(fileTransfer.file))
                }
            }
            
        } catch (e: Exception) {
            emit(ReceiveProgress.Failed(e.message ?: "Receive failed"))
            throw e
        } finally {
            cleanupSession(session)
        }
    }
    
    private suspend fun createTransferSession(
        file: PlatformFile,
        recipient: RegisteredDevice,
        options: TransferOptions
    ): TransferSession {
        val sessionId = generateSessionId()
        val localDevice = deviceManager.trustedDevices.value.firstOrNull()
            ?: throw IllegalStateException("Local device not registered")
        
        return TransferSession(
            sessionId = sessionId,
            file = file,
            sender = localDevice,
            recipient = recipient,
            options = options,
            state = TransferState.INITIATED,
            createdAt = clock.currentTimeMillis()
        )
    }
    
    private suspend fun createReceiveSession(
        request: TransferRequest,
        sender: RegisteredDevice
    ): TransferSession {
        val localDevice = deviceManager.trustedDevices.value.firstOrNull()
            ?: throw IllegalStateException("Local device not registered")
        
        return TransferSession(
            sessionId = request.sessionId,
            file = null,  // Will be created when receiving
            sender = sender,
            recipient = localDevice,
            options = TransferOptions(),
            state = TransferState.RECEIVING,
            createdAt = clock.currentTimeMillis()
        )
    }
    
    private suspend fun performKeyExchange(session: TransferSession): SharedSecret {
        // Generate ephemeral key pair
        val ephemeralKeyPair = cryptoProvider.generateEphemeralKeyPair()
        session.ephemeralKeyPair = ephemeralKeyPair
        
        // Perform ECDH with recipient's public key
        return encryptionService.performKeyExchange(
            localPrivateKey = ephemeralKeyPair.privateKey,
            remotePublicKey = session.recipient.publicKey
        )
    }
    
    private suspend fun completeKeyExchange(
        session: TransferSession,
        remoteEphemeralPublicKey: ByteArray
    ): SharedSecret {
        // Generate ephemeral key pair if not already done
        val ephemeralKeyPair = session.ephemeralKeyPair 
            ?: cryptoProvider.generateEphemeralKeyPair().also {
                session.ephemeralKeyPair = it
            }
        
        // Perform ECDH with remote ephemeral public key
        return encryptionService.performKeyExchange(
            localPrivateKey = ephemeralKeyPair.privateKey,
            remotePublicKey = remoteEphemeralPublicKey
        )
    }
    
    private suspend fun createFileMetadata(
        file: PlatformFile,
        options: TransferOptions
    ): FileMetadata {
        return FileMetadata(
            fileName = file.name,
            fileSize = file.size(),
            mimeType = file.mimeType ?: "application/octet-stream",
            compressionType = if (options.compress) "gzip" else null
        )
    }
    
    private suspend fun publishTransferRequest(
        session: TransferSession,
        encryptedMetadata: ByteArray
    ) {
        val request = TransferRequest(
            sessionId = session.sessionId,
            sender = session.sender.deviceId,
            recipient = session.recipient.deviceId,
            encryptedMetadata = encryptedMetadata,
            ephemeralPublicKey = session.ephemeralKeyPair?.publicKey 
                ?: throw IllegalStateException("Ephemeral key not generated"),
            timestamp = clock.currentTimeMillis()
        )
        
        mqttClient.publishTransferRequest(
            fromDevice = session.sender.deviceId,
            toDevice = session.recipient.deviceId,
            request = request
        )
    }
    
    private suspend fun waitForAcceptance(session: TransferSession): Boolean {
        val controlTopic = MqttTopics.transferControl(session.sessionId)
        val controlFlow = mqttClient.subscribe(controlTopic)
        
        return controlFlow
            .map { message ->
                // Parse control message
                val control = parseControlMessage(message.payload)
                control.type == ControlMessageType.ACCEPT
            }
            .first()
    }
    
    private fun performFileTransfer(
        session: TransferSession,
        file: PlatformFile,
        sharedSecret: SharedSecret
    ): Flow<TransferProgress> = flow {
        val fileSize = file.size()
        val inputStream = fileManager.getReadStreamFrom(file)
        
        val encryptedChunks = encryptionService.encryptStream(
            inputStream = inputStream,
            key = sharedSecret.fileEncryptionKey,
            fileSize = fileSize
        )
        
        var uploadedBytes = 0L
        
        encryptedChunks.collect { chunk ->
            // Publish chunk via MQTT
            mqttClient.publishFileChunk(session.sessionId, chunk)
            
            uploadedBytes += chunk.originalSize
            val progress = ((uploadedBytes * 100) / fileSize).toInt()
            
            emit(TransferProgress.Uploading(progress, uploadedBytes, fileSize))
        }
        
        emit(TransferProgress.Completed)
    }
    
    private suspend fun subscribeToChunks(sessionId: String): Flow<EncryptedChunk> {
        val dataTopic = MqttTopics.transferData(sessionId)
        
        return mqttClient.subscribe(dataTopic)
            .map { message ->
                deserializeChunk(message.payload)
            }
    }
    
    private suspend fun decryptChunk(
        encryptedChunk: EncryptedChunk,
        key: ByteArray
    ): ByteArray {
        val cipher = cryptoProvider.createAESGCMCipher(key)
        return cipher.decrypt(encryptedChunk.data, encryptedChunk.nonce)
    }
    
    private fun cleanupSession(session: TransferSession) {
        activeSessions.remove(session.sessionId)
        
        // Unsubscribe from session topics
        val topics = listOf(
            MqttTopics.transferControl(session.sessionId),
            MqttTopics.transferData(session.sessionId)
        )
        
        // Clean up would unsubscribe from topics
    }
    
    private fun generateSessionId(): String {
        return "transfer_${clock.currentTimeMillis()}_${Random.randomAlphanumeric(8)}"
    }
    
    private fun parseControlMessage(payload: ByteArray): ControlMessage {
        // Parse control message from payload
        return ControlMessage(ControlMessageType.ACCEPT)  // Placeholder
    }
    
    private fun deserializeChunk(payload: ByteArray): EncryptedChunk {
        // Deserialize chunk from payload
        return EncryptedChunk(0, ByteArray(0), ByteArray(0), 0)  // Placeholder
    }
}

/**
 * Transfer session information
 */
private data class TransferSession(
    val sessionId: String,
    val file: PlatformFile?,
    val sender: RegisteredDevice,
    val recipient: RegisteredDevice,
    val options: TransferOptions,
    var state: TransferState,
    val createdAt: Long,
    var sharedSecret: SharedSecret? = null,
    var ephemeralKeyPair: EphemeralKeyPair? = null
)

/**
 * Transfer state
 */
private enum class TransferState {
    INITIATED,
    KEY_EXCHANGE,
    WAITING_ACCEPTANCE,
    TRANSFERRING,
    RECEIVING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Transfer options
 */
data class TransferOptions(
    val compress: Boolean = false,
    val priority: TransferPriority = TransferPriority.NORMAL,
    val expiresAfter: Long = 3600_000L,  // 1 hour
    val requireConfirmation: Boolean = true
)

/**
 * Transfer priority
 */
enum class TransferPriority {
    LOW,
    NORMAL,
    HIGH
}

/**
 * Transfer result
 */
sealed class TransferResult {
    data class Success(
        val sessionId: String,
        val duration: Long,
        val bytesTransferred: Long
    ) : TransferResult()
    
    data class Failed(
        val sessionId: String,
        val reason: String
    ) : TransferResult()
    
    data class Rejected(
        val sessionId: String,
        val reason: String
    ) : TransferResult()
}

/**
 * Transfer progress states
 */
sealed class TransferProgress {
    data class Uploading(
        val percentage: Int,
        val bytesUploaded: Long,
        val totalBytes: Long
    ) : TransferProgress()
    
    object Completed : TransferProgress()
    
    data class Failed(val reason: String) : TransferProgress()
}

/**
 * Receive progress states
 */
sealed class ReceiveProgress {
    data class Metadata(val metadata: FileMetadata) : ReceiveProgress()
    object WaitingForAcceptance : ReceiveProgress()
    data class Downloading(
        val percentage: Int,
        val bytesReceived: Long,
        val totalBytes: Long
    ) : ReceiveProgress()
    data class Completed(val file: PlatformFile) : ReceiveProgress()
    data class Failed(val reason: String) : ReceiveProgress()
}

/**
 * Control message types
 */
private enum class ControlMessageType {
    ACCEPT,
    REJECT,
    CANCEL,
    PAUSE,
    RESUME,
    ACK
}

/**
 * Control message
 */
private data class ControlMessage(
    val type: ControlMessageType,
    val payload: ByteArray? = null
)