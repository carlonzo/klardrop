package com.carlom.klardrop.common.trust.communication

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.TrustMessage
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.protos.trust.TrustMessage as ProtoTrustMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Routes trust messages between the communication layer and the trust system
 */
class TrustMessageRouter(
    private val scope: CoroutineScope
) {
    private var trustManager: TrustManager? = null
    private var messenger: Messenger? = null
    
    /**
     * Initialize the router with both TrustManager and Messenger
     */
    fun initialize(trustManager: TrustManager, messenger: Messenger) {
        this.trustManager = trustManager
        this.messenger = messenger
    }
    
    /**
     * Send a trust message to a device
     */
    suspend fun sendTrustMessage(deviceId: String, message: ProtoTrustMessage) {
        val msgr = messenger ?: throw IllegalStateException("TrustMessageRouter not initialized")
        
        // Convert protobuf message to communication TrustMessage
        val trustMessage = TrustMessage(
            trustMessageBytes = message.toByteArray()
        )
        
        // Send as a regular message
        val messageRequest = SendMessageRequest(trustMessage)
        msgr.send(deviceId, messageRequest).collect { progress ->
            when (progress) {
                is com.carlom.klardrop.common.communication.MessengerSendProgress.Error -> 
                    throw RuntimeException("Failed to send trust message: ${progress.message}")
                is com.carlom.klardrop.common.communication.MessengerSendProgress.Completed -> 
                    return@collect
                else -> {} // Continue
            }
        }
    }
    
    /**
     * Handle received trust message
     */
    suspend fun handleReceivedTrustMessage(message: TrustMessage, fromDeviceId: String) {
        val tm = trustManager ?: throw IllegalStateException("TrustMessageRouter not initialized")
        
        try {
            // Parse the protobuf message
            val protoMessage = ProtoTrustMessage.parseFrom(message.trustMessageBytes)
            
            // Handle it in the trust manager
            tm.handleTrustMessage(protoMessage, fromDeviceId)
        } catch (e: Exception) {
            // Log error but don't crash
            println("Error handling trust message: ${e.message}")
        }
    }
}