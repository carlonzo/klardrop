package com.carlom.klardrop.common.trust.communication

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.protos.trust.TrustMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Extension to add trust message support to Messenger
 */
suspend fun Messenger.sendTrustMessage(deviceId: String, trustMessage: TrustMessage) {
    // Convert the trust message to the common TrustMessage type
    val trustMessageBytes = trustMessage.encode()
    val commonTrustMessage = com.carlom.klardrop.common.communication.message.TrustMessage(
        trustMessageBytes = trustMessageBytes
    )
    
    // Send using the existing messenger infrastructure
    val request = com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest(commonTrustMessage)
    send(deviceId, request)
}