# MQTT Implementation Guide for Klardrop

## Table of Contents
1. [Project Setup](#project-setup)
2. [Protocol Buffer Definitions](#protocol-buffer-definitions)
3. [Core MQTT Components](#core-mqtt-components)
4. [Integration Examples](#integration-examples)
5. [Testing Strategy](#testing-strategy)

## Project Setup

### Dependencies

Add to `gradle/dependencies.toml`:

```toml
[versions]
hivemq = "1.3.0"
mqtt-protobuf = "1.0.0"

[libraries]
hivemq-mqtt-client = { module = "com.hivemq:hivemq-mqtt-client", version.ref = "hivemq" }
hivemq-mqtt-client-websocket = { module = "com.hivemq:hivemq-mqtt-client-websocket", version.ref = "hivemq" }

[bundles]
mqtt = ["hivemq-mqtt-client", "hivemq-mqtt-client-websocket"]
```

### Module Structure

```
common/
├── src/
│   ├── commonMain/
│   │   ├── kotlin/
│   │   │   └── com/carlom/klardrop/common/
│   │   │       └── mqtt/
│   │   │           ├── MqttConnectionManager.kt
│   │   │           ├── MqttDiscoveryService.kt
│   │   │           ├── MqttFileTransferManager.kt
│   │   │           ├── MqttMessageHandler.kt
│   │   │           └── models/
│   │   └── proto/
│   │       └── mqtt_messages.proto
```

## Protocol Buffer Definitions

### mqtt_messages.proto

```protobuf
syntax = "proto3";

package klardrop.mqtt;

option java_package = "com.carlom.klardrop.common.mqtt.proto";
option java_multiple_files = true;

import "wire_format.proto";

// Device discovery and presence
message MqttDeviceInfo {
  string device_id = 1;
  string short_device_id = 2;
  string device_name = 3;
  sharing.nearby.DeviceType device_type = 4;
  int64 last_seen = 5;
  DeviceCapabilities capabilities = 6;
  repeated string available_protocols = 7;
}

message DeviceCapabilities {
  int64 max_file_size = 1;
  int32 max_concurrent_transfers = 2;
  bool supports_resume = 3;
  bool supports_compression = 4;
  string mqtt_client_version = 5;
}

// Transfer protocol messages
message CloudTransferRequest {
  string transfer_id = 1;
  string sender_device_id = 2;
  string receiver_device_id = 3;
  int64 timestamp = 4;
  repeated sharing.nearby.FileMetadata files = 5;
  TransferOptions options = 6;
  bytes sender_public_key = 7; // For E2E encryption
}

message TransferOptions {
  bool enable_encryption = 1;
  bool enable_compression = 2;
  int32 chunk_size_bytes = 3;
  int32 parallel_chunks = 4;
  int32 ttl_seconds = 5; // Time to live for the transfer
}

message CloudTransferResponse {
  string transfer_id = 1;
  ResponseStatus status = 2;
  string message = 3;
  repeated int64 accepted_file_ids = 4;
  bytes receiver_public_key = 5; // For E2E encryption
  
  enum ResponseStatus {
    UNKNOWN = 0;
    ACCEPTED = 1;
    REJECTED = 2;
    PARTIAL_ACCEPT = 3;
    NO_SPACE = 4;
    UNSUPPORTED = 5;
    DEVICE_OFFLINE = 6;
  }
}

message FileChunk {
  string transfer_id = 1;
  int64 file_id = 2;
  int32 chunk_index = 3;
  int32 total_chunks = 4;
  bytes data = 5;
  string checksum = 6;
  bool is_compressed = 7;
  bool is_encrypted = 8;
}

message ChunkAcknowledgment {
  string transfer_id = 1;
  int64 file_id = 2;
  repeated int32 received_chunks = 3;
  repeated int32 missing_chunks = 4;
}

message TransferProgress {
  string transfer_id = 1;
  int64 file_id = 2;
  int64 bytes_transferred = 3;
  int64 total_bytes = 4;
  float transfer_speed_mbps = 5;
  int32 chunks_completed = 6;
  int32 total_chunks = 7;
  int64 eta_seconds = 8;
}

message TransferControl {
  string transfer_id = 1;
  ControlCommand command = 2;
  string reason = 3;
  
  enum ControlCommand {
    UNKNOWN = 0;
    PAUSE = 1;
    RESUME = 2;
    CANCEL = 3;
    RETRY = 4;
  }
}
```

## Core MQTT Components

### MqttConnectionManager.kt

```kotlin
package com.carlom.klardrop.common.mqtt

import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.message.connect.Mqtt5Connect
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscribe
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class MqttConnectionManager(
    private val applicationInfo: ApplicationInfo,
    private val coroutines: Coroutines,
    private val currentDeviceProvider: CurrentDeviceProvider
) {
    companion object {
        private const val MQTT_BROKER_HOST = "broker.hivemq.cloud"
        private const val MQTT_BROKER_PORT = 8883
        private const val KEEP_ALIVE_SECONDS = 60
        private const val SESSION_EXPIRY_SECONDS = 3600L
        private const val MAX_RECONNECT_DELAY_SECONDS = 60L
    }

    private val scope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _incomingMessages = MutableSharedFlow<MqttMessage>()
    val incomingMessages: SharedFlow<MqttMessage> = _incomingMessages.asSharedFlow()
    
    private lateinit var mqttClient: Mqtt5AsyncClient
    private val subscriptions = mutableSetOf<String>()
    
    data class MqttMessage(
        val topic: String,
        val payload: ByteArray,
        val qos: Int,
        val retained: Boolean
    )
    
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        ERROR
    }
    
    suspend fun initialize() = withContext(coroutines.ioDispatcher) {
        val currentDevice = currentDeviceProvider.get()
        val clientId = "klardrop_${currentDevice.shortDeviceId}_${UUID.randomUUID()}"
        
        mqttClient = MqttClient.builder()
            .useMqttVersion5()
            .identifier(clientId)
            .serverHost(applicationInfo.mqttBrokerHost ?: MQTT_BROKER_HOST)
            .serverPort(applicationInfo.mqttBrokerPort ?: MQTT_BROKER_PORT)
            .sslWithDefaultConfig()
            .automaticReconnect()
                .initialDelay(1, TimeUnit.SECONDS)
                .maxDelay(MAX_RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS)
                .applyAutomaticReconnect()
            .addConnectedListener {
                scope.launch {
                    handleConnected()
                }
            }
            .addDisconnectedListener { context ->
                scope.launch {
                    handleDisconnected(context.cause)
                }
            }
            .buildAsync()
            
        setupMessageHandling()
    }
    
    suspend fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            log("MqttConnectionManager", "Already connected")
            return
        }
        
        _connectionState.value = ConnectionState.CONNECTING
        
        try {
            val connectMessage = Mqtt5Connect.builder()
                .cleanStart(false)
                .sessionExpiryInterval(SESSION_EXPIRY_SECONDS)
                .keepAlive(KEEP_ALIVE_SECONDS)
                .build()
                
            mqttClient.connect(connectMessage).await()
            log("MqttConnectionManager", "Successfully connected to MQTT broker")
        } catch (e: Exception) {
            log("MqttConnectionManager", "Failed to connect", e)
            _connectionState.value = ConnectionState.ERROR
            throw e
        }
    }
    
    suspend fun disconnect() {
        if (mqttClient.state != MqttClientState.CONNECTED) {
            return
        }
        
        try {
            mqttClient.disconnect().await()
            _connectionState.value = ConnectionState.DISCONNECTED
            log("MqttConnectionManager", "Disconnected from MQTT broker")
        } catch (e: Exception) {
            log("MqttConnectionManager", "Error during disconnect", e)
        }
    }
    
    suspend fun publish(
        topic: String,
        payload: ByteArray,
        qos: Int = 1,
        retained: Boolean = false
    ) {
        ensureConnected()
        
        val publishMessage = Mqtt5Publish.builder()
            .topic(topic)
            .payload(payload)
            .qos(MqttQos.fromCode(qos))
            .retain(retained)
            .build()
            
        mqttClient.publish(publishMessage).await()
        log("MqttConnectionManager", "Published to topic: $topic, size: ${payload.size}")
    }
    
    suspend fun subscribe(
        topic: String,
        qos: Int = 1
    ) {
        ensureConnected()
        
        val subscription = Mqtt5Subscribe.builder()
            .topicFilter(topic)
            .qos(MqttQos.fromCode(qos))
            .build()
            
        mqttClient.subscribe(subscription).await()
        subscriptions.add(topic)
        log("MqttConnectionManager", "Subscribed to topic: $topic")
    }
    
    suspend fun unsubscribe(topic: String) {
        if (mqttClient.state != MqttClientState.CONNECTED) {
            return
        }
        
        mqttClient.unsubscribe().topicFilter(topic).send().await()
        subscriptions.remove(topic)
        log("MqttConnectionManager", "Unsubscribed from topic: $topic")
    }
    
    private fun setupMessageHandling() {
        mqttClient.publishes(MqttGlobalPublishFilter.ALL) { publish ->
            scope.launch {
                val message = MqttMessage(
                    topic = publish.topic.toString(),
                    payload = publish.payloadAsBytes,
                    qos = publish.qos.code,
                    retained = publish.isRetain
                )
                _incomingMessages.emit(message)
            }
        }
    }
    
    private suspend fun handleConnected() {
        _connectionState.value = ConnectionState.CONNECTED
        
        // Resubscribe to all topics
        subscriptions.forEach { topic ->
            try {
                subscribe(topic)
            } catch (e: Exception) {
                log("MqttConnectionManager", "Failed to resubscribe to $topic", e)
            }
        }
        
        // Publish device presence
        publishPresence()
    }
    
    private suspend fun handleDisconnected(cause: Throwable?) {
        when (_connectionState.value) {
            ConnectionState.CONNECTED -> {
                _connectionState.value = ConnectionState.RECONNECTING
                log("MqttConnectionManager", "Connection lost, attempting to reconnect", cause)
            }
            else -> {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }
    
    private suspend fun publishPresence() {
        val currentDevice = currentDeviceProvider.get()
        val presence = MqttDeviceInfo.newBuilder()
            .setDeviceId(currentDevice.deviceId)
            .setShortDeviceId(currentDevice.shortDeviceId)
            .setDeviceName(currentDevice.deviceName)
            .setDeviceType(currentDevice.deviceType.toProto())
            .setLastSeen(System.currentTimeMillis())
            .setCapabilities(
                DeviceCapabilities.newBuilder()
                    .setMaxFileSize(applicationInfo.maxFileSize ?: 5_000_000_000L) // 5GB default
                    .setMaxConcurrentTransfers(applicationInfo.maxConcurrentTransfers ?: 5)
                    .setSupportsResume(true)
                    .setSupportsCompression(true)
                    .setMqttClientVersion("1.0.0")
                    .build()
            )
            .addAllAvailableProtocols(listOf("klardrop", "nearby_share", "mqtt_relay"))
            .build()
            
        val topic = "klardrop/presence/${currentDevice.deviceId}"
        publish(topic, presence.toByteArray(), qos = 0, retained = true)
    }
    
    private suspend fun ensureConnected() {
        if (mqttClient.state != MqttClientState.CONNECTED) {
            connect()
        }
    }
}

// Extension function to await Java CompletableFuture
private suspend fun <T> java.util.concurrent.CompletableFuture<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        whenComplete { result, exception ->
            if (exception != null) {
                cont.resumeWithException(exception)
            } else {
                cont.resume(result)
            }
        }
    }
```

### MqttDiscoveryService.kt

```kotlin
package com.carlom.klardrop.common.mqtt

import com.carlom.klardrop.common.discovery.*
import com.carlom.klardrop.common.mqtt.proto.MqttDeviceInfo
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.protobuf.ProtoBuf

class MqttDiscoveryService(
    private val connectionManager: MqttConnectionManager,
    private val visibleDevices: VisibleDevices,
    private val currentDeviceProvider: CurrentDeviceProvider,
    private val coroutines: Coroutines,
    private val protoBuf: ProtoBuf
) {
    companion object {
        private const val DISCOVERY_TOPIC = "klardrop/discovery/announce/+"
        private const val PRESENCE_TOPIC = "klardrop/presence/+"
        private const val DEVICE_TIMEOUT_MS = 30_000L // 30 seconds
    }
    
    private val scope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
    private val discoveredDevices = mutableMapOf<String, CloudDevice>()
    
    data class CloudDevice(
        val deviceInfo: DeviceInfo,
        val lastSeen: Long,
        val capabilities: DeviceCapabilities
    )
    
    suspend fun startDiscovery() {
        // Subscribe to discovery topics
        connectionManager.subscribe(DISCOVERY_TOPIC)
        connectionManager.subscribe(PRESENCE_TOPIC)
        
        // Handle incoming discovery messages
        connectionManager.incomingMessages
            .filter { it.topic.startsWith("klardrop/discovery/") || it.topic.startsWith("klardrop/presence/") }
            .onEach { handleDiscoveryMessage(it) }
            .launchIn(scope)
            
        // Periodically clean up stale devices
        scope.launch {
            while (isActive) {
                delay(10_000) // Check every 10 seconds
                cleanupStaleDevices()
            }
        }
        
        // Announce our presence
        announceDevice()
    }
    
    suspend fun stopDiscovery() {
        scope.cancel()
        connectionManager.unsubscribe(DISCOVERY_TOPIC)
        connectionManager.unsubscribe(PRESENCE_TOPIC)
    }
    
    suspend fun announceDevice() {
        val currentDevice = currentDeviceProvider.get()
        val announcement = MqttDeviceInfo.newBuilder()
            .setDeviceId(currentDevice.deviceId)
            .setShortDeviceId(currentDevice.shortDeviceId)
            .setDeviceName(currentDevice.deviceName)
            .setDeviceType(currentDevice.deviceType.toProto())
            .setLastSeen(System.currentTimeMillis())
            .build()
            
        val topic = "klardrop/discovery/announce/${currentDevice.deviceId}"
        connectionManager.publish(topic, announcement.toByteArray(), qos = 1)
        
        log("MqttDiscoveryService", "Announced device: ${currentDevice.deviceName}")
    }
    
    private suspend fun handleDiscoveryMessage(message: MqttConnectionManager.MqttMessage) {
        try {
            val deviceInfo = MqttDeviceInfo.parseFrom(message.payload)
            
            // Don't process our own announcements
            val currentDevice = currentDeviceProvider.get()
            if (deviceInfo.deviceId == currentDevice.deviceId) {
                return
            }
            
            val klardropDevice = DeviceInfo(
                deviceId = deviceInfo.deviceId,
                shortDeviceId = deviceInfo.shortDeviceId,
                deviceName = deviceInfo.deviceName,
                deviceType = deviceInfo.deviceType.toKlardrop(),
                osType = OsType.Unknown // Could be added to proto
            )
            
            val cloudDevice = CloudDevice(
                deviceInfo = klardropDevice,
                lastSeen = deviceInfo.lastSeen,
                capabilities = deviceInfo.capabilities
            )
            
            discoveredDevices[deviceInfo.deviceId] = cloudDevice
            
            // Create MQTT connection info
            val mqttConnection = DeviceConnection(
                type = DeviceConnectionType.MQTT,
                address = "mqtt:${deviceInfo.deviceId}",
                port = 0, // Not applicable for MQTT
                protocol = "mqtt"
            )
            
            // Update visible devices
            val discoveryDevice = DiscoveryDevice(
                deviceInfo = klardropDevice,
                connections = listOf(mqttConnection)
            )
            
            visibleDevices.updateDevice(deviceInfo.deviceId, discoveryDevice)
            
            log("MqttDiscoveryService", "Discovered cloud device: ${deviceInfo.deviceName}")
        } catch (e: Exception) {
            log("MqttDiscoveryService", "Failed to parse discovery message", e)
        }
    }
    
    private fun cleanupStaleDevices() {
        val now = System.currentTimeMillis()
        val staleDevices = discoveredDevices.filter { (_, device) ->
            now - device.lastSeen > DEVICE_TIMEOUT_MS
        }
        
        staleDevices.forEach { (deviceId, device) ->
            discoveredDevices.remove(deviceId)
            visibleDevices.removeDevice(deviceId)
            log("MqttDiscoveryService", "Removed stale device: ${device.deviceInfo.deviceName}")
        }
    }
    
    fun getCloudDevice(deviceId: String): CloudDevice? = discoveredDevices[deviceId]
    
    fun isCloudDevice(deviceId: String): Boolean = 
        discoveredDevices.containsKey(deviceId)
}
```

### MqttFileTransferManager.kt

```kotlin
package com.carlom.klardrop.common.mqtt

import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.file.FileManager
import com.carlom.klardrop.common.mqtt.proto.*
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.protobuf.ProtoBuf
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class MqttFileTransferManager(
    private val connectionManager: MqttConnectionManager,
    private val fileManager: FileManager,
    private val messageReceiver: MessageReceiver,
    private val coroutines: Coroutines,
    private val protoBuf: ProtoBuf
) {
    companion object {
        const val DEFAULT_CHUNK_SIZE = 65536 // 64KB
        const val MAX_CHUNK_SIZE = 262144 // 256KB
        const val MAX_PARALLEL_CHUNKS = 4
        const val CHUNK_TIMEOUT_MS = 30000L // 30 seconds
        const val TRANSFER_TTL_SECONDS = 3600 // 1 hour
    }
    
    private val scope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
    private val activeTransfers = ConcurrentHashMap<String, TransferSession>()
    
    data class TransferSession(
        val transferId: String,
        val isReceiver: Boolean,
        val files: List<FileTransferInfo>,
        val options: TransferOptions,
        val startTime: Long = System.currentTimeMillis(),
        var status: TransferStatus = TransferStatus.PENDING,
        val progressFlow: MutableStateFlow<TransferProgress>
    )
    
    data class FileTransferInfo(
        val fileId: Long,
        val path: Path,
        val size: Long,
        val totalChunks: Int,
        val receivedChunks: MutableSet<Int> = mutableSetOf(),
        val chunkChannel: Channel<FileChunk> = Channel(Channel.UNLIMITED)
    )
    
    enum class TransferStatus {
        PENDING,
        ACTIVE,
        PAUSED,
        COMPLETED,
        FAILED,
        CANCELLED
    }
    
    init {
        // Subscribe to transfer topics
        scope.launch {
            connectionManager.connectionState
                .filter { it == MqttConnectionManager.ConnectionState.CONNECTED }
                .onEach { subscribeToTransferTopics() }
                .launchIn(scope)
        }
        
        // Handle incoming messages
        connectionManager.incomingMessages
            .filter { it.topic.startsWith("klardrop/transfer/") }
            .onEach { handleTransferMessage(it) }
            .launchIn(scope)
    }
    
    suspend fun initiateTransfer(
        receiverDeviceId: String,
        files: List<Path>,
        options: TransferOptions? = null
    ): String {
        val transferId = UUID.randomUUID().toString()
        val transferOptions = options ?: TransferOptions.newBuilder()
            .setEnableCompression(true)
            .setChunkSizeBytes(DEFAULT_CHUNK_SIZE)
            .setParallelChunks(MAX_PARALLEL_CHUNKS)
            .setTtlSeconds(TRANSFER_TTL_SECONDS)
            .build()
            
        // Create file metadata
        val fileMetadataList = files.mapIndexed { index, file ->
            val fileInfo = SystemFileSystem.metadataOrNull(file)
                ?: throw IllegalArgumentException("File not found: $file")
                
            sharing.nearby.FileMetadata.newBuilder()
                .setId(index.toLong())
                .setName(file.name)
                .setSize(fileInfo.size ?: 0)
                .setMimeType(guessMimeType(file.name))
                .build()
        }
        
        // Create transfer request
        val request = CloudTransferRequest.newBuilder()
            .setTransferId(transferId)
            .setSenderDeviceId(getCurrentDeviceId())
            .setReceiverDeviceId(receiverDeviceId)
            .setTimestamp(System.currentTimeMillis())
            .addAllFiles(fileMetadataList)
            .setOptions(transferOptions)
            .build()
            
        // Publish transfer request
        val requestTopic = "klardrop/transfer/request/${getCurrentDeviceId()}/$receiverDeviceId/$transferId"
        connectionManager.publish(requestTopic, request.toByteArray(), qos = 2)
        
        // Create transfer session
        val fileTransferInfos = files.mapIndexed { index, file ->
            val size = SystemFileSystem.metadataOrNull(file)?.size ?: 0
            FileTransferInfo(
                fileId = index.toLong(),
                path = file,
                size = size,
                totalChunks = ((size + transferOptions.chunkSizeBytes - 1) / transferOptions.chunkSizeBytes).toInt()
            )
        }
        
        val session = TransferSession(
            transferId = transferId,
            isReceiver = false,
            files = fileTransferInfos,
            options = transferOptions,
            progressFlow = MutableStateFlow(createInitialProgress(transferId))
        )
        
        activeTransfers[transferId] = session
        
        // Wait for response
        val responseReceived = withTimeoutOrNull(30000) {
            waitForTransferResponse(transferId)
        }
        
        if (responseReceived == null) {
            activeTransfers.remove(transferId)
            throw TransferException("Transfer request timed out")
        }
        
        // Start sending files
        scope.launch {
            sendFiles(session)
        }
        
        return transferId
    }
    
    private suspend fun sendFiles(session: TransferSession) {
        session.status = TransferStatus.ACTIVE
        
        try {
            session.files.forEach { fileInfo ->
                sendFile(session, fileInfo)
            }
            session.status = TransferStatus.COMPLETED
        } catch (e: Exception) {
            log("MqttFileTransferManager", "Error sending files", e)
            session.status = TransferStatus.FAILED
        }
    }
    
    private suspend fun sendFile(session: TransferSession, fileInfo: FileTransferInfo) = coroutineScope {
        val file = SystemFileSystem.source(fileInfo.path)
        val chunkSize = session.options.chunkSizeBytes
        val semaphore = kotlinx.coroutines.sync.Semaphore(session.options.parallelChunks)
        
        val jobs = mutableListOf<Job>()
        var chunkIndex = 0
        
        file.use { source ->
            val buffer = ByteArray(chunkSize)
            
            while (true) {
                val bytesRead = source.readAtMostTo(buffer, 0, chunkSize)
                if (bytesRead <= 0) break
                
                val currentChunkIndex = chunkIndex++
                val chunkData = buffer.copyOf(bytesRead)
                
                val job = launch {
                    semaphore.withPermit {
                        sendChunk(session, fileInfo, currentChunkIndex, chunkData)
                    }
                }
                jobs.add(job)
            }
        }
        
        // Wait for all chunks to be sent
        jobs.joinAll()
    }
    
    private suspend fun sendChunk(
        session: TransferSession,
        fileInfo: FileTransferInfo,
        chunkIndex: Int,
        data: ByteArray
    ) {
        val chunk = FileChunk.newBuilder()
            .setTransferId(session.transferId)
            .setFileId(fileInfo.fileId)
            .setChunkIndex(chunkIndex)
            .setTotalChunks(fileInfo.totalChunks)
            .setData(ByteString.copyFrom(data))
            .setChecksum(calculateChecksum(data))
            .setIsCompressed(session.options.enableCompression)
            .setIsEncrypted(session.options.enableEncryption)
            .build()
            
        val chunkTopic = "klardrop/transfer/chunks/${getCurrentDeviceId()}/${getReceiverDeviceId(session)}/${session.transferId}/$chunkIndex"
        
        // Retry logic for chunk sending
        var retries = 0
        while (retries < 3) {
            try {
                connectionManager.publish(chunkTopic, chunk.toByteArray(), qos = 1)
                updateProgress(session, fileInfo, chunkIndex)
                break
            } catch (e: Exception) {
                retries++
                if (retries >= 3) throw e
                delay(1000L * retries) // Exponential backoff
            }
        }
    }
    
    private suspend fun handleTransferMessage(message: MqttConnectionManager.MqttMessage) {
        when {
            message.topic.contains("/request/") -> handleTransferRequest(message)
            message.topic.contains("/response/") -> handleTransferResponse(message)
            message.topic.contains("/chunks/") -> handleFileChunk(message)
            message.topic.contains("/control/") -> handleTransferControl(message)
        }
    }
    
    private suspend fun handleTransferRequest(message: MqttConnectionManager.MqttMessage) {
        try {
            val request = CloudTransferRequest.parseFrom(message.payload)
            
            // Check if we should accept this transfer
            val receiveFlow = messageReceiver.onReceiveMessage(request.senderDeviceId)
            
            // Create file transfer infos for receiving
            val fileTransferInfos = request.filesList.map { metadata ->
                val downloadPath = fileManager.getDownloadPath(metadata.name)
                FileTransferInfo(
                    fileId = metadata.id,
                    path = downloadPath,
                    size = metadata.size,
                    totalChunks = ((metadata.size + request.options.chunkSizeBytes - 1) / request.options.chunkSizeBytes).toInt()
                )
            }
            
            // Create transfer session
            val session = TransferSession(
                transferId = request.transferId,
                isReceiver = true,
                files = fileTransferInfos,
                options = request.options,
                progressFlow = MutableStateFlow(createInitialProgress(request.transferId))
            )
            
            activeTransfers[request.transferId] = session
            
            // Send response
            val response = CloudTransferResponse.newBuilder()
                .setTransferId(request.transferId)
                .setStatus(CloudTransferResponse.ResponseStatus.ACCEPTED)
                .addAllAcceptedFileIds(request.filesList.map { it.id })
                .build()
                
            val responseTopic = "klardrop/transfer/response/${getCurrentDeviceId()}/${request.senderDeviceId}/${request.transferId}"
            connectionManager.publish(responseTopic, response.toByteArray(), qos = 2)
            
            // Update receive flow
            receiveFlow.update {
                it.copy(status = ReceiveMessageStatus.Receiving(0f))
            }
            
            // Start receiving files
            scope.launch {
                receiveFiles(session, receiveFlow)
            }
            
        } catch (e: Exception) {
            log("MqttFileTransferManager", "Error handling transfer request", e)
        }
    }
    
    private suspend fun receiveFiles(
        session: TransferSession,
        receiveFlow: MutableStateFlow<com.carlom.klardrop.common.receiver.ReceiveMessage>
    ) {
        session.status = TransferStatus.ACTIVE
        
        try {
            // Create file writers
            val writers = session.files.associate { fileInfo ->
                fileInfo.fileId to SystemFileSystem.sink(fileInfo.path)
            }
            
            // Process chunks as they arrive
            session.files.forEach { fileInfo ->
                launch {
                    fileInfo.chunkChannel.consumeAsFlow().collect { chunk ->
                        writers[fileInfo.fileId]?.let { writer ->
                            writer.write(chunk.data.toByteArray())
                            fileInfo.receivedChunks.add(chunk.chunkIndex)
                            
                            // Update progress
                            val progress = fileInfo.receivedChunks.size.toFloat() / fileInfo.totalChunks
                            receiveFlow.update {
                                it.copy(status = ReceiveMessageStatus.Receiving(progress))
                            }
                        }
                    }
                }
            }
            
            // Wait for all files to complete
            while (session.files.any { it.receivedChunks.size < it.totalChunks }) {
                delay(1000)
                
                // Check for timeout
                if (System.currentTimeMillis() - session.startTime > TRANSFER_TTL_SECONDS * 1000) {
                    throw TransferException("Transfer timed out")
                }
            }
            
            // Close writers
            writers.values.forEach { it.close() }
            
            session.status = TransferStatus.COMPLETED
            receiveFlow.update {
                it.copy(status = ReceiveMessageStatus.Success(buildFileMessages(session)))
            }
            
        } catch (e: Exception) {
            log("MqttFileTransferManager", "Error receiving files", e)
            session.status = TransferStatus.FAILED
            receiveFlow.update {
                it.copy(status = ReceiveMessageStatus.Failed(e.message ?: "Transfer failed"))
            }
        }
    }
    
    private suspend fun handleFileChunk(message: MqttConnectionManager.MqttMessage) {
        try {
            val chunk = FileChunk.parseFrom(message.payload)
            val session = activeTransfers[chunk.transferId] ?: return
            
            if (!session.isReceiver) return
            
            val fileInfo = session.files.find { it.fileId == chunk.fileId } ?: return
            
            // Verify checksum
            val calculatedChecksum = calculateChecksum(chunk.data.toByteArray())
            if (calculatedChecksum != chunk.checksum) {
                log("MqttFileTransferManager", "Checksum mismatch for chunk ${chunk.chunkIndex}")
                return
            }
            
            // Send chunk to processing channel
            fileInfo.chunkChannel.send(chunk)
            
        } catch (e: Exception) {
            log("MqttFileTransferManager", "Error handling file chunk", e)
        }
    }
    
    private fun calculateChecksum(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
    
    private fun getCurrentDeviceId(): String {
        // Implementation would get from CurrentDeviceProvider
        return "current_device_id"
    }
    
    private fun getReceiverDeviceId(session: TransferSession): String {
        // Implementation would extract from session
        return "receiver_device_id"
    }
    
    private suspend fun waitForTransferResponse(transferId: String): Boolean {
        // Implementation would wait for response message
        return true
    }
    
    private fun createInitialProgress(transferId: String): TransferProgress {
        return TransferProgress.newBuilder()
            .setTransferId(transferId)
            .setBytesTransferred(0)
            .setTotalBytes(0)
            .setTransferSpeedMbps(0f)
            .setChunksCompleted(0)
            .setTotalChunks(0)
            .setEtaSeconds(0)
            .build()
    }
    
    private fun updateProgress(session: TransferSession, fileInfo: FileTransferInfo, chunkIndex: Int) {
        // Update progress tracking
    }
    
    private fun buildFileMessages(session: TransferSession): List<FileMessage> {
        // Convert received files to FileMessage format
        return emptyList()
    }
    
    private fun guessMimeType(filename: String): String {
        return when (filename.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }
    
    private suspend fun subscribeToTransferTopics() {
        val deviceId = getCurrentDeviceId()
        
        // Subscribe to transfers directed to this device
        connectionManager.subscribe("klardrop/transfer/request/+/$deviceId/+", qos = 2)
        connectionManager.subscribe("klardrop/transfer/response/$deviceId/+/+", qos = 2)
        connectionManager.subscribe("klardrop/transfer/chunks/+/$deviceId/+/+", qos = 1)
        connectionManager.subscribe("klardrop/transfer/control/+", qos = 2)
    }
}

class TransferException(message: String) : Exception(message)
```

## Integration Examples

### Extended Klardrop.kt

```kotlin
// Add to Klardrop.kt init() method
class Klardrop(
    // existing parameters...
) {
    fun init() {
        // Existing initialization...
        
        // Initialize MQTT components if enabled
        if (applicationInfo.enableMqttCloud) {
            appScope.launch(commonComponent.coroutines().ioDispatcher) {
                try {
                    val mqttManager = commonComponent.mqttConnectionManager()
                    mqttManager.initialize()
                    mqttManager.connect()
                    
                    // Start MQTT discovery
                    val mqttDiscovery = commonComponent.mqttDiscoveryService()
                    mqttDiscovery.startDiscovery()
                    
                    log("Klardrop", "MQTT cloud services initialized")
                } catch (e: Exception) {
                    log("Klardrop", "Failed to initialize MQTT services", e)
                }
            }
        }
    }
}
```

### Dependency Injection Setup

```kotlin
// Add to CommonComponent
@Component(modules = [
    // existing modules...
    MqttModule::class
])
interface CommonComponent {
    // existing methods...
    
    fun mqttConnectionManager(): MqttConnectionManager
    fun mqttDiscoveryService(): MqttDiscoveryService
    fun mqttFileTransferManager(): MqttFileTransferManager
}

@Module
class MqttModule {
    @Provides
    @Singleton
    fun provideMqttConnectionManager(
        applicationInfo: ApplicationInfo,
        coroutines: Coroutines,
        currentDeviceProvider: CurrentDeviceProvider
    ): MqttConnectionManager {
        return MqttConnectionManager(applicationInfo, coroutines, currentDeviceProvider)
    }
    
    @Provides
    @Singleton
    fun provideMqttDiscoveryService(
        connectionManager: MqttConnectionManager,
        visibleDevices: VisibleDevices,
        currentDeviceProvider: CurrentDeviceProvider,
        coroutines: Coroutines,
        protoBuf: ProtoBuf
    ): MqttDiscoveryService {
        return MqttDiscoveryService(
            connectionManager,
            visibleDevices,
            currentDeviceProvider,
            coroutines,
            protoBuf
        )
    }
    
    @Provides
    @Singleton
    fun provideMqttFileTransferManager(
        connectionManager: MqttConnectionManager,
        fileManager: FileManager,
        messageReceiver: MessageReceiver,
        coroutines: Coroutines,
        protoBuf: ProtoBuf
    ): MqttFileTransferManager {
        return MqttFileTransferManager(
            connectionManager,
            fileManager,
            messageReceiver,
            coroutines,
            protoBuf
        )
    }
}
```

## Testing Strategy

### Unit Tests

```kotlin
class MqttConnectionManagerTest {
    @Test
    fun `test connection lifecycle`() = runTest {
        val manager = createTestManager()
        
        manager.initialize()
        assertEquals(ConnectionState.DISCONNECTED, manager.connectionState.value)
        
        manager.connect()
        assertEquals(ConnectionState.CONNECTED, manager.connectionState.value)
        
        manager.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, manager.connectionState.value)
    }
    
    @Test
    fun `test message publishing`() = runTest {
        val manager = createTestManager()
        manager.initialize()
        manager.connect()
        
        val testPayload = "test message".toByteArray()
        manager.publish("test/topic", testPayload, qos = 1)
        
        // Verify message was published
    }
}
```

### Integration Tests

```kotlin
class MqttFileTransferIntegrationTest {
    @Test
    fun `test file transfer between devices`() = runTest {
        // Set up two devices
        val sender = createSenderDevice()
        val receiver = createReceiverDevice()
        
        // Create test file
        val testFile = createTestFile("test.txt", "Hello MQTT!")
        
        // Initiate transfer
        val transferId = sender.mqttTransferManager.initiateTransfer(
            receiverDeviceId = receiver.deviceId,
            files = listOf(testFile.path)
        )
        
        // Wait for transfer completion
        delay(5000)
        
        // Verify file received
        val receivedFile = receiver.fileManager.getDownloadPath("test.txt")
        assertTrue(SystemFileSystem.exists(receivedFile))
        assertEquals("Hello MQTT!", SystemFileSystem.source(receivedFile).readString())
    }
}
```

This implementation guide provides a complete foundation for integrating MQTT-based cloud file transfer into Klardrop while maintaining compatibility with the existing architecture.