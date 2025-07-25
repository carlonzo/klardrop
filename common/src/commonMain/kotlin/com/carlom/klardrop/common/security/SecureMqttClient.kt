package com.carlom.klardrop.common.security

import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Secure MQTT client for cloud-based file transfers with TLS and JWT authentication
 */
class SecureMqttClient(
    private val config: MqttConfig,
    private val authService: AuthenticationService,
    private val cryptoProvider: CryptoProvider,
    private val clock: Clock
) {
    private var mqttConnection: MqttConnection? = null
    private val connectionMutex = kotlinx.coroutines.sync.Mutex()
    private val messageChannel = Channel<MqttMessage>(Channel.UNLIMITED)
    private var connectionJob: Job? = null
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    /**
     * Connects to the MQTT broker with JWT authentication and TLS
     */
    suspend fun connect(
        deviceCredentials: DeviceCredentials
    ): MqttConnection {
        return connectionMutex.withLock {
            if (mqttConnection?.isConnected == true) {
                return@withLock mqttConnection!!
            }
            
            _connectionState.value = ConnectionState.CONNECTING
            
            try {
                // Authenticate and get JWT
                val authResult = authService.authenticate(deviceCredentials)
                
                // Create TLS context
                val tlsContext = createTLSContext()
                
                // Configure MQTT connection
                val options = MqttConnectOptions(
                    cleanSession = false,
                    keepAliveInterval = 60,
                    connectionTimeout = 30,
                    automaticReconnect = true,
                    
                    // TLS Configuration
                    useTLS = true,
                    tlsContext = tlsContext,
                    serverCertificateValidation = true,
                    
                    // Authentication
                    username = deviceCredentials.deviceId,
                    password = authResult.accessToken,
                    
                    // Will message for presence
                    willTopic = MqttTopics.devicePresence(deviceCredentials.deviceId),
                    willMessage = createPresenceMessage(false),
                    willQos = 2,
                    willRetained = true
                )
                
                // Create connection
                val connection = createMqttConnection(config.brokerUrl, options)
                
                // Set up callbacks
                connection.onConnectionLost = { cause ->
                    handleConnectionLost(cause)
                }
                
                connection.onMessageArrived = { topic, message ->
                    handleMessageArrived(topic, message)
                }
                
                // Connect
                connection.connect()
                
                // Subscribe to required topics
                subscribeToTopics(connection, deviceCredentials.deviceId, authResult.mqttTopics)
                
                // Publish online presence
                publishPresence(connection, deviceCredentials.deviceId, true)
                
                // Start connection monitoring
                startConnectionMonitoring(connection, authResult)
                
                mqttConnection = connection
                _connectionState.value = ConnectionState.CONNECTED
                
                log("SecureMqttClient", "Connected to MQTT broker: ${config.brokerUrl}")
                
                connection
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.ERROR(e.message ?: "Connection failed")
                throw MqttConnectionException("Failed to connect to MQTT broker", e)
            }
        }
    }
    
    /**
     * Disconnects from the MQTT broker
     */
    suspend fun disconnect() {
        connectionMutex.withLock {
            connectionJob?.cancel()
            
            mqttConnection?.let { connection ->
                if (connection.isConnected) {
                    // Publish offline presence before disconnecting
                    try {
                        val deviceId = connection.clientId
                        publishPresence(connection, deviceId, false)
                    } catch (e: Exception) {
                        log("SecureMqttClient", "Failed to publish offline presence", e)
                    }
                    
                    connection.disconnect()
                }
            }
            
            mqttConnection = null
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }
    
    /**
     * Publishes a message to a topic
     */
    suspend fun publish(
        topic: String,
        payload: ByteArray,
        qos: Int = config.defaultQos,
        retained: Boolean = false
    ) {
        val connection = ensureConnected()
        
        connection.publish(
            topic = topic,
            payload = payload,
            qos = qos,
            retained = retained
        )
        
        log("SecureMqttClient", "Published ${payload.size} bytes to $topic")
    }
    
    /**
     * Subscribes to a topic pattern
     */
    suspend fun subscribe(
        topicPattern: String,
        qos: Int = config.defaultQos
    ): Flow<MqttMessage> {
        val connection = ensureConnected()
        
        connection.subscribe(topicPattern, qos)
        
        return messageChannel.receiveAsFlow()
            .filter { it.topic.matches(topicPattern.toRegex()) }
    }
    
    /**
     * Publishes a file transfer request
     */
    suspend fun publishTransferRequest(
        fromDevice: String,
        toDevice: String,
        request: TransferRequest
    ) {
        val topic = MqttTopics.transferRequest(fromDevice, toDevice)
        val payload = Json.encodeToString(TransferRequest.serializer(), request)
        
        publish(
            topic = topic,
            payload = payload.encodeToByteArray(),
            qos = 2  // Exactly once delivery
        )
    }
    
    /**
     * Publishes encrypted file chunks
     */
    suspend fun publishFileChunk(
        sessionId: String,
        chunk: EncryptedChunk
    ) {
        val topic = MqttTopics.transferData(sessionId)
        val payload = serializeChunk(chunk)
        
        publish(
            topic = topic,
            payload = payload,
            qos = 1  // At least once delivery
        )
    }
    
    /**
     * Creates a secure file transfer session
     */
    suspend fun createTransferSession(
        recipient: String,
        fileMetadata: FileMetadata
    ): TransferSessionInfo {
        val sessionId = generateSessionId()
        
        // Subscribe to session topics
        val controlTopic = MqttTopics.transferControl(sessionId)
        val dataTopic = MqttTopics.transferData(sessionId)
        
        subscribe(controlTopic)
        subscribe(dataTopic)
        
        return TransferSessionInfo(
            sessionId = sessionId,
            sender = mqttConnection?.clientId ?: throw IllegalStateException("Not connected"),
            recipient = recipient,
            metadata = fileMetadata,
            createdAt = clock.currentTimeMillis()
        )
    }
    
    private suspend fun ensureConnected(): MqttConnection {
        return mqttConnection?.takeIf { it.isConnected }
            ?: throw MqttConnectionException("Not connected to MQTT broker")
    }
    
    private suspend fun createTLSContext(): TLSContext {
        return TLSContext(
            protocol = "TLSv1.3",
            cipherSuites = listOf(
                "TLS_AES_256_GCM_SHA384",
                "TLS_CHACHA20_POLY1305_SHA256"
            ),
            certificatePinning = config.certificatePinning,
            pinnedCertificates = config.pinnedCertificates
        )
    }
    
    private fun createMqttConnection(
        brokerUrl: String,
        options: MqttConnectOptions
    ): MqttConnection {
        // Platform-specific MQTT client creation
        // This is a placeholder - actual implementation would use platform MQTT library
        return MqttConnectionImpl(brokerUrl, options)
    }
    
    private suspend fun subscribeToTopics(
        connection: MqttConnection,
        deviceId: String,
        authorizedTopics: List<String>
    ) {
        // Subscribe to device-specific topics
        val topics = listOf(
            MqttTopics.deviceControl(deviceId),
            MqttTopics.transferRequest("+", deviceId),  // Incoming transfers
            "${MqttTopics.TRANSFER_PREFIX}/+/control"   // Transfer control messages
        ) + authorizedTopics
        
        topics.forEach { topic ->
            connection.subscribe(topic, config.defaultQos)
            log("SecureMqttClient", "Subscribed to: $topic")
        }
    }
    
    private suspend fun publishPresence(
        connection: MqttConnection,
        deviceId: String,
        online: Boolean
    ) {
        val topic = MqttTopics.devicePresence(deviceId)
        val message = createPresenceMessage(online)
        
        connection.publish(
            topic = topic,
            payload = message,
            qos = 2,
            retained = true
        )
    }
    
    private fun createPresenceMessage(online: Boolean): ByteArray {
        val presence = DevicePresence(
            status = if (online) "online" else "offline",
            timestamp = clock.currentTimeMillis(),
            capabilities = listOf("file-transfer", "encrypted", "klardrop-v2")
        )
        
        return Json.encodeToString(DevicePresence.serializer(), presence).encodeToByteArray()
    }
    
    private fun startConnectionMonitoring(
        connection: MqttConnection,
        authResult: AuthResult
    ) {
        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            // Monitor connection health
            while (isActive) {
                delay(30_000) // Check every 30 seconds
                
                if (!connection.isConnected) {
                    _connectionState.value = ConnectionState.RECONNECTING
                    // Attempt reconnection
                }
                
                // Refresh token if needed
                val tokenExpiryTime = authResult.expiresIn * 1000
                val refreshThreshold = tokenExpiryTime * 0.8
                
                if (clock.currentTimeMillis() >= refreshThreshold) {
                    try {
                        val newAuth = authService.refreshToken(authResult.refreshToken)
                        connection.updateAuthentication(newAuth.accessToken)
                    } catch (e: Exception) {
                        log("SecureMqttClient", "Token refresh failed", e)
                    }
                }
            }
        }
    }
    
    private fun handleConnectionLost(cause: Throwable?) {
        log("SecureMqttClient", "Connection lost", cause)
        _connectionState.value = ConnectionState.DISCONNECTED
        
        // Trigger reconnection logic
        CoroutineScope(Dispatchers.IO).launch {
            delay(5000) // Wait 5 seconds before reconnecting
            // Implement exponential backoff for reconnection attempts
        }
    }
    
    private fun handleMessageArrived(topic: String, message: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            messageChannel.send(MqttMessage(topic, message))
        }
    }
    
    private fun serializeChunk(chunk: EncryptedChunk): ByteArray {
        // Serialize chunk with metadata
        val chunkData = ChunkData(
            index = chunk.index,
            data = chunk.data,
            nonce = chunk.nonce,
            originalSize = chunk.originalSize
        )
        
        return Json.encodeToString(ChunkData.serializer(), chunkData).encodeToByteArray()
    }
    
    private fun generateSessionId(): String {
        return "session_${clock.currentTimeMillis()}_${kotlin.random.Random.nextInt(10000)}"
    }
}

