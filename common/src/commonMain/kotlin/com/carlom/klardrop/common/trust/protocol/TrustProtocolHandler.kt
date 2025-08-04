package com.carlom.klardrop.common.trust.protocol

import com.carlom.klardrop.common.trust.model.*
import com.carlom.klardrop.protos.trust.*
import kotlinx.coroutines.flow.*

interface TrustProtocolHandler {
    // Discovery
    suspend fun createDiscoveryAnnouncement(): DiscoveryAnnouncement
    suspend fun handleDiscoveryAnnouncement(announcement: DiscoveryAnnouncement, senderAddress: String)
    
    // Pairing
    suspend fun initiatePairing(deviceId: String): String // Returns session ID
    suspend fun handleECDHInitiation(initiation: ECDHInitiation, senderAddress: String)
    suspend fun handleECDHResponse(response: ECDHResponse)
    suspend fun handleGroupInvitation(invitation: GroupInvitation)
    suspend fun handleJoinConfirmation(confirmation: JoinConfirmation)
    
    // Member updates
    suspend fun handleMemberUpdate(update: MemberUpdate)
    suspend fun broadcastMemberUpdate(action: UpdateAction, device: com.carlom.klardrop.common.trust.model.TrustedDevice)
    
    // Clipboard sync
    suspend fun handleClipboardSync(sync: ClipboardSync)
    suspend fun broadcastClipboardUpdate(content: String)
    
    // Events
    fun getTrustEvents(): Flow<TrustEvent>
    
    // Utilities
    suspend fun isMessageFromTrustedDevice(deviceId: String): Boolean
    suspend fun verifyMessageSignature(data: ByteArray, signature: ByteArray, deviceId: String): Boolean
}

sealed class TrustEvent {
    data class PairingRequest(
        val device: DeviceIdentity,
        val sessionId: String,
        val onAccept: suspend () -> Unit,
        val onDecline: suspend () -> Unit
    ) : TrustEvent()
    
    data class DeviceJoined(val device: com.carlom.klardrop.common.trust.model.TrustedDevice) : TrustEvent()
    data class DeviceRemoved(val deviceId: String) : TrustEvent()
    data class DeviceUpdated(val device: com.carlom.klardrop.common.trust.model.TrustedDevice) : TrustEvent()
    data class ClipboardUpdate(val content: String, val fromDevice: String) : TrustEvent()
    data class TrustError(val message: String, val deviceId: String? = null) : TrustEvent()
}