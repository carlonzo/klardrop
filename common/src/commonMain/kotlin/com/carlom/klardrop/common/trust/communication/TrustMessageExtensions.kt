package com.carlom.klardrop.common.trust.communication

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.trust.model.TrustMessage
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Extension to add trust message support to Messenger
 */
suspend fun Messenger.sendTrustMessage(deviceId: String, trustMessage: TrustMessage) {
    // Convert the trust message to the common TrustMessage type using kotlinx.serialization
    val proto = ProtoBuf { }
    val trustMessageBytes = proto.encodeToByteArray(TrustMessage.serializer(), trustMessage)
    val commonTrustMessage = com.carlom.klardrop.common.communication.message.TrustMessage(
        trustMessageBytes = trustMessageBytes
    )
    
    // Send using the existing messenger infrastructure
    val request = com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest(commonTrustMessage)
    send(deviceId, request)
}