package com.carlom.klardrop.chat

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.database.Messages
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.log
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DeviceChatViewModel(
    private val deviceId: String,
    val messageRepository: MessageRepository, // Made public val
    private val messenger: Messenger,
    private val visibleDevices: VisibleDevices,
    private val coroutines: Coroutines,
    private val fileManager: com.carlom.klardrop.common.FileManager, // Added
    private val platformFileSystem: PlatformFileSystem // Added for file operations
) {
    private val viewModelScope = CoroutineScope(coroutines.mainDispatcher + SupervisorJob())

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val messages: StateFlow<List<Messages>> =
        messageRepository.getMessagesForDevice(deviceId, limit = 100)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Mark messages as read when chat screen is opened
        viewModelScope.launch {
            messageRepository.markMessagesAsRead(deviceId)
        }
    }

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
                    fileTransferId = null,
                    isRead = true // Outgoing messages are read by default
                )

                // 2. Send the message over the network
                val isVisible = visibleDevices.isDeviceVisible(deviceId)
                if (isVisible) {
                    val textMessage = TextMessage(text = text)
                    messenger.send(deviceId, textMessage.toSimpleSendRequest())
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
    
    fun sendFiles(files: List<PlatformFile>) {
        if (files.isEmpty()) return

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isSending = true, error = null)
                
                // Check if device is visible
                val isVisible = visibleDevices.isDeviceVisible(deviceId)
                if (!isVisible) {
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        error = "Device not found or not connected"
                    )
                    return@launch
                }

                // Send each file
                files.forEach { file ->
                    val fileData = runCatching { platformFileSystem.getResolvedFileData(file) }
                        .onFailure { 
                            log("DeviceChatViewModel", "Unable to resolve file at path $file. File cannot be sent!", it) 
                        }
                        .getOrNull() 
                    
                    if (fileData != null) {
                        // 1. Insert file message into local DB
                        val fileTransferId = messageRepository.insertFileTransfer(
                            fileName = fileData.fileName,
                            filePath = file.path ?: fileData.fileName,
                            totalSize = fileData.fileSize,
                            status = com.carlom.klardrop.common.persistence.FileTransferStatus.IN_PROGRESS
                        )
                        
                        val messageId = messageRepository.insertMessage(
                            remoteDeviceId = deviceId,
                            content = fileData.fileName,
                            isSender = true,
                            messageType = MessageType.FILE,
                            fileTransferId = fileTransferId,
                            isRead = true // Outgoing messages are read by default
                        )

                        // 2. Send the file over the network
                        val fileMessage = FileMessage(
                            fileData.fileName,
                            fileData.fileSize,
                            fileData.mimeType
                        )
                        
                        messenger.send(deviceId, fileMessage.toSendRequest(file))
                            .untilCompleted()
                            .collect { progress ->
                                // Handle send progress if needed
                                // Progress updates are handled by the message handlers
                            }
                    }
                }
                
                _uiState.value = _uiState.value.copy(isSending = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    error = "Failed to send files: ${e.message}"
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
