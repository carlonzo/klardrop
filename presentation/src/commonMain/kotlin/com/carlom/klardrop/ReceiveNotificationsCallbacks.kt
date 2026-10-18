package com.carlom.klardrop

import com.carlom.klardrop.common.communication.message.ConnectionInfoMessage
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate

public interface ReceiveNotificationsCallbacks {
    public fun onReceivedCardClicked(receiveUpdate: ReceiveMessageUpdate): Unit
    public fun onCardDismissed(id: Int): Unit
    public fun onConnectionInfoAccepted(message: ConnectionInfoMessage): Unit {}
}