/**
 * MQTT topic structure
 */
object MqttTopics {
    const val PREFIX = "klardrop"
    const val TRANSFER_PREFIX = "$PREFIX/transfer"
    
    fun devicePresence(deviceId: String) = 
        "$PREFIX/devices/$deviceId/presence"
    
    fun deviceControl(deviceId: String) = 
        "$PREFIX/devices/$deviceId/control"
    
    fun transferRequest(fromDevice: String, toDevice: String) = 
        "$TRANSFER_PREFIX/$fromDevice/$toDevice/request"
    
    fun transferData(sessionId: String) = 
        "$TRANSFER_PREFIX/session/$sessionId/data"
    
    fun transferControl(sessionId: String) = 
        "$TRANSFER_PREFIX/session/$sessionId/control"
}

/**
 * MQTT configuration
 */
data class MqttConfig(
    val brokerUrl: String = "mqtts://mqtt.klardrop.com:8883",
    val defaultQos: Int = 1,
    val certificatePinning: Boolean = true,
    val pinnedCertificates: List<String> = emptyList(),
    val maxReconnectAttempts: Int = 5,
    val reconnectBackoffMs: Long = 1000
)

/**
 * Connection states
 */
sealed class ConnectionState {
    object DISCONNECTED : ConnectionState()
    object CONNECTING : ConnectionState()
    object CONNECTED : ConnectionState()
    object RECONNECTING : ConnectionState()
    data class ERROR(val message: String) : ConnectionState()
}

