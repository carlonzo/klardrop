package com.carlom.klardrop.chat

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DeviceChatViewModel(
  private val deviceId: String,
  val messageRepository: MessageRepository, // Made public val
  private val messenger: Messenger,
  private val visibleDevices: VisibleDevices,
  private val coroutines: Coroutines,
  private val fileManager: FileManager, // Added
  private val platformFileSystem: PlatformFileSystem // Added for file operations
) {

  // TODO we need to dispose this viewmodel
  private val viewModelScope = CoroutineScope(coroutines.mainDispatcher + SupervisorJob())

  private val _uiState = MutableStateFlow(ChatUiState())
  val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

  // Track message sending progress by messageId
  private val _messageSendProgress = MutableStateFlow<Map<Long, MessengerSendProgress>>(emptyMap())
  val messageSendProgress: StateFlow<Map<Long, MessengerSendProgress>> = _messageSendProgress.asStateFlow()

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
        _uiState.value = _uiState.value.copy(error = null)

        // 1. Optimistically insert into local DB
        val messageId = messageRepository.insertMessage(
          remoteDeviceId = deviceId,
          content = text,
          isSender = true,
          messageType = MessageType.TEXT,
          fileTransferId = null,
          isRead = true // Outgoing messages are read by default
        )

        // 2. Send the message using common logic
        val textMessage = TextMessage(text = text)
        sendMessage(messageId, textMessage.toSimpleSendRequest())

      } catch (e: Exception) {
        _uiState.value = _uiState.value.copy(
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
        _uiState.value = _uiState.value.copy(error = null)

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
              filePath = file.path,
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

            // 2. Send the file using common logic with special handling for file transfers
            val fileMessage = FileMessage(
              fileData.fileName,
              fileData.fileSize,
              fileData.mimeType
            )

            sendFileMessage(messageId, fileTransferId, fileMessage.toSendRequest(file))
          }
        }

      } catch (e: Exception) {
        _uiState.value = _uiState.value.copy(
          error = "Failed to send files: ${e.message}"
        )
      }
    }
  }

  fun clearError() {
    _uiState.value = _uiState.value.copy(error = null)
  }

  // Helper methods for message progress tracking
  private fun updateMessageProgress(messageId: Long, progress: MessengerSendProgress) {
    _messageSendProgress.update { currentMap ->
      currentMap + (messageId to progress)
    }
  }

  private fun clearMessageProgress(messageId: Long) {
    _messageSendProgress.update { currentMap ->
      currentMap - messageId
    }
  }

  // Common method for device visibility check and error handling
  private suspend fun sendMessage(
    messageId: Long,
    sendRequest: com.carlom.klardrop.common.communication.message.SendMessageRequest
  ) {
    val isVisible = visibleDevices.isDeviceVisible(deviceId)
    if (isVisible) {
      messenger.send(deviceId, sendRequest)
        .collect { progress ->
          when (progress) {
            is MessengerSendProgress.Error -> {
              updateMessageProgress(messageId, progress)
              _uiState.update {
                it.copy(error = "Failed to send message: ${progress.message}")
              }
            }
            is MessengerSendProgress.Completed -> {
              clearMessageProgress(messageId)
            }
            else -> {
              updateMessageProgress(messageId, progress)
            }
          }
        }
    } else {
      updateMessageProgress(messageId, MessengerSendProgress.Error("Device not found or not connected"))
      _uiState.update {
        it.copy(error = "Device not found or not connected")
      }
    }
  }

  // Special handling for file messages that need to update FileTransferStatus
  // Note: File messages use FileTransferStatus as single source of truth for UI,
  // so we don't track progress in messageSendProgress map
  private suspend fun sendFileMessage(
    messageId: Long,
    fileTransferId: Long,
    sendRequest: com.carlom.klardrop.common.communication.message.SendMessageRequest
  ) {
    val isVisible = visibleDevices.isDeviceVisible(deviceId)
    if (isVisible) {
      messenger.send(deviceId, sendRequest)
        .untilCompleted() // File transfers are managed by separate message handlers
        .collect { progress ->
          when (progress) {
            is MessengerSendProgress.Error -> {
              // Update file transfer status - UI will react to this change
              messageRepository.updateFileTransferStatus(
                fileTransferId,
                com.carlom.klardrop.common.persistence.FileTransferStatus.FAILED
              )
              _uiState.update {
                it.copy(error = "Failed to send file: ${progress.message}")
              }
            }
            is MessengerSendProgress.Completed -> {
              // File transfer status will be updated by message handlers
            }
            else -> {
              // Progress updates handled by existing message handlers that update FileTransferStatus
            }
          }
        }
    } else {
      messageRepository.updateFileTransferStatus(
        fileTransferId,
        com.carlom.klardrop.common.persistence.FileTransferStatus.FAILED
      )
      _uiState.update {
        it.copy(error = "Device not found or not connected")
      }
    }
  }
}

data class ChatUiState(
  val error: String? = null
)
