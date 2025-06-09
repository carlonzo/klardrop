package com.carlom.klardrop.common.communication

interface AckDelegate {
    fun onAckReceived(originalMessageId: String, fromDeviceId: String)
}
