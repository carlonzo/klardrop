package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.trust.crypto.CryptoProvider
import com.carlom.klardrop.common.trust.crypto.CryptoProviderImpl
import com.carlom.klardrop.common.database.AppDatabase
import com.carlom.klardrop.common.trust.model.*
import com.carlom.klardrop.common.trust.crypto.EncryptedPayload
import com.carlom.klardrop.common.trust.protocol.TrustEvent
import com.carlom.klardrop.common.trust.protocol.TrustProtocolHandler
import com.carlom.klardrop.common.trust.protocol.TrustProtocolHandlerStub
import com.carlom.klardrop.common.trust.storage.SecureKeyStorageFactory
import com.carlom.klardrop.common.trust.storage.TrustStore
import com.carlom.klardrop.common.trust.storage.TrustStoreImpl
import com.carlom.klardrop.protos.trust.TrustMessage as ProtoTrustMessage
import com.carlom.klardrop.protos.trust.TrustLevel
import com.carlom.klardrop.protos.trust.Permission
import com.carlom.klardrop.protos.trust.DeviceType as ProtoDeviceType
import com.carlom.klardrop.protos.trust.TrustMessageType
import com.carlom.klardrop.protos.trust.ECDHInitiation
import com.carlom.klardrop.protos.trust.ECDHResponse
import com.carlom.klardrop.protos.trust.GroupInvitation
import com.carlom.klardrop.protos.trust.JoinConfirmation
import com.carlom.klardrop.protos.trust.MemberUpdate
import com.carlom.klardrop.protos.trust.ClipboardSync
import com.carlom.klardrop.protos.trust.DiscoveryAnnouncement
import com.carlom.klardrop.protos.trust.UpdateAction
import com.carlom.klardrop.common.communication.message.TrustMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.DeviceType
import io.ktor.utils.io.core.toByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * TrustManager - Central coordinator for all trust-related functionality
 * 
 * This class manages:
 * - Device identity and keypair generation
 * - Trust group creation and management
 * - Device pairing and trust relationships
 * - Protocol message handling
 * - Clipboard synchronization
 * - Security event logging
 */
