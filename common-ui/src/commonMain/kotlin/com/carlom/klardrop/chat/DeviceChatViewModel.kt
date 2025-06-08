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
import kotlinx.coroutines.cancel
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

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val messages: StateFlow<List<Messages>> =
        messageRepository.getMessagesForDevice(deviceId, limit = 100)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun sendTextMessage(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isSending = true, error = null)
                
                // 1. Optimistically insert into local DB
                messageRepository.insertMessage(
                    remoteDeviceId = deviceId,
                    content = text,
                    isSender = true,
                    messageType = MessageType.TEXT,
                    fileTransferId = null
                )

                // 2. Send the message over the network
                val remoteDevice = messenger.getDeviceById(deviceId)
                if (remoteDevice != null) {
                    val textMessage = TextMessage(text = text)
                    messenger.send(remoteDevice, textMessage.toSimpleSendRequest())
                        .collect { progress ->
                            // Handle send progress if needed
                        }
                } else {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = "Device not found or not connected"
                    )
                }
                
                _uiState.value = _uiState.value.copy(isSending = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    error = "Failed to send message: ${e.message}"
                )
            }
        }
    }

    fun onDispose() {
        // Cancel any coroutines started by this ViewModel
        viewModelScope.cancel()
    }

    fun openFileClicked(filePath: String) {
        viewModelScope.launch {
            try {
                val success = fileManager.openFile(filePath)
                if (!success) {
                    _uiState.value = _uiState.value.copy(
                        error = "Unable to open file. No suitable app found."
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to open file: ${e.message}"
                )
            }
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

data class ChatUiState(
    val isSending: Boolean = false,
    val error: String? = null
)
