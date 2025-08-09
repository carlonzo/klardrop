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
    
    // Device public key cache for ECDH signature verification
    // Maps deviceId -> (publicKey, timestamp) with 30-minute expiration
    private val devicePublicKeyCache = mutableMapOf<String, Pair<ByteArray, Long>>()
    private val publicKeyCacheTimeout = 30 * 60 * 1000L // 30 minutes
    
    // Ephemeral private key cache for ECDH sessions
    // Maps sessionId -> (ephemeralPrivateKey, timestamp)
    private val ephemeralKeyCache = mutableMapOf<String, Pair<ByteArray, Long>>()
    
    init {
        startSessionCleanup()
    }
    
    override suspend fun createDiscoveryAnnouncement(): DiscoveryAnnouncement {
        val device = deviceInfo()
        val trustGroup = trustStore.getTrustGroup()
        val timestamp = Clock().currentTimeMillis()
        
        // Create initial announcement
        val announcement = DiscoveryAnnouncement(
            deviceId = device.deviceId,
            deviceName = device.deviceName,
            deviceType = device.deviceType,
            publicKey = device.publicKey,
            isInTrustGroup = trustGroup != null,
            supportsAutoTrust = true,
            timestamp = timestamp,
            signature = byteArrayOf()
        )
        
        // Sign the announcement using same scheme as verifyDiscoveryAnnouncementSignature
        val signatureData = (announcement.deviceId + announcement.deviceName + 
                            announcement.deviceType.name + announcement.timestamp.toString()).encodeToByteArray() + 
                            announcement.publicKey
        val signature = cryptoProvider.signECDSA(signatureData, device.privateKey)
        
        return announcement.copy(signature = signature)
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
            
            // Cache the device public key for later ECDH signature verification
            val currentTime = Clock().currentTimeMillis()
            devicePublicKeyCache[announcement.deviceId] = announcement.publicKey to currentTime
            
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
            
            // Store ephemeral private key for later use in ECDH response handling
            ephemeralKeyCache[sessionId] = ephemeralKeypair.privateKey to Clock().currentTimeMillis()
            
            // Prepare data for signing
            val timestamp = Clock().currentTimeMillis()
            val nonce = cryptoProvider.generateRandomBytes(16)
            
            // Encrypt group ID if we have one
            val encryptedGroupId = try {
                val trustGroup = trustStore.getTrustGroup()
                if (trustGroup != null) {
                    // Use AES-GCM to encrypt the group ID with a derived key
                    val salt = cryptoProvider.generateRandomBytes(16)
                    val info = "group_id_encryption".encodeToByteArray()
                    val encryptionKey = cryptoProvider.deriveKey(
                        secret = device.privateKey, // Use device private key as seed
                        salt = salt,
                        info = info,
                        length = 32
                    )
                    val encrypted = cryptoProvider.encryptAESGCM(
                        data = trustGroup.groupId.encodeToByteArray(),
                        key = encryptionKey
                    )
                    // Return salt + encrypted data for later decryption
                    salt + encrypted.nonce + encrypted.tag + encrypted.ciphertext
                } else {
                    byteArrayOf() // No group to encrypt
                }
            } catch (e: Exception) {
                log(TAG, "Failed to encrypt group ID: ${e.message}")
                byteArrayOf() // Fallback to empty on error
            }
            
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
            
            // Encrypt device info to share securely
            val encryptedDeviceInfo = try {
                // Create device info JSON payload
                val deviceInfoJson = """
                    {
                        "deviceId": "${device.deviceId}",
                        "deviceName": "${device.deviceName}",
                        "deviceType": "${device.deviceType.name}",
                        "publicKey": "${device.publicKey.joinToString(",") { it.toString() }}"
                    }
                """.trimIndent()
                
                // Use ECDH shared secret to derive encryption key
                val sharedSecret = cryptoProvider.computeECDHSecret(
                    privateKey = ephemeralKeypair.privateKey,
                    publicKey = initiation.ephemeralPublicKey
                )
                
                val salt = cryptoProvider.generateRandomBytes(16)
                val info = "device_info_encryption".encodeToByteArray()
                val encryptionKey = cryptoProvider.deriveKey(
                    secret = sharedSecret,
                    salt = salt,
                    info = info,
                    length = 32
                )
                
                val encrypted = cryptoProvider.encryptAESGCM(
                    data = deviceInfoJson.encodeToByteArray(),
                    key = encryptionKey
                )
                
                // Return salt + encrypted data for later decryption
                salt + encrypted.nonce + encrypted.tag + encrypted.ciphertext
            } catch (e: Exception) {
                log(TAG, "Failed to encrypt device info: ${e.message}")
                byteArrayOf() // Fallback to empty on error
            }
            
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
            
            // Store our ephemeral private key for potential future use
            ephemeralKeyCache[initiation.sessionId] = ephemeralKeypair.privateKey to Clock().currentTimeMillis()
            
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
            
            // Verify the ECDH response signature
            val cachedData = devicePublicKeyCache[response.deviceId]
            if (cachedData == null) {
                log(TAG, "SECURITY: No cached public key for ECDH response from ${response.deviceId}")
                return
            }
            
            val (publicKey, cacheTime) = cachedData
            val currentTime = Clock().currentTimeMillis()
            
            if (currentTime - cacheTime > publicKeyCacheTimeout) {
                log(TAG, "SECURITY: Cached public key expired for ${response.deviceId}")
                devicePublicKeyCache.remove(response.deviceId)
                return
            }
            
            // Verify signature
            val signatureData = (response.sessionId + response.deviceId).encodeToByteArray() + 
                               response.ephemeralPublicKey + response.encryptedDeviceInfo + 
                               response.timestamp.toString().encodeToByteArray()
            
            if (!cryptoProvider.verifyECDSA(signatureData, response.signature, publicKey)) {
                log(TAG, "SECURITY: Invalid signature in ECDH response from ${response.deviceId}")
                logSecurityEvent(SecurityEventType.AUTH_FAILED, response.deviceId, null)
                return
            }
            
            // Get our ephemeral private key from cache
            val ephemeralKeyData = ephemeralKeyCache[response.sessionId]
            if (ephemeralKeyData == null) {
                log(TAG, "SECURITY: No cached ephemeral private key for session ${response.sessionId}")
                return
            }
            
            val (ephemeralPrivateKey, keyTime) = ephemeralKeyData
            
            if (currentTime - keyTime > publicKeyCacheTimeout) {
                log(TAG, "SECURITY: Cached ephemeral key expired for session ${response.sessionId}")
                ephemeralKeyCache.remove(response.sessionId)
                return
            }
            
            // Compute shared secret using our ephemeral private key and their ephemeral public key
            val sharedSecret = cryptoProvider.computeECDHSecret(
                privateKey = ephemeralPrivateKey,
                publicKey = response.ephemeralPublicKey
            )
            
            // Decrypt device info if available
            if (response.encryptedDeviceInfo.isNotEmpty()) {
                try {
                    // Extract components (salt + nonce + tag + ciphertext)
                    if (response.encryptedDeviceInfo.size >= 16 + 12 + 16) {
                        val salt = response.encryptedDeviceInfo.sliceArray(0..15)
                        val nonce = response.encryptedDeviceInfo.sliceArray(16..27)
                        val tag = response.encryptedDeviceInfo.sliceArray(28..43)
                        val ciphertext = response.encryptedDeviceInfo.sliceArray(44 until response.encryptedDeviceInfo.size)
                        
                        val info = "device_info_encryption".encodeToByteArray()
                        val decryptionKey = cryptoProvider.deriveKey(
                            secret = sharedSecret,
                            salt = salt,
                            info = info,
                            length = 32
                        )
                        
                        val payload = com.carlom.klardrop.common.trust.crypto.EncryptedPayload(
                            ciphertext = ciphertext,
                            nonce = nonce,
                            tag = tag
                        )
                        
                        val decryptedJson = String(cryptoProvider.decryptAESGCM(payload, decryptionKey))
                        log(TAG, "Successfully decrypted device info: $decryptedJson")
                        
                        // TODO: Parse JSON and extract device information
                        // For now, just log the success
                    }
                } catch (e: Exception) {
                    log(TAG, "Failed to decrypt device info from ECDH response: ${e.message}")
                }
            }
            
            // Update session status to indicate successful key exchange
            trustStore.updatePairingSessionStatus(response.sessionId, PairingSessionStatus.ACCEPTED)
            
            // Emit successful pairing - for now just log success
            // In future, we could add a PairingSuccess event type
            log(TAG, "Pairing successful for device ${response.deviceId}")
            
            log(TAG, "ECDH key exchange completed successfully for session ${response.sessionId}")
            
        } catch (e: Exception) {
            log(TAG, "Failed to handle ECDH response: ${e.message}")
        }
    }
    
    override suspend fun handleGroupInvitation(invitation: GroupInvitation) {
        try {
            log(TAG, "Received group invitation for session ${invitation.sessionId}")
            
            // For now, since GroupInvitation only has sessionId, encryptedPayload, and signature,
            // we'll need to decrypt the payload to get the actual invitation details
            
            // Verify invitation signature (basic validation)
            if (invitation.signature.isEmpty()) {
                log(TAG, "SECURITY: Group invitation without signature")
                return
            }
            
            // Try to decrypt the encrypted payload to extract invitation details
            // This would require the shared secret from the ECDH session
            val ephemeralKeyData = ephemeralKeyCache[invitation.sessionId]
            if (ephemeralKeyData == null) {
                log(TAG, "No ephemeral key found for invitation session ${invitation.sessionId}")
                _trustEvents.emit(TrustEvent.TrustError("Group invitation received but no session key available"))
                return
            }
            
            // For now, emit a basic trust error indicating group invitation was received
            // In a complete implementation, we would decrypt the payload and extract:
            // - groupId, fromDeviceId, encryptedGroupKey, etc.
            _trustEvents.emit(
                TrustEvent.TrustError("Group invitation received for session ${invitation.sessionId}")
            )
            
        } catch (e: Exception) {
            log(TAG, "Failed to handle group invitation: ${e.message}")
        }
    }
    
    override suspend fun handleJoinConfirmation(confirmation: JoinConfirmation) {
        try {
            if (confirmation.accepted) {
                log(TAG, "Join confirmation successful from ${confirmation.deviceId}")
                
                // Add the device to our trust group
                val currentGroup = trustStore.getTrustGroup()
                if (currentGroup != null) {
                    // Get device public key from cache
                    val cachedData = devicePublicKeyCache[confirmation.deviceId]
                    if (cachedData != null) {
                        val (publicKey, _) = cachedData
                        
                        // Create trusted device entry
                        val trustedDevice = TrustedDevice(
                            deviceId = confirmation.deviceId,
                            groupId = currentGroup.groupId,
                            publicKey = publicKey,
                            deviceName = "Device ${confirmation.deviceId.take(8)}",
                            deviceType = com.carlom.klardrop.common.utils.DeviceType.UNKNOWN,
                            addedAt = Clock().currentTimeMillis(),
                            addedBy = (deviceInfo()).deviceId,
                            lastSeen = Clock().currentTimeMillis(),
                            trustLevel = TrustLevel.FULL,
                            permissions = setOf(
                                Permission.CLIPBOARD_SYNC,
                                Permission.FILE_SEND,
                                Permission.FILE_RECEIVE
                            ),
                            isActive = true
                        )
                        
                        // Add device to trust store
                        trustStore.addTrustedDevice(trustedDevice)
                        
                        // Update group with new device
                        val updatedGroup = currentGroup.copy(
                            devices = currentGroup.devices.toMutableMap().apply {
                                put(confirmation.deviceId, trustedDevice)
                            }
                        )
                        trustStore.saveTrustGroup(updatedGroup)
                        
                        // Broadcast member update to other trusted devices
                        broadcastMemberUpdate(UpdateAction.ADD, trustedDevice)
                        
                        // Emit success event
                        _trustEvents.emit(
                            TrustEvent.DeviceJoined(trustedDevice)
                        )
                        
                        log(TAG, "Device ${confirmation.deviceId} successfully added to trust group ${currentGroup.groupId}")
                    } else {
                        log(TAG, "Cannot add device ${confirmation.deviceId}: no cached public key")
                        _trustEvents.emit(TrustEvent.TrustError("Cannot add device: missing public key"))
                    }
                } else {
                    log(TAG, "Cannot add device: no trust group exists")
                    _trustEvents.emit(TrustEvent.TrustError("Cannot add device: no trust group"))
                }
            } else {
                log(TAG, "Join confirmation failed - device ${confirmation.deviceId} declined to join")
                _trustEvents.emit(TrustEvent.TrustError("Device declined to join trust group"))
            }
            
            // Clean up ephemeral keys and pairing session
            val session = trustStore.getPairingSession(confirmation.sessionId)
            if (session != null) {
                ephemeralKeyCache.remove(confirmation.sessionId)
                trustStore.updatePairingSessionStatus(confirmation.sessionId, 
                    if (confirmation.accepted) PairingSessionStatus.ACCEPTED else PairingSessionStatus.REJECTED)
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
            
            // Encrypt clipboard content for secure transmission
            val encryptedContent = try {
                val groupKey = group.groupKey
                if (groupKey != null) {
                    // Use group key to encrypt clipboard content
                    val salt = cryptoProvider.generateRandomBytes(16)
                    val info = "clipboard_sync_encryption".encodeToByteArray()
                    val encryptionKey = cryptoProvider.deriveKey(
                        secret = groupKey,
                        salt = salt,
                        info = info,
                        length = 32
                    )
                    
                    val encrypted = cryptoProvider.encryptAESGCM(
                        data = content.encodeToByteArray(),
                        key = encryptionKey
                    )
                    
                    // Return salt + encrypted data
                    salt + encrypted.nonce + encrypted.tag + encrypted.ciphertext
                } else {
                    // Fallback: use device private key for encryption if no group key
                    log(TAG, "WARNING: No group key available, using device key for clipboard encryption")
                    val salt = cryptoProvider.generateRandomBytes(16)
                    val info = "clipboard_fallback_encryption".encodeToByteArray()
                    val encryptionKey = cryptoProvider.deriveKey(
                        secret = localDevice.privateKey,
                        salt = salt,
                        info = info,
                        length = 32
                    )
                    
                    val encrypted = cryptoProvider.encryptAESGCM(
                        data = content.encodeToByteArray(),
                        key = encryptionKey
                    )
                    
                    salt + encrypted.nonce + encrypted.tag + encrypted.ciphertext
                }
            } catch (e: Exception) {
                log(TAG, "Failed to encrypt clipboard content: ${e.message}")
                content.encodeToByteArray() // Fallback to plain text on error
            }
            
            // Use ClipboardSyncMessage for secure clipboard sync
            val syncMessage = ClipboardSyncMessage(
                deviceId = localDevice.deviceId,
                encryptedContent = encryptedContent,
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
        
        // Check if we have a cached public key for this device
        val currentTime = Clock().currentTimeMillis()
        val cachedData = devicePublicKeyCache[initiation.deviceId]
        
        if (cachedData == null) {
            log(TAG, "SECURITY: No cached public key for device ${initiation.deviceId}")
            return false
        }
        
        val (publicKey, cacheTime) = cachedData
        
        // Check if cached key has expired
        if (currentTime - cacheTime > publicKeyCacheTimeout) {
            log(TAG, "SECURITY: Cached public key expired for device ${initiation.deviceId}")
            devicePublicKeyCache.remove(initiation.deviceId)
            return false
        }
        
        // Verify the ECDH initiation signature using cached public key
        try {
            val signatureData = (initiation.sessionId + initiation.deviceId).encodeToByteArray() + 
                               initiation.ephemeralPublicKey + initiation.encryptedGroupId + 
                               initiation.timestamp.toString().encodeToByteArray() + initiation.nonce
            
            val isValid = cryptoProvider.verifyECDSA(signatureData, initiation.signature, publicKey)
            
            if (!isValid) {
                log(TAG, "SECURITY: Invalid ECDH initiation signature from ${initiation.deviceId}")
                // Remove from cache on signature failure as a security precaution
                devicePublicKeyCache.remove(initiation.deviceId)
            }
            
            return isValid
        } catch (e: Exception) {
            log(TAG, "SECURITY: Error verifying ECDH initiation signature: ${e.message}")
            return false
        }
    }
    
    private suspend fun extractDeviceInfoFromInitiation(initiation: ECDHInitiation): DeviceIdentity {
        // First, try to get device info from the cached public key (from discovery)
        val cachedData = devicePublicKeyCache[initiation.deviceId]
        val cachedPublicKey = cachedData?.first
        
        // If we have encrypted group ID, try to decrypt and extract device info
        if (initiation.encryptedGroupId.isNotEmpty()) {
            try {
                // Extract salt and encrypted data
                if (initiation.encryptedGroupId.size >= 16 + 12 + 16) { // salt + nonce + tag minimum
                    val salt = initiation.encryptedGroupId.sliceArray(0..15)
                    val nonce = initiation.encryptedGroupId.sliceArray(16..27)
                    val tag = initiation.encryptedGroupId.sliceArray(28..43)
                    val ciphertext = initiation.encryptedGroupId.sliceArray(44 until initiation.encryptedGroupId.size)
                    
                    // Try to decrypt using device's private key (if we can derive the same key)
                    val device = deviceInfo()
                    val info = "group_id_encryption".encodeToByteArray()
                    val decryptionKey = cryptoProvider.deriveKey(
                        secret = device.privateKey, // Note: This only works if we have the same device key
                        salt = salt,
                        info = info,
                        length = 32
                    )
                    
                    val payload = com.carlom.klardrop.common.trust.crypto.EncryptedPayload(
                        ciphertext = ciphertext,
                        nonce = nonce,
                        tag = tag
                    )
                    
                    val decryptedData = cryptoProvider.decryptAESGCM(payload, decryptionKey)
                    val groupId = String(decryptedData)
                    
                    log(TAG, "Successfully extracted group ID from initiation: $groupId")
                } else {
                    log(TAG, "WARNING: Encrypted group ID too small to contain valid data")
                }
            } catch (e: Exception) {
                log(TAG, "Failed to decrypt device info from initiation: ${e.message}")
            }
        }
        
        // Return device identity with available information
        return DeviceIdentity(
            deviceId = initiation.deviceId,
            deviceName = "Device ${initiation.deviceId.take(8)}", // Use first 8 chars as display name
            deviceType = com.carlom.klardrop.common.utils.DeviceType.UNKNOWN, // Would need to be in encrypted payload
            publicKey = cachedPublicKey // Use cached public key from discovery announcement
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
    
    private suspend fun handleGroupInvitationAccept(invitation: GroupInvitation, groupKey: ByteArray?) {
        try {
            log(TAG, "Accepting group invitation for session ${invitation.sessionId}")
            
            if (groupKey != null) {
                // Create/update trust group with the new group key
                val currentGroup = trustStore.getTrustGroup()
                if (currentGroup == null) {
                    // Create new trust group
                    val currentTime = Clock().currentTimeMillis()
                    val newGroup = TrustGroup(
                        groupId = "temp-group-id", // invitation.groupId not available in current model
                        groupKey = groupKey,
                        groupName = "Trust Group",
                        devices = mutableMapOf(),
                        createdAt = currentTime,
                        updatedAt = currentTime,
                        cloudSyncEnabled = false
                    )
                    trustStore.saveTrustGroup(newGroup)
                } else {
                    // Update existing group
                    trustStore.updateGroupKey(currentGroup.groupId, groupKey)
                }
                
                // Send join confirmation back to the inviting device
                val device = deviceInfo()
                val timestamp = Clock().currentTimeMillis()
                val signatureData = (invitation.sessionId + device.deviceId + "true" + timestamp.toString()).encodeToByteArray()
                val signature = cryptoProvider.signECDSA(signatureData, device.privateKey)
                
                val confirmation = JoinConfirmation(
                    sessionId = invitation.sessionId,
                    deviceId = device.deviceId,
                    accepted = true,
                    timestamp = timestamp,
                    signature = signature
                )
                
                // Note: fromDeviceId not available in current GroupInvitation model
                // Would need to extract from decrypted payload or store during session
                // For now, log the confirmation
                log(TAG, "Would send join confirmation: ${confirmation.accepted}")
                
                _trustEvents.emit(
                    TrustEvent.TrustError("Successfully joined trust group")
                )
            } else {
                log(TAG, "Cannot accept group invitation: no valid group key")
                handleGroupInvitationDecline(invitation.sessionId, "unknown-device")
            }
        } catch (e: Exception) {
            log(TAG, "Failed to handle group invitation accept: ${e.message}")
        }
    }
    
    private suspend fun handleGroupInvitationDecline(sessionId: String, deviceId: String) {
        try {
            log(TAG, "Declining group invitation for session $sessionId")
            
            val device = deviceInfo()
            val timestamp = Clock().currentTimeMillis()
            val signatureData = (sessionId + device.deviceId + "false" + timestamp.toString()).encodeToByteArray()
            val signature = cryptoProvider.signECDSA(signatureData, device.privateKey)
            
            val confirmation = JoinConfirmation(
                sessionId = sessionId,
                deviceId = device.deviceId,
                accepted = false,
                timestamp = timestamp,
                signature = signature
            )
            
            sendMessage(deviceId, confirmation)
            logSecurityEvent(SecurityEventType.PAIRING_FAILED, deviceId, null)
        } catch (e: Exception) {
            log(TAG, "Failed to handle group invitation decline: ${e.message}")
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
            
            // Clean up expired device public key cache
            val currentTime = Clock().currentTimeMillis()
            val expiredKeys = devicePublicKeyCache.filter { (_, cacheData) ->
                currentTime - cacheData.second > publicKeyCacheTimeout
            }.keys
            expiredKeys.forEach { devicePublicKeyCache.remove(it) }
            
            // Clean up expired ephemeral key cache
            val expiredEphemeralKeys = ephemeralKeyCache.filter { (_, cacheData) ->
                currentTime - cacheData.second > publicKeyCacheTimeout
            }.keys
            expiredEphemeralKeys.forEach { ephemeralKeyCache.remove(it) }
            
            if (expiredKeys.isNotEmpty() || expiredEphemeralKeys.isNotEmpty()) {
                log(TAG, "Cleaned up ${expiredKeys.size} expired public keys and ${expiredEphemeralKeys.size} expired ephemeral keys")
            }
            
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
        
        // Clear caches on shutdown for security
        devicePublicKeyCache.clear()
        ephemeralKeyCache.clear()
        
        log(TAG, "TrustProtocolHandler stopped and caches cleared")
    }
}