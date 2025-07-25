package com.carlom.klardrop.cloud.transfer.mqtt

import com.carlom.klardrop.cloud.transfer.config.MqttConfig
import com.carlom.klardrop.cloud.transfer.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

class MqttTransferClient(
    private val config: MqttConfig,
    private val clientId: String
) {
    private val client: MqttAsyncClient = MqttAsyncClient(
        config.brokerUrl,
        "$clientId-transfer",
        MemoryPersistence()
    )
    
    private val json = Json { 
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    private val transferChannels = ConcurrentHashMap<String, Channel<TransferEvent>>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        setupCallbacks()
    }
    
    suspend fun connect() = withContext(Dispatchers.IO) {
        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            connectionTimeout = 30
            keepAliveInterval = 60
            userName = config.username
            password = config.password.toCharArray()
            
            // Configure SSL if needed
            if (config.brokerUrl.startsWith("ssl://") || config.brokerUrl.startsWith("wss://")) {
                socketFactory = createSSLSocketFactory()
            }
        }
        
        client.connect(options).waitForCompletion()
        logger.info { "MQTT client connected to ${config.brokerUrl}" }
        
        // Subscribe to device-specific topics
        subscribeToDeviceTopics()
    }
    
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        transferChannels.values.forEach { it.close() }
        transferChannels.clear()
        
        if (client.isConnected) {
            client.disconnect().waitForCompletion()
        }
        scope.cancel()
        
        logger.info { "MQTT client disconnected" }
    }
    
    suspend fun initiateTransfer(
        transferId: String,
        senderId: String,
        receiverId: String,
        request: TransferRequest
    ): Flow<TransferEvent> = flow {
        val channel = Channel<TransferEvent>(Channel.UNLIMITED)
        transferChannels[transferId] = channel
        
        try {
            // Subscribe to transfer-specific topics
            val transferTopic = "klardrop/transfers/$transferId/+"
            client.subscribe(transferTopic, config.qos) { _, message ->
                scope.launch {
                    handleTransferMessage(transferId, message)
                }
            }
            
            // Publish transfer initiation
            val initTopic = "klardrop/transfers/requests/$receiverId"
            val initMessage = TransferInitiation(
                transferId = transferId,
                senderId = senderId,
                receiverId = receiverId,
                request = request
            )
            
            publish(initTopic, json.encodeToString(initMessage))
            
            // Emit events from channel
            for (event in channel) {
                emit(event)
            }
        } finally {
            transferChannels.remove(transferId)
            client.unsubscribe("klardrop/transfers/$transferId/+")
        }
    }
    
    suspend fun sendFileChunk(
        transferId: String,
        fileId: String,
        chunk: FileChunk
    ) {
        val topic = "klardrop/transfers/$transferId/data"
        val message = TransferData(
            transferId = transferId,
            fileId = fileId,
            chunk = chunk
        )
        
        // For file data, we might want to use QoS 0 for performance
        publish(topic, json.encodeToString(message), qos = 0)
    }
    
    suspend fun acceptTransfer(transferId: String) {
        val topic = "klardrop/transfers/$transferId/accept"
        val message = TransferAcceptance(
            transferId = transferId,
            timestamp = System.currentTimeMillis()
        )
        
        publish(topic, json.encodeToString(message))
    }
    
    suspend fun rejectTransfer(transferId: String, reason: String) {
        val topic = "klardrop/transfers/$transferId/reject"
        val message = TransferRejection(
            transferId = transferId,
            reason = reason,
            timestamp = System.currentTimeMillis()
        )
        
        publish(topic, json.encodeToString(message))
    }
    
    suspend fun updateProgress(
        transferId: String,
        fileId: String,
        bytesTransferred: Long,
        totalBytes: Long
    ) {
        val topic = "klardrop/transfers/$transferId/progress"
        val message = TransferProgress(
            transferId = transferId,
            fileId = fileId,
            bytesTransferred = bytesTransferred,
            totalBytes = totalBytes,
            percentage = (bytesTransferred * 100.0 / totalBytes).toFloat()
        )
        
        publish(topic, json.encodeToString(message))
    }
    
    suspend fun completeTransfer(transferId: String, success: Boolean, error: String? = null) {
        val topic = "klardrop/transfers/$transferId/complete"
        val message = TransferCompletion(
            transferId = transferId,
            success = success,
            error = error,
            timestamp = System.currentTimeMillis()
        )
        
        publish(topic, json.encodeToString(message))
    }
    
    private suspend fun publish(topic: String, payload: String, qos: Int = config.qos) = withContext(Dispatchers.IO) {
        val message = MqttMessage(payload.toByteArray()).apply {
            this.qos = qos
            isRetained = false
        }
        
        client.publish(topic, message).waitForCompletion()
        logger.debug { "Published to $topic: ${payload.take(100)}" }
    }
    
    private suspend fun handleTransferMessage(transferId: String, message: MqttMessage) {
        val channel = transferChannels[transferId] ?: return
        
        try {
            val payload = String(message.payload)
            val topic = message.properties.getProperty("topic") ?: return
            
            val event = when {
                topic.endsWith("/accept") -> {
                    val acceptance = json.decodeFromString<TransferAcceptance>(payload)
                    TransferEvent.Accepted(transferId)
                }
                topic.endsWith("/reject") -> {
                    val rejection = json.decodeFromString<TransferRejection>(payload)
                    TransferEvent.Rejected(transferId, rejection.reason)
                }
                topic.endsWith("/progress") -> {
                    val progress = json.decodeFromString<TransferProgress>(payload)
                    TransferEvent.Progress(
                        transferId,
                        progress.fileId,
                        progress.bytesTransferred,
                        progress.totalBytes
                    )
                }
                topic.endsWith("/data") -> {
                    val data = json.decodeFromString<TransferData>(payload)
                    TransferEvent.DataReceived(
                        transferId,
                        data.fileId,
                        data.chunk
                    )
                }
                topic.endsWith("/complete") -> {
                    val completion = json.decodeFromString<TransferCompletion>(payload)
                    if (completion.success) {
                        TransferEvent.Completed(transferId)
                    } else {
                        TransferEvent.Failed(transferId, completion.error ?: "Unknown error")
                    }
                }
                else -> null
            }
            
            event?.let { channel.send(it) }
        } catch (e: Exception) {
            logger.error(e) { "Error handling transfer message" }
        }
    }
    
    private fun setupCallbacks() {
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                logger.info { "MQTT connection complete (reconnect: $reconnect)" }
                scope.launch {
                    subscribeToDeviceTopics()
                }
            }
            
            override fun connectionLost(cause: Throwable?) {
                logger.warn(cause) { "MQTT connection lost" }
            }
            
            override fun messageArrived(topic: String, message: MqttMessage) {
                logger.debug { "Message arrived on topic: $topic" }
            }
            
            override fun deliveryComplete(token: IMqttDeliveryToken) {
                logger.debug { "Delivery complete for message: ${token.messageId}" }
            }
        })
    }
    
    private suspend fun subscribeToDeviceTopics() = withContext(Dispatchers.IO) {
        val topics = arrayOf(
            "klardrop/devices/$clientId/command",
            "klardrop/transfers/requests/$clientId"
        )
        
        val qos = IntArray(topics.size) { config.qos }
        
        client.subscribe(topics, qos) { topic, message ->
            scope.launch {
                handleDeviceMessage(topic, message)
            }
        }
        
        logger.info { "Subscribed to device topics" }
    }
    
    private suspend fun handleDeviceMessage(topic: String, message: MqttMessage) {
        try {
            val payload = String(message.payload)
            
            when {
                topic.endsWith("/command") -> {
                    // Handle device commands
                    logger.info { "Received command: $payload" }
                }
                topic.contains("/transfers/requests/") -> {
                    // Handle incoming transfer requests
                    val initiation = json.decodeFromString<TransferInitiation>(payload)
                    handleIncomingTransfer(initiation)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error handling device message" }
        }
    }
    
    private suspend fun handleIncomingTransfer(initiation: TransferInitiation) {
        // This would typically be handled by a callback or event system
        logger.info { "Incoming transfer request: ${initiation.transferId}" }
    }
    
    private fun createSSLSocketFactory(): javax.net.ssl.SSLSocketFactory {
        // Implement SSL socket factory creation
        // This would load certificates and configure TLS
        return javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
    }
}

// Transfer event types
sealed class TransferEvent {
    data class Accepted(val transferId: String) : TransferEvent()
    data class Rejected(val transferId: String, val reason: String) : TransferEvent()
    data class Progress(
        val transferId: String,
        val fileId: String,
        val bytesTransferred: Long,
        val totalBytes: Long
    ) : TransferEvent()
    data class DataReceived(
        val transferId: String,
        val fileId: String,
        val chunk: FileChunk
    ) : TransferEvent()
    data class Completed(val transferId: String) : TransferEvent()
    data class Failed(val transferId: String, val error: String) : TransferEvent()
}