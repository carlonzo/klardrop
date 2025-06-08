package com.carlom.klardrop.chat

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.database.Messages
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DeviceChatViewModel(
    private val deviceId: String,
    val messageRepository: MessageRepository, // Made public val
    private val messenger: Messenger,
    private val coroutines: Coroutines,
    private val fileManager: com.carlom.klardrop.common.FileManager // Added
) {
    private val viewModelScope = CoroutineScope(coroutines.mainDispatcher + SupervisorJob())

    val messages: StateFlow<List<Messages>> =
        messageRepository.getMessagesForDevice(deviceId, limit = 100)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun sendTextMessage(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            // 1. Optimistically insert into local DB
            // Note: The actual remoteDeviceId for the messenger.send might be different if `deviceId` is an internal ID.
            // Assuming `deviceId` is the one known by the messenger.
            messageRepository.insertMessage(
                remoteDeviceId = deviceId,
                content = text,
                isSender = true,
                messageType = MessageType.TEXT,
                fileTransferId = null
            )

            // 2. Send the message over the network
            // We need to know the actual remote device ID that the messenger expects.
            // For now, assume this viewModel's deviceId is the correct one.
            val remoteDevice = messenger.getDeviceById(deviceId)
            if (remoteDevice != null) {
                val textMessage = TextMessage(text = text)
                messenger.send(remoteDevice, textMessage.toSimpleSendRequest())
                    .collect { progress ->
                        // Handle send progress/status if needed in the UI
                        // For text messages, it's usually quick or fire-and-forget
                        // For file messages, this flow would be more important.
                        println("Send progress: $progress")
                    }
            } else {
                // Handle error: device not found or not connected
                // Maybe update UI or log
                println("Error sending message: Device $deviceId not found by messenger.")
                // Optionally, update the just-inserted optimistic message to a 'failed' state here.
            }
        }
    }

    fun onDispose() {
        // Cancel any coroutines started by this ViewModel
        // (viewModelScope.cancel() would typically be called by the lifecycle owner in Android)
        // For multiplatform, manual cancellation or a lifecycle library is needed.
    }

    fun openFileClicked(filePath: String) {
        viewModelScope.launch {
            val success = fileManager.openFile(filePath)
            if (!success) {
                // Handle error (e.g., show a snackbar or log)
                println("DeviceChatViewModel: Failed to open file: $filePath")
                // Optionally, emit a state to the UI to show an error message
            }
        }
    }
}
