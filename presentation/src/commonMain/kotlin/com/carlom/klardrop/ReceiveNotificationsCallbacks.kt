package com.carlom.klardrop

import com.carlom.klardrop.common.communication.message.ConnectionInfoMessage
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate

interface ReceiveNotificationsCallbacks {
    fun onReceivedCardClicked(receiveUpdate: ReceiveMessageUpdate)
    fun onCardDismissed(id: Int)
    fun onConnectionInfoAccepted(message: ConnectionInfoMessage) {}
}