class TrustManager(
    private val database: AppDatabase,
    private val secureKeyStorageFactory: SecureKeyStorageFactory,
    private val deviceName: String,
    private val deviceType: DeviceType,
    private val scope: CoroutineScope,
    private val sendTrustMessage: suspend (deviceId: String, message: ProtoTrustMessage) -> Unit
) {
    
    private val secureKeyStorage = secureKeyStorageFactory.create()
    private val cryptoProvider: CryptoProvider = CryptoProviderImpl()
    val trustStore: TrustStore = TrustStoreImpl(database, secureKeyStorage)
    
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    
    private val _currentDeviceKeypair = MutableStateFlow<DeviceKeypair?>(null)
    val currentDeviceKeypair: StateFlow<DeviceKeypair?> = _currentDeviceKeypair.asStateFlow()
    
    private val _currentTrustGroup = MutableStateFlow<TrustGroup?>(null)
    val currentTrustGroup: StateFlow<TrustGroup?> = _currentTrustGroup.asStateFlow()
    
    private val _trustedDevices = MutableStateFlow<List<TrustedDevice>>(emptyList())
    val trustedDevices: StateFlow<List<TrustedDevice>> = _trustedDevices.asStateFlow()
    
    lateinit var protocolHandler: TrustProtocolHandler
    
    private val proto = ProtoBuf { }
    
    init {
        scope.launch {
            initialize()
        }
    }
    
    /**
     * Initialize the trust system
     */
    private suspend fun initialize() {
        // Load or create device keypair
        var keypair = trustStore.getDeviceKeypair()
        if (keypair == null) {
            keypair = createDeviceKeypair()
            trustStore.saveDeviceKeypair(keypair)
        }
        _currentDeviceKeypair.value = keypair
        
        // Load trust group if exists
        val group = trustStore.getTrustGroup()
        _currentTrustGroup.value = group
        
        // Load trusted devices
        if (group != null) {
            _trustedDevices.value = trustStore.getTrustedDevices()
        }
        
        // Initialize protocol handler
        protocolHandler = TrustProtocolHandlerStub(
            trustStore = trustStore,
            cryptoProvider = cryptoProvider,
            deviceInfo = { keypair },
            sendMessage = sendTrustMessage
        )
        
        // Listen to trust events
        scope.launch {
            protocolHandler.getTrustEvents().collect { event ->
                handleTrustEvent(event)
            }
        }
        
        // Start periodic cleanup
        startPeriodicCleanup()
        
        _isInitialized.value = true
    }
    
    /**
     * Create a new device keypair
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun createDeviceKeypair(): DeviceKeypair {
        val deviceId = Uuid.random().toString()
        val ecdsaKeyPair = cryptoProvider.generateECDSAKeypair()
        
        return DeviceKeypair(
            deviceId = deviceId,
            publicKey = ecdsaKeyPair.publicKey,
            privateKey = ecdsaKeyPair.privateKey,
            deviceName = deviceName,
            deviceType = mapDeviceType(deviceType)
        )
    }
    
    /**
     * Create a new trust group
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun createTrustGroup(groupName: String? = null): TrustGroup {
        val groupId = Uuid.random().toString()
        val groupKey = cryptoProvider.generateAESKey()
        val device = _currentDeviceKeypair.value ?: throw IllegalStateException("Device not initialized")
        
        val trustGroup = TrustGroup(
            groupId = groupId,
            groupKey = groupKey,
            groupName = groupName,
            devices = mapOf(
                device.deviceId to TrustedDevice(
                    deviceId = device.deviceId,
                    groupId = groupId,
                    publicKey = device.publicKey,
                    deviceName = device.deviceName,
                    deviceType = device.deviceType,
                    addedAt = Clock().currentTimeMillis(),
                    addedBy = device.deviceId
                )
            ),
            createdAt = Clock().currentTimeMillis(),
            updatedAt = Clock().currentTimeMillis()
        )
        
        trustStore.saveTrustGroup(trustGroup)
        _currentTrustGroup.value = trustGroup
        _trustedDevices.value = trustGroup.devices.values.toList()
        
        return trustGroup
    }
    
    /**
     * Get or create a trust group
     */
    suspend fun getOrCreateTrustGroup(): TrustGroup {
        return _currentTrustGroup.value ?: createTrustGroup()
    }
    
    /**
     * Update device name
     */
    suspend fun updateDeviceName(newName: String) {
        trustStore.updateDeviceName(newName)
        _currentDeviceKeypair.value = _currentDeviceKeypair.value?.copy(deviceName = newName)
    }
    
    /**
     * Get discovery announcement for mDNS
     */
    suspend fun getDiscoveryAnnouncement(): DiscoveryAnnouncement {
        return protocolHandler.createDiscoveryAnnouncement()
    }
    
    /**
     * Handle incoming discovery announcement
     */
    suspend fun handleDiscoveryAnnouncement(announcement: DiscoveryAnnouncement, senderAddress: String) {
        protocolHandler.handleDiscoveryAnnouncement(announcement, senderAddress)
    }
    
    /**
     * Initiate pairing with a device
     */
    suspend fun initiatePairing(deviceId: String): String {
        // Ensure we have a trust group
        getOrCreateTrustGroup()
        return protocolHandler.initiatePairing(deviceId)
    }
    
    /**
     * Handle incoming trust protocol message
     */
    suspend fun handleTrustMessage(message: ProtoTrustMessage, senderAddress: String) {
        when (message.type) {
            TrustMessageType.MESSAGE_TYPE_ECDH_INITIATION -> {
                val initiation = ECDHInitiation.ADAPTER.decode(message.payload)
                protocolHandler.handleECDHInitiation(initiation, senderAddress)
            }
            TrustMessageType.MESSAGE_TYPE_ECDH_RESPONSE -> {
                val response = ECDHResponse.ADAPTER.decode(message.payload)
                protocolHandler.handleECDHResponse(response)
            }
            TrustMessageType.MESSAGE_TYPE_GROUP_INVITATION -> {
                val invitation = GroupInvitation.ADAPTER.decode(message.payload)
                protocolHandler.handleGroupInvitation(invitation)
            }
            TrustMessageType.MESSAGE_TYPE_JOIN_CONFIRMATION -> {
                val confirmation = JoinConfirmation.ADAPTER.decode(message.payload)
                protocolHandler.handleJoinConfirmation(confirmation)
            }
            TrustMessageType.MESSAGE_TYPE_MEMBER_UPDATE -> {
                val update = MemberUpdate.ADAPTER.decode(message.payload)
                protocolHandler.handleMemberUpdate(update)
            }
            TrustMessageType.MESSAGE_TYPE_CLIPBOARD_SYNC -> {
                val sync = ClipboardSync.ADAPTER.decode(message.payload)
                protocolHandler.handleClipboardSync(sync)
            }
            else -> {
                // Unknown message type
            }
        }
    }
    
    /**
     * Check if a device is trusted
     */
    suspend fun isDeviceTrusted(deviceId: String): Boolean {
        return trustStore.isDeviceTrusted(deviceId)
    }
    
    /**
     * Get device trust level
     */
    suspend fun getDeviceTrustLevel(deviceId: String): TrustLevel? {
        return trustStore.getDeviceTrustLevel(deviceId)
    }
    
    /**
     * Remove a trusted device
     */
    suspend fun removeTrustedDevice(deviceId: String) {
        val device = trustStore.getTrustedDevice(deviceId) ?: return
        trustStore.removeTrustedDevice(deviceId)
        
        // Broadcast update to other devices
        protocolHandler.broadcastMemberUpdate(UpdateAction.UPDATE_ACTION_REMOVE, device)
        
        // Rotate group key for security
        rotateGroupKey()
        
        // Update local state
        _trustedDevices.value = trustStore.getTrustedDevices()
    }
    
    /**
     * Sync clipboard content
     */
    suspend fun syncClipboard(content: String) {
        if (_currentTrustGroup.value == null) return
        protocolHandler.broadcastClipboardUpdate(content)
    }
    
    /**
     * Get latest clipboard entry
     */
    suspend fun getLatestClipboardEntry(): ClipboardEntry? {
        return trustStore.getLatestClipboardEntry()
    }
    
    /**
     * Get trust events flow
     */
    fun getTrustEvents(): Flow<TrustEvent> {
        return protocolHandler.getTrustEvents()
    }
    
    /**
     * Get security events
     */
    suspend fun getSecurityEvents(limit: Int = 100): List<SecurityEvent> {
        return trustStore.getRecentSecurityEvents(limit)
    }
    
    /**
     * Log a security event
     */
    suspend fun logSecurityEvent(event: SecurityEvent) {
        trustStore.logSecurityEvent(event)
    }
    
    /**
     * Handle trust events from protocol handler
     */
    private suspend fun handleTrustEvent(event: TrustEvent) {
        when (event) {
            is TrustEvent.DeviceJoined -> {
                _trustedDevices.value = trustStore.getTrustedDevices()
                _currentTrustGroup.value = trustStore.getTrustGroup()
            }
            is TrustEvent.DeviceRemoved -> {
                _trustedDevices.value = trustStore.getTrustedDevices()
                _currentTrustGroup.value = trustStore.getTrustGroup()
            }
            is TrustEvent.DeviceUpdated -> {
                _trustedDevices.value = trustStore.getTrustedDevices()
            }
            else -> {
                // Other events are passed through to UI
            }
        }
    }
    
    /**
     * Rotate the group key for security
     */
    private suspend fun rotateGroupKey() {
        val group = _currentTrustGroup.value ?: return
        val newKey = cryptoProvider.generateAESKey()
        
        trustStore.updateGroupKey(group.groupId, newKey)
        _currentTrustGroup.value = group.copy(
            groupKey = newKey,
            updatedAt = Clock().currentTimeMillis()
        )
        
        // Notify all trusted devices about key rotation
        val device = _currentDeviceKeypair.value ?: return
        val trustedDevice = TrustedDevice(
            deviceId = device.deviceId,
            groupId = group.groupId,
            publicKey = device.publicKey,
            deviceName = device.deviceName,
            deviceType = device.deviceType,
            addedAt = Clock().currentTimeMillis(),
            addedBy = device.deviceId
        )
        
        protocolHandler.broadcastMemberUpdate(UpdateAction.UPDATE_ACTION_UPDATE, trustedDevice)
    }
    
    /**
     * Start periodic cleanup tasks
     */
    private fun startPeriodicCleanup() {
        scope.launch {
            // Run cleanup every hour
            while (true) {
                kotlinx.coroutines.delay(60 * 60 * 1000) // 1 hour
                
                try {
                    trustStore.cleanExpiredPairingSessions()
                    trustStore.cleanupExpiredDevices()
                    trustStore.cleanupOldSecurityEvents()
                    trustStore.cleanupOldClipboardEntries()
                } catch (e: Exception) {
                    // Log error but don't crash
                }
            }
        }
    }
    
    /**
     * Export trust group for backup
     */
    suspend fun exportTrustGroup(password: String): ByteArray {
        val group = _currentTrustGroup.value ?: throw IllegalStateException("No trust group to export")
        val keypair = _currentDeviceKeypair.value ?: throw IllegalStateException("Device not initialized")
        
        // Create export data
        val exportData = TrustExportData(
            version = 1,
            exportDate = Clock().currentTimeMillis(),
            deviceKeypair = keypair,
            trustGroup = group,
            trustedDevices = _trustedDevices.value
        )
        
        // Serialize and encrypt
        val serialized = proto.encodeToByteArray(
            TrustExportData.serializer(),
            exportData
        )
        
        // Derive key from password
        val salt = cryptoProvider.generateRandomBytes(16)
        val key = cryptoProvider.deriveKey(
            secret = password.toByteArray(),
            salt = salt,
            info = "klardrop-trust-export".toByteArray()
        )
        
        val encrypted = cryptoProvider.encryptAESGCM(serialized, key)
        
        // Combine salt + encrypted data
        return salt + encrypted.ciphertext + encrypted.nonce + encrypted.tag
    }
    
    /**
     * Import trust group from backup
     */
    suspend fun importTrustGroup(data: ByteArray, password: String) {
        // Extract components
        val salt = data.sliceArray(0 until 16)
        val remainder = data.sliceArray(16 until data.size)
        
        // Derive key from password
        val key = cryptoProvider.deriveKey(
            secret = password.toByteArray(),
            salt = salt,
            info = "klardrop-trust-export".toByteArray()
        )
        
        // Decrypt
        val nonce = remainder.sliceArray(remainder.size - 28 until remainder.size - 16)
        val tag = remainder.sliceArray(remainder.size - 16 until remainder.size)
        val ciphertext = remainder.sliceArray(0 until remainder.size - 28)
        
        val decrypted = cryptoProvider.decryptAESGCM(
            EncryptedPayload(ciphertext, nonce, tag),
            key
        )
        
        // Deserialize
        val exportData = proto.decodeFromByteArray<TrustExportData>(decrypted)
        
        // Import data
        trustStore.saveDeviceKeypair(exportData.deviceKeypair)
        trustStore.saveTrustGroup(exportData.trustGroup)
        
        // Update state
        _currentDeviceKeypair.value = exportData.deviceKeypair
        _currentTrustGroup.value = exportData.trustGroup
        _trustedDevices.value = exportData.trustedDevices
    }
    
    private fun mapDeviceType(deviceType: com.carlom.klardrop.common.utils.DeviceType): ProtoDeviceType {
        return when (deviceType) {
            com.carlom.klardrop.common.utils.DeviceType.MOBILE -> ProtoDeviceType.DEVICE_TYPE_ANDROID
            com.carlom.klardrop.common.utils.DeviceType.DESKTOP -> ProtoDeviceType.DEVICE_TYPE_LINUX
            com.carlom.klardrop.common.utils.DeviceType.UNKNOWN -> ProtoDeviceType.DEVICE_TYPE_UNKNOWN
        }
    }
}

/**
 * Data class for trust export/import
 */

@Serializable
private data class TrustExportData(
    val version: Int,
    val exportDate: Long,
    val deviceKeypair: DeviceKeypair,
    val trustGroup: TrustGroup,
    val trustedDevices: List<TrustedDevice>
)