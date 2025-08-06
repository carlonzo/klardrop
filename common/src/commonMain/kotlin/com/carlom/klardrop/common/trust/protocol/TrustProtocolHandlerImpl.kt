package com.carlom.klardrop.common.trust.protocol

import com.carlom.klardrop.common.trust.crypto.CryptoProvider
import com.carlom.klardrop.common.trust.model.*
import com.carlom.klardrop.common.trust.storage.TrustStore
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.log
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
        
        return DiscoveryAnnouncement(
            deviceId = device.deviceId,
            deviceName = device.deviceName,
            deviceType = device.deviceType,
            publicKey = device.publicKey
        )
    }
    
    override suspend fun handleDiscoveryAnnouncement(announcement: DiscoveryAnnouncement, senderAddress: String) {
        try {
            // Verify the announcement (in a real implementation, we'd verify the signature)
            log(TAG, "Received discovery announcement from ${announcement.deviceId}")
            
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
            
            // Send ECDH initiation
            val initiation = ECDHInitiation(
                sessionId = sessionId,
                ephemeralPublicKey = ephemeralKeypair.publicKey
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
            val device = deviceInfo()
            
            // Generate ephemeral key pair for ECDH response
            val ephemeralKeypair = cryptoProvider.generateECDHKeypair()
            
            // Create ECDH response
            val response = ECDHResponse(
                sessionId = initiation.sessionId,
                ephemeralPublicKey = ephemeralKeypair.publicKey
            )
            
            // Send the response back (we'd need to extract sender device ID from the address)
            sendMessage(senderAddress, response)
            
            // Create a pairing session and emit pairing request event
            val session = PairingSession(
                sessionId = initiation.sessionId,
                deviceId = senderAddress, // In real implementation, this would be extracted properly
                ephemeralPublicKey = initiation.ephemeralPublicKey,
                expiresAt = Clock().currentTimeMillis() + PAIRING_SESSION_TIMEOUT_MS
            )
            
            trustStore.createPairingSession(session)
            
            // Emit pairing request event
            _trustEvents.emit(
                TrustEvent.PairingRequest(
                    device = DeviceIdentity(
                        deviceId = senderAddress,
                        deviceName = "Unknown Device",
                        deviceType = com.carlom.klardrop.common.utils.DeviceType.UNKNOWN
                    ),
                    sessionId = initiation.sessionId,
                    onAccept = { /* Handle accept */ },
                    onDecline = { /* Handle decline */ }
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
            log(TAG, "Received group invitation for group ${invitation.groupId}")
            
            // Emit group invitation event (need to check if this event type exists)
            _trustEvents.emit(
                TrustEvent.TrustError("Group invitation received for ${invitation.groupId}")
            )
            
        } catch (e: Exception) {
            log(TAG, "Failed to handle group invitation: ${e.message}")
        }
    }
    
    override suspend fun handleJoinConfirmation(confirmation: JoinConfirmation) {
        try {
            if (confirmation.success) {
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
            log(TAG, "Received member update: ${update.action} for device ${update.deviceId}")
            
            when (update.action) {
                UpdateAction.ADD -> {
                    // In a real implementation, we'd add the device
                    log(TAG, "Adding device ${update.deviceId} to trust group")
                }
                UpdateAction.REMOVE -> {
                    trustStore.removeTrustedDevice(update.deviceId)
                    _trustEvents.emit(TrustEvent.DeviceRemoved(update.deviceId))
                }
                UpdateAction.UPDATE -> {
                    // In a real implementation, we'd update device info
                    log(TAG, "Updating device ${update.deviceId}")
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
                deviceId = device.deviceId,
                action = action
            )
            
            // Send to all trusted devices
            group.devices.values.forEach { trustedDevice ->
                if (trustedDevice.deviceId != deviceInfo.deviceId) {
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
                log(TAG, "Clipboard sync from untrusted device: ${sync.deviceId}")
                return
            }
            
            log(TAG, "Received clipboard sync from ${sync.deviceId}")
            
            // Create clipboard entry
            val entry = ClipboardEntry(
                deviceId = sync.deviceId,
                content = sync.content,
                contentHash = cryptoProvider.hash(sync.content.encodeToByteArray()).contentToString(),
                timestamp = Clock().currentTimeMillis(),
                signature = byteArrayOf() // Would be verified in real implementation
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
            val deviceInfo = deviceInfo()
            
            val sync = ClipboardSync(
                content = content,
                deviceId = deviceInfo.deviceId
            )
            
            // Send to all trusted devices with clipboard sync permission
            group.devices.values.forEach { trustedDevice ->
                if (trustedDevice.deviceId != deviceInfo.deviceId &&
                    trustedDevice.permissions.contains(Permission.CLIPBOARD_SYNC)) {
                    sendMessage(trustedDevice.deviceId, sync)
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