/**
 * MQTT message
 */
data class MqttMessage(
    val topic: String,
    val payload: ByteArray
)

/**
 * Transfer request
 */
@Serializable
data class TransferRequest(
    val sessionId: String,
    val sender: String,
    val recipient: String,
    val encryptedMetadata: ByteArray,
    val ephemeralPublicKey: ByteArray,
    val timestamp: Long
)

/**
 * Transfer session information
 */
data class TransferSessionInfo(
    val sessionId: String,
    val sender: String,
    val recipient: String,
    val metadata: FileMetadata,
    val createdAt: Long
)

/**
 * Device presence information
 */
@Serializable
data class DevicePresence(
    val status: String,
    val timestamp: Long,
    val capabilities: List<String>
)

/**
 * Chunk data for serialization
 */
@Serializable
data class ChunkData(
    val index: Long,
    val data: ByteArray,
    val nonce: ByteArray,
    val originalSize: Int
)

/**
 * MQTT connection options
 */
data class MqttConnectOptions(
    val cleanSession: Boolean,
    val keepAliveInterval: Int,
    val connectionTimeout: Int,
    val automaticReconnect: Boolean,
    val useTLS: Boolean,
    val tlsContext: TLSContext?,
    val serverCertificateValidation: Boolean,
    val username: String,
    val password: String,
    val willTopic: String?,
    val willMessage: ByteArray?,
    val willQos: Int?,
    val willRetained: Boolean?
)

/**
 * TLS context configuration
 */
data class TLSContext(
    val protocol: String,
    val cipherSuites: List<String>,
    val certificatePinning: Boolean,
    val pinnedCertificates: List<String>
)

/**
 * MQTT connection interface
 */
interface MqttConnection {
    val clientId: String
    val isConnected: Boolean
    
    var onConnectionLost: ((cause: Throwable?) -> Unit)?
    var onMessageArrived: ((topic: String, message: ByteArray) -> Unit)?
    
    suspend fun connect()
    suspend fun disconnect()
    suspend fun publish(topic: String, payload: ByteArray, qos: Int, retained: Boolean)
    suspend fun subscribe(topic: String, qos: Int)
    suspend fun unsubscribe(topic: String)
    suspend fun updateAuthentication(newToken: String)
}

/**
 * Placeholder MQTT connection implementation
 */
private class MqttConnectionImpl(
    private val brokerUrl: String,
    private val options: MqttConnectOptions
) : MqttConnection {
    override val clientId: String = options.username
    override var isConnected: Boolean = false
    override var onConnectionLost: ((cause: Throwable?) -> Unit)? = null
    override var onMessageArrived: ((topic: String, message: ByteArray) -> Unit)? = null
    
    override suspend fun connect() {
        // Platform-specific implementation
        isConnected = true
    }
    
    override suspend fun disconnect() {
        isConnected = false
    }
    
    override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retained: Boolean) {
        // Platform-specific implementation
    }
    
    override suspend fun subscribe(topic: String, qos: Int) {
        // Platform-specific implementation
    }
    
    override suspend fun unsubscribe(topic: String) {
        // Platform-specific implementation
    }
    
    override suspend fun updateAuthentication(newToken: String) {
        // Platform-specific implementation
    }
}

class MqttConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)