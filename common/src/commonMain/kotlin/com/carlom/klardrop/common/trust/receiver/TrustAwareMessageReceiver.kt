package com.carlom.klardrop.common.trust.receiver

import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.protos.trust.Permission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Trust-aware message receiver that automatically accepts transfers from trusted devices
 */
class TrustAwareMessageReceiver(
    private val baseReceiver: MessageReceiver,
    private val trustManager: TrustManager,
    private val scope: CoroutineScope
) : MessageReceiver {
    
    override fun onReceiveMessage(deviceId: String): MutableStateFlow<ReceiveMessageUpdate> {
        val baseFlow = baseReceiver.onReceiveMessage(deviceId)
        
        // Check trust status and potentially auto-accept
        scope.launch {
            baseFlow.collect { update ->
                when (update.status) {
                    is ReceiveMessageStatus.PendingAuthorization -> {
                        handlePendingAuthorization(deviceId, update.status)
                    }
                    else -> {
                        // Other statuses pass through unchanged
                    }
                }
            }
        }
        
        return baseFlow
    }
    
    override val notifier: Flow<Flow<ReceiveMessageUpdate>>
        get() = baseReceiver.notifier
    
    override val messageReceivedNotifier: Flow<ReceiveMessageUpdate>
        get() = baseReceiver.messageReceivedNotifier
    
    private suspend fun handlePendingAuthorization(
        deviceId: String,
        status: ReceiveMessageStatus.PendingAuthorization
    ) {
        try {
            // Check if device is trusted
            if (trustManager.isDeviceTrusted(deviceId)) {
                // Check trust level and permissions
                val trustLevel = trustManager.getDeviceTrustLevel(deviceId)
                val trustedDevice = trustManager.trustedDevices.value.find { it.deviceId == deviceId }
                
                if (trustedDevice != null) {
                    // Check if device has file receive permission
                    if (trustedDevice.permissions.contains(Permission.PERMISSION_FILE_RECEIVE)) {
                        log("TrustAwareMessageReceiver", "Auto-accepting transfer from trusted device: $deviceId")
                        
                        // Auto-accept the transfer
                        status.acceptTransfer(true)
                        
                        // Log security event
                        trustManager.logSecurityEvent(
                            com.carlom.klardrop.common.trust.model.SecurityEvent(
                                eventType = com.carlom.klardrop.common.trust.model.SecurityEventType.AUTH_FAILED,
                                deviceId = deviceId,
                                timestamp = Clock().currentTimeMillis(),
                                details = mapOf("action" to "auto_accept_transfer")
                            )
                        )
                        
                        return
                    }
                }
            }
            
            // If not trusted or doesn't have permission, let user decide
            log("TrustAwareMessageReceiver", "Device $deviceId not trusted, requiring manual authorization")
            
        } catch (e: Exception) {
            log("TrustAwareMessageReceiver", "Error checking trust status for device $deviceId", e)
            // On error, fall back to manual authorization
        }
    }
}

/**
 * Extension function to wrap an existing MessageReceiver with trust awareness
 */
fun MessageReceiver.withTrustAwareness(
    trustManager: TrustManager,
    scope: CoroutineScope
): MessageReceiver {
    return TrustAwareMessageReceiver(this, trustManager, scope)
}