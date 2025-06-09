package com.carlom.klardrop.common.communication.message

import kotlinx.serialization.Serializable

@Serializable
data class AckMessage(
    val ackedMessageId: String
) : Message {
    override val type: MessageType = MessageType.ACK
    override val hasPayload: Boolean = false
    override val messageId: String? = null // ACK's own ID is null
}
