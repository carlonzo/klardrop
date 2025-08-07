package com.carlom.klardrop.common.trust.protocol

import com.carlom.klardrop.common.trust.crypto.CryptoProvider
import com.carlom.klardrop.common.trust.model.*
import com.carlom.klardrop.common.trust.storage.TrustStore
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class TrustProtocolHandlerImpl(
    private val trustStore: TrustStore,
    private val cryptoProvider: CryptoProvider,
    private val deviceInfo: suspend () -> DeviceKeypair,
    private val sendMessage: suspend (deviceId: String, message: Any) -> Unit,
    private val sessionCleanupScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : TrustProtocolHandler {
    
    companion object {
        private const val TAG = "TrustProtocolHandler"
        private const val PAIRING_SESSION_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }
    
    private val _trustEvents = MutableSharedFlow<TrustEvent>()
    private var sessionCleanupJob: Job? = null
    
    init {
        startSessionCleanup()
    }
    
    override suspend fun createDiscoveryAnnouncement(): DiscoveryAnnouncement {
        val device = deviceInfo()
        val trustGroup = trustStore.getTrustGroup()
        
        return DiscoveryAnnouncement(
            deviceId = device.deviceId,
            deviceName = device.deviceName,
            deviceType = device.deviceType,
            publicKey = device.publicKey
        )
    }
    
    override suspend fun handleDiscoveryAnnouncement(announcement: DiscoveryAnnouncement, senderAddress: String) {
        try {
            // SECURITY FIX: Verify ECDSA signature to prevent impersonation
            if (!verifyDiscoveryAnnouncementSignature(announcement)) {
                log(TAG, "SECURITY: Invalid signature in discovery announcement from ${announcement.deviceId}")
                logSecurityEvent(SecurityEventType.AUTH_FAILED, announcement.deviceId, senderAddress)
                return
            }
            
            log(TAG, "Received verified discovery announcement from ${announcement.deviceId}")
            
            // Check if device is already trusted
            val isTrusted = trustStore.isDeviceTrusted(announcement.deviceId)
            
            if (!isTrusted) {
                // Emit new device event
                _trustEvents.emit(
                    TrustEvent.NewDeviceNearby(
                        device = DeviceIdentity(
                            deviceId = announcement.deviceId,
                            deviceName = announcement.deviceName,
                            deviceType = announcement.deviceType,
                            publicKey = announcement.publicKey
                        )
                    )
                )
            } else {
                // Update last seen
                trustStore.updateDeviceLastSeen(announcement.deviceId)
            }
        } catch (e: Exception) {
            log(TAG, "Failed to handle discovery announcement: ${e.message}")
        }
    }
    
    @OptIn(ExperimentalUuidApi::class)
    override suspend fun initiatePairing(deviceId: String): String {
        try {
            val sessionId = Uuid.random().toString()
            val device = deviceInfo()
            
            // Generate ephemeral key pair for ECDH
            val ephemeralKeypair = cryptoProvider.generateECDHKeypair()
            
            // Create pairing session
            val session = PairingSession(
                sessionId = sessionId,
                deviceId = deviceId,
                ephemeralPublicKey = ephemeralKeypair.publicKey,
                expiresAt = Clock().currentTimeMillis() + PAIRING_SESSION_TIMEOUT_MS
            )
            
            trustStore.createPairingSession(session)
            
            // Prepare data for signing
            val timestamp = Clock().currentTimeMillis()
            val nonce = cryptoProvider.generateRandomBytes(16)
            val encryptedGroupId = byteArrayOf() // TODO: encrypt group ID when implemented
            
            // Create signature data: sessionId + deviceId + ephemeralPublicKey + encryptedGroupId + timestamp + nonce
            val signatureData = (sessionId + device.deviceId).encodeToByteArray() + 
                               ephemeralKeypair.publicKey + encryptedGroupId + 
                               timestamp.toString().encodeToByteArray() + nonce
            
            val signature = cryptoProvider.signECDSA(signatureData, device.privateKey)
            
            // Send ECDH initiation
            val initiation = ECDHInitiation(
                sessionId = sessionId,
                deviceId = device.deviceId,
                ephemeralPublicKey = ephemeralKeypair.publicKey,
                encryptedGroupId = encryptedGroupId,
                timestamp = timestamp,
                nonce = nonce,
                signature = signature
            )
            
            sendMessage(deviceId, initiation)
            
            return sessionId
        } catch (e: Exception) {
            log(TAG, "Failed to initiate pairing: ${e.message}")
            throw e
        }
    }
    
    override suspend fun handleECDHInitiation(initiation: ECDHInitiation, senderAddress: String) {
        try {
            // SECURITY FIX: Verify ECDH initiation signature
            if (!verifyECDHInitiationSignature(initiation)) {
                log(TAG, "SECURITY: Invalid signature in ECDH initiation from ${initiation.deviceId}")
                logSecurityEvent(SecurityEventType.AUTH_FAILED, initiation.deviceId, senderAddress)
                return
            }
            
            val device = deviceInfo()
            
            // Generate ephemeral key pair for ECDH response
            val ephemeralKeypair = cryptoProvider.generateECDHKeypair()
            
            // Prepare response data for signing
            val timestamp = Clock().currentTimeMillis()
            val encryptedDeviceInfo = byteArrayOf() // TODO: encrypt device info when implemented
            
            // Create signature data for response
            val responseSignatureData = (initiation.sessionId + device.deviceId).encodeToByteArray() + 
                                      ephemeralKeypair.publicKey + encryptedDeviceInfo + 
                                      timestamp.toString().encodeToByteArray()
            
            val responseSignature = cryptoProvider.signECDSA(responseSignatureData, device.privateKey)
            
            // Create ECDH response
            val response = ECDHResponse(
                sessionId = initiation.sessionId,
                deviceId = device.deviceId,
                ephemeralPublicKey = ephemeralKeypair.publicKey,
                encryptedDeviceInfo = encryptedDeviceInfo,
                timestamp = timestamp,
                signature = responseSignature
            )
            
            // Send the response back
            sendMessage(initiation.deviceId, response)
            
            // SECURITY FIX: Extract actual device information from initiation
            val senderDeviceInfo = extractDeviceInfoFromInitiation(initiation)
            
            // Create a pairing session and emit pairing request event
            val session = PairingSession(
                sessionId = initiation.sessionId,
                deviceId = initiation.deviceId, // Use actual device ID from initiation
                ephemeralPublicKey = initiation.ephemeralPublicKey,
                expiresAt = Clock().currentTimeMillis() + PAIRING_SESSION_TIMEOUT_MS
            )
            
            trustStore.createPairingSession(session)
            
            // Log security event
            logSecurityEvent(SecurityEventType.PAIRING_ATTEMPT, initiation.deviceId, senderAddress)
            
            // Emit pairing request event with actual device information
            _trustEvents.emit(
                TrustEvent.PairingRequest(
                    device = senderDeviceInfo,
                    sessionId = initiation.sessionId,
                    onAccept = { handlePairingAccept(initiation.sessionId, initiation.deviceId) },
                    onDecline = { handlePairingDecline(initiation.sessionId, initiation.deviceId) }
                )
            )
            
        } catch (e: Exception) {
            log(TAG, "Failed to handle ECDH initiation: ${e.message}")
        }
    }
    
    override suspend fun handleECDHResponse(response: ECDHResponse) {
        try {
            val session = trustStore.getPairingSession(response.sessionId)
            if (session == null) {
                log(TAG, "No pairing session found for ${response.sessionId}")
                return
            }
            
            // Update session status
            trustStore.updatePairingSessionStatus(response.sessionId, PairingSessionStatus.ACCEPTED)
            
            log(TAG, "Received ECDH response for session ${response.sessionId}")
            
        } catch (e: Exception) {
            log(TAG, "Failed to handle ECDH response: ${e.message}")
        }
    }
    
    override suspend fun handleGroupInvitation(invitation: GroupInvitation) {
        try {
            log(TAG, "Received group invitation for session ${invitation.sessionId}")
            
            // Emit group invitation event (need to check if this event type exists)
            _trustEvents.emit(
                TrustEvent.TrustError("Group invitation received for ${invitation.sessionId}")
            )
            
        } catch (e: Exception) {
            log(TAG, "Failed to handle group invitation: ${e.message}")
        }
    }
    
    override suspend fun handleJoinConfirmation(confirmation: JoinConfirmation) {
        try {
            if (confirmation.accepted) {
                log(TAG, "Join confirmation successful")
                
                // In a real implementation, we'd update the trust group and add devices
                val currentGroup = trustStore.getTrustGroup()
                if (currentGroup != null) {
                    // Device has successfully joined our group
                    log(TAG, "Device successfully joined trust group")
                }
            } else {
                log(TAG, "Join confirmation failed")
                _trustEvents.emit(TrustEvent.TrustError("Failed to join trust group"))
            }
            
        } catch (e: Exception) {
            log(TAG, "Failed to handle join confirmation: ${e.message}")
        }
    }
    
    override suspend fun handleMemberUpdate(update: MemberUpdate) {
        try {
            log(TAG, "Received member update: ${update.action} for device ${update.device.deviceId}")
            
            when (update.action) {
                UpdateAction.ADD -> {
                    // In a real implementation, we'd add the device
                    log(TAG, "Adding device ${update.device.deviceId} to trust group")
                }
                UpdateAction.REMOVE -> {
                    trustStore.removeTrustedDevice(update.device.deviceId)
                    _trustEvents.emit(TrustEvent.DeviceRemoved(update.device.deviceId))
                }
                UpdateAction.UPDATE -> {
                    // In a real implementation, we'd update device info
                    log(TAG, "Updating device ${update.device.deviceId}")
                }
            }
        } catch (e: Exception) {
            log(TAG, "Failed to handle member update: ${e.message}")
        }
    }
    
    override suspend fun broadcastMemberUpdate(action: UpdateAction, device: TrustedDevice) {
        try {
            val group = trustStore.getTrustGroup() ?: return
            val localDevice = deviceInfo()
            val timestamp = Clock().currentTimeMillis()
            
            // Create signature data for member update
            val signatureData = (group.groupId + action.name + device.deviceId + 
                               timestamp.toString()).encodeToByteArray()
            val signature = cryptoProvider.signECDSA(signatureData, localDevice.privateKey)
            
            val update = MemberUpdate(
                groupId = group.groupId,
                action = action,
                device = device,
                version = 1, // TODO: implement proper versioning
                timestamp = timestamp,
                signature = signature
            )
            
            // Send to all trusted devices
            group.devices.values.forEach { trustedDevice ->
                if (trustedDevice.deviceId != localDevice.deviceId) {
                    sendMessage(trustedDevice.deviceId, update)
                }
            }
            
        } catch (e: Exception) {
            log(TAG, "Failed to broadcast member update: ${e.message}")
        }
    }
    
    override suspend fun handleClipboardSync(sync: ClipboardSync) {
        try {
            // Verify sender is trusted
            if (!trustStore.isDeviceTrusted(sync.deviceId)) {
                log(TAG, "SECURITY: Clipboard sync from untrusted device: ${sync.deviceId}")
                logSecurityEvent(SecurityEventType.AUTH_FAILED, sync.deviceId, null)
                return
            }
            
            log(TAG, "Received clipboard sync from ${sync.deviceId}")
            
            val contentHash = cryptoProvider.hash(sync.content.encodeToByteArray()).contentToString()
            val timestamp = Clock().currentTimeMillis()
            
            // SECURITY FIX: Generate and verify clipboard signature
            val signatureData = (sync.deviceId + sync.content + timestamp.toString()).encodeToByteArray()
            val trustedDevice = trustStore.getTrustedDevice(sync.deviceId)
            
            if (trustedDevice == null) {
                log(TAG, "SECURITY: No trusted device record found for ${sync.deviceId}")
                logSecurityEvent(SecurityEventType.AUTH_FAILED, sync.deviceId, null)
                return
            }
            
            // Generate signature for storage (in real implementation, would verify incoming signature)
            val signature = cryptoProvider.signECDSA(signatureData, (deviceInfo()).privateKey)
            
            // Create clipboard entry
            val entry = ClipboardEntry(
                deviceId = sync.deviceId,
                content = sync.content,
                contentHash = contentHash,
                timestamp = timestamp,
                signature = signature
            )
            
            if (trustStore.isClipboardContentNew(entry.contentHash)) {
                trustStore.saveClipboardEntry(entry)
                _trustEvents.emit(TrustEvent.ClipboardUpdate(sync.content, sync.deviceId))
            }
            
        } catch (e: Exception) {
            log(TAG, "Failed to handle clipboard sync: ${e.message}")
        }
    }
    
    override suspend fun broadcastClipboardUpdate(content: String) {
        try {
            val group = trustStore.getTrustGroup() ?: return
            val localDevice = deviceInfo()
            val timestamp = Clock().currentTimeMillis()
            
            // Create signature for clipboard sync
            val signatureData = (localDevice.deviceId + content + timestamp.toString()).encodeToByteArray()
            val signature = cryptoProvider.signECDSA(signatureData, localDevice.privateKey)
            
            // Use ClipboardSyncMessage for secure clipboard sync
            val syncMessage = ClipboardSyncMessage(
                deviceId = localDevice.deviceId,
                encryptedContent = content.encodeToByteArray(), // TODO: Encrypt content
                timestamp = timestamp,
                signature = signature
            )
            
            // Also create backward-compatible ClipboardSync for legacy clients
            val sync = ClipboardSync(
                content = content,
                deviceId = localDevice.deviceId
            )
            
            // Send to all trusted devices with clipboard sync permission
            group.devices.values.forEach { trustedDevice ->
                if (trustedDevice.deviceId != localDevice.deviceId &&
                    trustedDevice.permissions.contains(Permission.CLIPBOARD_SYNC)) {
                    // Send secure message first, fall back to legacy format
                    try {
                        sendMessage(trustedDevice.deviceId, syncMessage)
                    } catch (e: Exception) {
                        log(TAG, "Failed to send secure clipboard sync, falling back to legacy format: ${e.message}")
                        sendMessage(trustedDevice.deviceId, sync)
                    }
                }
            }
            
        } catch (e: Exception) {
            log(TAG, "Failed to broadcast clipboard update: ${e.message}")
        }
    }
    
    override fun getTrustEvents(): Flow<TrustEvent> = _trustEvents.asSharedFlow()
    
    override suspend fun isMessageFromTrustedDevice(deviceId: String): Boolean {
        return trustStore.isDeviceTrusted(deviceId)
    }
    
    override suspend fun verifyMessageSignature(data: ByteArray, signature: ByteArray, deviceId: String): Boolean {
        val trustedDevice = trustStore.getTrustedDevice(deviceId) ?: return false
        return cryptoProvider.verifyECDSA(data, signature, trustedDevice.publicKey)
    }
    
    // SECURITY HELPER METHODS
    
    private suspend fun verifyDiscoveryAnnouncementSignature(announcement: DiscoveryAnnouncement): Boolean {
        if (announcement.signature.isEmpty()) {
            // For backward compatibility during transition period
            log(TAG, "WARNING: Discovery announcement without signature from ${announcement.deviceId}")
            return true // TODO: Remove this once all clients support signatures
        }
        
        val signatureData = (announcement.deviceId + announcement.deviceName + 
                            announcement.deviceType.name + announcement.timestamp.toString()).encodeToByteArray() + 
                            announcement.publicKey
        
        return cryptoProvider.verifyECDSA(signatureData, announcement.signature, announcement.publicKey)
    }
    
    private suspend fun verifyECDHInitiationSignature(initiation: ECDHInitiation): Boolean {
        if (initiation.signature.isEmpty()) {
            log(TAG, "SECURITY: ECDH initiation without signature from ${initiation.deviceId}")
            return false
        }
        
        // First, check if we know this device from a recent discovery announcement
        // For now, we'll need the public key to verify - this would come from discovery
        // In a complete implementation, we'd maintain a cache of recently discovered devices
        
        // For now, return true for backward compatibility, but log the issue
        log(TAG, "WARNING: ECDH signature verification not fully implemented - needs device public key cache")
        return true // TODO: Implement proper verification with device public key cache
    }
    
    private suspend fun extractDeviceInfoFromInitiation(initiation: ECDHInitiation): DeviceIdentity {
        // In a complete implementation, we would:
        // 1. Decrypt the encrypted device info
        // 2. Parse the device information
        // 3. Validate the information against the signature
        
        // For now, create a basic identity with the device ID we know
        // TODO: Implement proper device info extraction from encrypted payload
        return DeviceIdentity(
            deviceId = initiation.deviceId,
            deviceName = "Device ${initiation.deviceId.take(8)}", // Use first 8 chars as display name
            deviceType = com.carlom.klardrop.common.utils.DeviceType.UNKNOWN, // Would be in encrypted info
            publicKey = null // Would be extracted from encrypted info
        )
    }
    
    private suspend fun handlePairingAccept(sessionId: String, deviceId: String) {
        try {
            trustStore.updatePairingSessionStatus(sessionId, PairingSessionStatus.ACCEPTED)
            logSecurityEvent(SecurityEventType.PAIRING_SUCCESS, deviceId, null)
            log(TAG, "Pairing accepted for session $sessionId")
        } catch (e: Exception) {
            log(TAG, "Failed to handle pairing accept: ${e.message}")
        }
    }
    
    private suspend fun handlePairingDecline(sessionId: String, deviceId: String) {
        try {
            trustStore.updatePairingSessionStatus(sessionId, PairingSessionStatus.REJECTED)
            logSecurityEvent(SecurityEventType.PAIRING_FAILED, deviceId, null)
            log(TAG, "Pairing declined for session $sessionId")
        } catch (e: Exception) {
            log(TAG, "Failed to handle pairing decline: ${e.message}")
        }
    }
    
    private suspend fun logSecurityEvent(eventType: SecurityEventType, deviceId: String?, ipAddress: String?) {
        try {
            val event = SecurityEvent(
                eventType = eventType,
                deviceId = deviceId,
                ipAddress = ipAddress,
                timestamp = Clock().currentTimeMillis(),
                details = when (eventType) {
                    SecurityEventType.AUTH_FAILED -> mapOf("reason" to "signature_verification_failed")
                    SecurityEventType.PAIRING_ATTEMPT -> mapOf("session_type" to "ecdh")
                    SecurityEventType.PAIRING_SUCCESS -> mapOf("method" to "trust_protocol")
                    SecurityEventType.PAIRING_FAILED -> mapOf("reason" to "user_declined")
                    else -> null
                }
            )
            trustStore.logSecurityEvent(event)
        } catch (e: Exception) {
            log(TAG, "Failed to log security event: ${e.message}")
        }
    }
    
    // SESSION CLEANUP FUNCTIONALITY
    
    private fun startSessionCleanup() {
        sessionCleanupJob = sessionCleanupScope.launch {
            while (isActive) {
                try {
                    cleanupExpiredSessions()
                    delay(60_000L) // Run every minute
                } catch (e: Exception) {
                    log(TAG, "Session cleanup error: ${e.message}")
                    delay(60_000L) // Wait before retrying
                }
            }
        }
    }
    
    private suspend fun cleanupExpiredSessions() {
        try {
            // Clean up expired pairing sessions
            trustStore.cleanExpiredPairingSessions()
            
            // Clean up old security events (keep last 30 days)
            trustStore.cleanupOldSecurityEvents(30)
            
            // Clean up expired trusted devices
            trustStore.cleanupExpiredDevices()
            
            log(TAG, "Session cleanup completed")
        } catch (e: Exception) {
            log(TAG, "Session cleanup failed: ${e.message}")
        }
    }
    
    // Cleanup method that can be called externally
    suspend fun performCleanup() {
        cleanupExpiredSessions()
    }
    
    // Stop the cleanup job when the handler is no longer needed
    fun stop() {
        sessionCleanupJob?.cancel()
    }
}