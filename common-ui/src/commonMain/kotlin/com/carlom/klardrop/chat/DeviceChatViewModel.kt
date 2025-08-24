package com.carlom.klardrop.chat

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.database.Messages
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.log
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DeviceChatViewModel(
  private val deviceId: String,
  val messageRepository: MessageRepository,
  private val messenger: Messenger,
  private val coroutines: Coroutines,
  private val fileManager: FileManager,
  private val platformFileSystem: PlatformFileSystem
) {

  // TODO we need to dispose this viewmodel
  private val viewModelScope = CoroutineScope(coroutines.mainDispatcher + SupervisorJob())

  private val _uiState = MutableStateFlow(ChatUiState())
  internal val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()


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

        // Send the message - persistence is handled by TextMessageHandler
        val textMessage = TextMessage(text = text)
        sendMessage(textMessage.toSimpleSendRequest())

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
          _uiState.update {
            it.copy(error = "Unable to open file. No suitable app found.")
          }
        }
      } catch (e: Exception) {
        log("DeviceChatViewModel", "Failed to open file at path $filePath", e)

        _uiState.update {
          it.copy(error = "Failed to open file: ${e.message}")
        }
      }
    }
  }

  fun sendFiles(files: List<PlatformFile>) {
    if (files.isEmpty()) return

    viewModelScope.launch {
      try {
        _uiState.update { it.copy(error = null) }

        // Send each file - persistence is handled by FileMessageHandler
        files.forEach { file ->
          val fileData = runCatching { platformFileSystem.getResolvedFileData(file) }
            .onFailure {
              log("DeviceChatViewModel", "Unable to resolve file at path $file. File cannot be sent!", it)
            }
            .getOrNull()

          if (fileData != null) {
            val fileMessage = FileMessage(
              fileData.fileName,
              fileData.fileSize,
              fileData.mimeType
            )

            // Send the file - handler will create DB records and manage transfer
            sendMessage(fileMessage.toSendRequest(file))
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


  private suspend fun sendMessage(
    sendRequest: SendMessageRequest
  ) {

    val finalStatus = messenger.send(deviceId, sendRequest)
      .untilCompleted()
      .lastOrNull()

    if (finalStatus is MessengerSendProgress.Error) {
      _uiState.update {
        it.copy(error = "Failed to send message: ${finalStatus.message}")
      }
    }
  }

}

internal data class ChatUiState(
  val error: String? = null
)
