package com.carlom.klardrop.common.trust.protocol

import com.carlom.klardrop.common.trust.crypto.CryptoProvider
import com.carlom.klardrop.common.trust.model.*
import com.carlom.klardrop.common.trust.storage.TrustStore
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.protos.trust.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Stub implementation of TrustProtocolHandler for compilation
 * TODO: Replace with proper implementation once protobuf usage is fixed
 */
class TrustProtocolHandlerStub(
    private val trustStore: TrustStore,
    private val cryptoProvider: CryptoProvider,
    private val deviceInfo: suspend () -> DeviceKeypair,
    private val sendMessage: suspend (deviceId: String, message: com.carlom.klardrop.protos.trust.TrustMessage) -> Unit
) : TrustProtocolHandler {
    
    private val _trustEvents = MutableSharedFlow<TrustEvent>()
    
    override suspend fun createDiscoveryAnnouncement(): DiscoveryAnnouncement {
        val device = deviceInfo()
        return DiscoveryAnnouncement(
            device_id = device.deviceId,
            public_key = okio.ByteString.of(*device.publicKey),
            is_in_trust_group = trustStore.getTrustGroup() != null,
            supports_auto_trust = true,
            timestamp = Clock().currentTimeMillis(),
            protocol_version = 1,
            signature = okio.ByteString.EMPTY
        )
    }
    
    override suspend fun handleDiscoveryAnnouncement(announcement: DiscoveryAnnouncement, senderAddress: String) {
        // Stub implementation
    }
    
    @OptIn(ExperimentalUuidApi::class)
    override suspend fun initiatePairing(deviceId: String): String {
        return Uuid.random().toString()
    }
    
    override suspend fun handleECDHInitiation(initiation: ECDHInitiation, senderAddress: String) {
        // Stub implementation
    }
    
    override suspend fun handleECDHResponse(response: ECDHResponse) {
        // Stub implementation
    }
    
    override suspend fun handleGroupInvitation(invitation: GroupInvitation) {
        // Stub implementation
    }
    
    override suspend fun handleJoinConfirmation(confirmation: JoinConfirmation) {
        // Stub implementation
    }
    
    override suspend fun handleMemberUpdate(update: MemberUpdate) {
        // Stub implementation
    }
    
    override suspend fun broadcastMemberUpdate(action: UpdateAction, device: com.carlom.klardrop.common.trust.model.TrustedDevice) {
        // Stub implementation
    }
    
    override suspend fun handleClipboardSync(sync: ClipboardSync) {
        // Stub implementation
    }
    
    override suspend fun broadcastClipboardUpdate(content: String) {
        // Stub implementation
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