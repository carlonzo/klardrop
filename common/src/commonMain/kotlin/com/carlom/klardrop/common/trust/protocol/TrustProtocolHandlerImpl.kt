package com.carlom.klardrop.common.trust.protocol

import com.carlom.klardrop.common.trust.crypto.CryptoProvider
import com.carlom.klardrop.common.trust.model.*
import com.carlom.klardrop.common.trust.storage.TrustStore
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.common.trust.model.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class TrustProtocolHandlerImpl(
    private val trustStore: TrustStore,
    private val cryptoProvider: CryptoProvider,
    private val deviceInfo: suspend () -> DeviceKeypair,
    private val sendMessage: suspend (deviceId: String, message: Any) -> Unit
) : TrustProtocolHandler {
    
    companion object {
        private const val TAG = "TrustProtocolHandler"
        private const val PAIRING_SESSION_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }
    
    private val _trustEvents = MutableSharedFlow<TrustEvent>()
    
    override suspend fun createDiscoveryAnnouncement(): DiscoveryAnnouncement {
        val device = deviceInfo()
        val trustGroup = trustStore.getTrustGroup()
        
        val announcement = DiscoveryAnnouncement(
            device_id = device.deviceId,
            public_key = okio.ByteString.of(*device.publicKey),
            is_in_trust_group = trustGroup != null,
            supports_auto_trust = true,
            timestamp = Clock().currentTimeMillis(),
            protocol_version = 1,
            signature = okio.ByteString.EMPTY
        )
        
        // Sign the announcement
        val dataToSign = buildString {
            append(announcement.device_id)
            append(announcement.public_key.hex())
            append(announcement.is_in_trust_group)
            append(announcement.supports_auto_trust)
            append(announcement.timestamp)
            append(announcement.protocol_version)
        }.encodeToByteArray()
        
        val signature = cryptoProvider.signECDSA(dataToSign, device.privateKey)
        
        return announcement.copy(signature = okio.ByteString.of(*signature))
    }
    
    override suspend fun handleDiscoveryAnnouncement(announcement: DiscoveryAnnouncement, senderAddress: String) {
        // Verify signature
        val dataToVerify = buildString {
            append(announcement.device_id)
            append(announcement.public_key.hex())
            append(announcement.is_in_trust_group)
            append(announcement.supports_auto_trust)
            append(announcement.timestamp)
            append(announcement.protocol_version)
        }.encodeToByteArray()
        
        val isValid = cryptoProvider.verifyECDSA(
            dataToVerify,
            announcement.signature.toByteArray(),
            announcement.public_key.toByteArray()
        )
        
        if (!isValid) {
            log(TAG, "Invalid signature in discovery announcement from ${announcement.device_id}")
            return
        }
        
        // Check if device is already trusted
        val isTrusted = trustStore.isDeviceTrusted(announcement.device_id)
        
        if (!isTrusted) {
            // Emit new device event
            _trustEvents.emit(
                TrustEvent.NewDeviceNearby(
                    device = DeviceIdentity(
                        deviceId = announcement.device_id,
                        deviceName = "Unknown Device", // Will be updated during pairing
                        deviceType = com.carlom.klardrop.common.utils.DeviceType.UNKNOWN
                    )
                )
            )
        } else {
            // Update last seen
            trustStore.updateDeviceLastSeen(announcement.device_id)
        }
    }
    
    @OptIn(ExperimentalUuidApi::class)
    override suspend fun initiatePairing(deviceId: String): String {
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
        
        // Send ECDH initiation
        val initiation = ECDHInitiation(
            session_id = sessionId,
            device_id = device.deviceId,
            ephemeral_public_key = okio.ByteString.of(*ephemeralKeypair.publicKey),
            encrypted_group_id = okio.ByteString.EMPTY, // Will be set if we have a group
            timestamp = Clock().currentTimeMillis()
        )
        
        val message = com.carlom.klardrop.protos.trust.TrustMessage(
            type = TrustMessageType.TRUST_MESSAGE_TYPE_ECDH_INITIATION,
            ecdh_initiation = initiation
        )
        
        sendMessage(deviceId, message)
        
        return sessionId
    }
    
    override suspend fun handleECDHInitiation(initiation: ECDHInitiation, senderAddress: String) {
        try {
            val device = deviceInfo()
            
            // Generate ephemeral key pair for ECDH response
            val ephemeralKeypair = cryptoProvider.generateECDHKeypair()
            
            // Compute shared secret
            val sharedSecret = cryptoProvider.computeECDHSharedSecret(
                ephemeralKeypair.privateKey,
                initiation.ephemeral_public_key.toByteArray()
            )
            
            // Create ECDH response
            val response = ECDHResponse(
                session_id = initiation.session_id,
                device_id = device.deviceId,
                device_name = device.deviceName,
                ephemeral_public_key = okio.ByteString.of(*ephemeralKeypair.publicKey),
                encrypted_device_info = okio.ByteString.EMPTY, // TODO: Encrypt device info
                timestamp = Clock().currentTimeMillis()
            )
            
            val message = com.carlom.klardrop.protos.trust.TrustMessage(
                type = TrustMessageType.TRUST_MESSAGE_TYPE_ECDH_RESPONSE,
                ecdh_response = response
            )
            
            sendMessage(initiation.device_id, message)
            
            // Emit pairing request event
            _trustEvents.emit(
                TrustEvent.PairingRequest(
                    deviceId = initiation.device_id,
                    sessionId = initiation.session_id
                )
            )
            
        } catch (e: Exception) {
            log(TAG, "Failed to handle ECDH initiation: ${e.message}")
        }
    }
    
    override suspend fun handleECDHResponse(response: ECDHResponse) {
        try {
            val session = trustStore.getPairingSession(response.session_id)
            if (session == null) {
                log(TAG, "No pairing session found for ${response.session_id}")
                return
            }
            
            // Compute shared secret
            val sharedSecret = cryptoProvider.computeECDHSharedSecret(
                session.ephemeralPublicKey, // This should be the private key, fix this
                response.ephemeral_public_key.toByteArray()
            )
            
            // Update session status
            trustStore.updatePairingSessionStatus(response.session_id, PairingSessionStatus.PENDING_CONFIRMATION)
            
            // Emit pairing response event
            _trustEvents.emit(
                TrustEvent.PairingResponse(
                    deviceId = response.device_id,
                    deviceName = response.device_name,
                    sessionId = response.session_id
                )
            )
            
        } catch (e: Exception) {
            log(TAG, "Failed to handle ECDH response: ${e.message}")
        }
    }
    
    override suspend fun handleGroupInvitation(invitation: GroupInvitation) {
        try {
            // Verify the invitation signature
            val dataToVerify = buildString {
                append(invitation.group_id)
                append(invitation.inviter_device_id)
                append(invitation.encrypted_group_key.hex())
                append(invitation.timestamp)
            }.encodeToByteArray()
            
            val inviterDevice = trustStore.getTrustedDevice(invitation.inviter_device_id)
            if (inviterDevice == null) {
                log(TAG, "Group invitation from unknown device: ${invitation.inviter_device_id}")
                return
            }
            
            val isValid = cryptoProvider.verifyECDSA(
                dataToVerify,
                invitation.signature.toByteArray(),
                inviterDevice.publicKey
            )
            
            if (!isValid) {
                log(TAG, "Invalid group invitation signature from ${invitation.inviter_device_id}")
                return
            }
            
            // Emit group invitation event
            _trustEvents.emit(
                TrustEvent.GroupInvitation(
                    groupId = invitation.group_id,
                    inviterDeviceId = invitation.inviter_device_id,
                    groupName = invitation.group_name
                )
            )
            
        } catch (e: Exception) {
            log(TAG, "Failed to handle group invitation: ${e.message}")
        }
    }
    
    override suspend fun handleJoinConfirmation(confirmation: JoinConfirmation) {
        try {
            // Update trust store with new group info
            val currentGroup = trustStore.getTrustGroup()
            if (currentGroup?.groupId == confirmation.group_id) {
                // Add new member to group
                val newDevice = TrustedDevice(
                    deviceId = confirmation.device_id,
                    groupId = confirmation.group_id,
                    publicKey = confirmation.public_key.toByteArray(),
                    deviceName = confirmation.device_name,
                    deviceType = confirmation.device_type.toLocalDeviceType(),
                    addedAt = Clock().currentTimeMillis(),
                    addedBy = deviceInfo().deviceId,
                    trustLevel = TrustLevel.FULL,
                    permissions = setOf(Permission.FILE_SEND, Permission.FILE_RECEIVE),
                    isActive = true
                )
                
                trustStore.addTrustedDevice(newDevice)
                
                // Emit member joined event
                _trustEvents.emit(TrustEvent.MemberJoined(newDevice))
            }
            
        } catch (e: Exception) {
            log(TAG, "Failed to handle join confirmation: ${e.message}")
        }
    }
    
    override suspend fun handleMemberUpdate(update: MemberUpdate) {
        try {
            when (update.action) {
                UpdateAction.UPDATE_ACTION_ADDED -> {
                    val newDevice = update.device.toLocalTrustedDevice(update.group_id)
                    trustStore.addTrustedDevice(newDevice)
                    _trustEvents.emit(TrustEvent.MemberJoined(newDevice))
                }
                UpdateAction.UPDATE_ACTION_REMOVED -> {
                    trustStore.removeTrustedDevice(update.device.identity.device_id)
                    _trustEvents.emit(TrustEvent.MemberLeft(update.device.identity.device_id))
                }
                UpdateAction.UPDATE_ACTION_UPDATED -> {
                    val updatedDevice = update.device.toLocalTrustedDevice(update.group_id)
                    trustStore.addTrustedDevice(updatedDevice) // This will update existing
                    _trustEvents.emit(TrustEvent.MemberUpdated(updatedDevice))
                }
                else -> {
                    log(TAG, "Unknown update action: ${update.action}")
                }
            }
        } catch (e: Exception) {
            log(TAG, "Failed to handle member update: ${e.message}")
        }
    }
    
    override suspend fun broadcastMemberUpdate(action: UpdateAction, device: TrustedDevice) {
        try {
            val group = trustStore.getTrustGroup() ?: return
            val deviceInfo = deviceInfo()
            
            val update = MemberUpdate(
                group_id = group.groupId,
                action = action,
                device = device.toProtoTrustedDevice(),
                updated_by = deviceInfo.deviceId,
                timestamp = Clock().currentTimeMillis()
            )
            
            val message = com.carlom.klardrop.protos.trust.TrustMessage(
                type = TrustMessageType.TRUST_MESSAGE_TYPE_MEMBER_UPDATE,
                member_update = update
            )
            
            // Send to all trusted devices
            group.devices.values.forEach { trustedDevice ->
                if (trustedDevice.deviceId != deviceInfo.deviceId) {
                    sendMessage(trustedDevice.deviceId, message)
                }
            }
            
        } catch (e: Exception) {
            log(TAG, "Failed to broadcast member update: ${e.message}")
        }
    }
    
    override suspend fun handleClipboardSync(sync: ClipboardSync) {
        try {
            // Verify sender is trusted
            if (!trustStore.isDeviceTrusted(sync.device_id)) {
                log(TAG, "Clipboard sync from untrusted device: ${sync.device_id}")
                return
            }
            
            // Verify signature
            val trustedDevice = trustStore.getTrustedDevice(sync.device_id) ?: return
            val dataToVerify = buildString {
                append(sync.content)
                append(sync.content_hash)
                append(sync.timestamp)
            }.encodeToByteArray()
            
            val isValid = cryptoProvider.verifyECDSA(
                dataToVerify,
                sync.signature.toByteArray(),
                trustedDevice.publicKey
            )
            
            if (!isValid) {
                log(TAG, "Invalid clipboard sync signature from ${sync.device_id}")
                return
            }
            
            // Save clipboard entry
            val entry = ClipboardEntry(
                deviceId = sync.device_id,
                content = sync.content,
                contentHash = sync.content_hash,
                timestamp = sync.timestamp,
                signature = sync.signature.toByteArray()
            )
            
            if (trustStore.isClipboardContentNew(entry.contentHash)) {
                trustStore.saveClipboardEntry(entry)
                _trustEvents.emit(TrustEvent.ClipboardSync(entry))
            }
            
        } catch (e: Exception) {
            log(TAG, "Failed to handle clipboard sync: ${e.message}")
        }
    }
    
    override suspend fun broadcastClipboardUpdate(content: String) {
        try {
            val group = trustStore.getTrustGroup() ?: return
            val deviceInfo = deviceInfo()
            
            val contentHash = cryptoProvider.hash(content.encodeToByteArray())
            val timestamp = Clock().currentTimeMillis()
            
            // Create signature
            val dataToSign = buildString {
                append(content)
                append(contentHash.contentToString())
                append(timestamp)
            }.encodeToByteArray()
            
            val signature = cryptoProvider.signECDSA(dataToSign, deviceInfo.privateKey)
            
            val sync = ClipboardSync(
                device_id = deviceInfo.deviceId,
                content = content,
                content_hash = contentHash.contentToString(),
                timestamp = timestamp,
                signature = okio.ByteString.of(*signature)
            )
            
            val message = com.carlom.klardrop.protos.trust.TrustMessage(
                type = TrustMessageType.TRUST_MESSAGE_TYPE_CLIPBOARD_SYNC,
                clipboard_sync = sync
            )
            
            // Send to all trusted devices with clipboard sync permission
            group.devices.values.forEach { trustedDevice ->
                if (trustedDevice.deviceId != deviceInfo.deviceId &&
                    trustedDevice.permissions.contains(Permission.CLIPBOARD_SYNC)) {
                    sendMessage(trustedDevice.deviceId, message)
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
}