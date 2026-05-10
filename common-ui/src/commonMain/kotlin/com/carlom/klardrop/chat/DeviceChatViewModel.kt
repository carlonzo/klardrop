package com.carlom.klardrop.chat

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.database.Messages
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

class DeviceChatViewModel(
  private val deviceId: String,
  val messageRepository: MessageRepository,
  private val messenger: Messenger,
  private val messageReceiver: MessageReceiver,
  private val coroutines: Coroutines,
  private val fileManager: FileManager,
  private val platformFileSystem: PlatformFileSystem,
  reachabilitySource: StateFlow<Map<String, Reachability>>,
) {

  // TODO we need to dispose this viewmodel
  private val viewModelScope = CoroutineScope(coroutines.mainDispatcher + SupervisorJob())

  private val _uiState = MutableStateFlow(ChatUiState())
  internal val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

  val reachability: StateFlow<Reachability> =
    reachabilitySource
      .map { it[deviceId] ?: Reachability.Unknown }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Reachability.Unknown)


  val messages: StateFlow<List<Messages>> =
    messageRepository.getMessagesForDevice(deviceId, limit = 100)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  /**
   * Most recent receive update from this device that's awaiting the user's accept/reject
   * decision. Null when nothing is pending. Subscribes directly to the per-device receive
   * flow (a StateFlow with current value) instead of [Messenger.receive] which is a
   * replay-less notifier — that previously meant the chat screen missed prompts that
   * fired before it was opened, leaving them only on the discovery-screen banner stack.
   * Going through [MessageReceiver.onReceiveMessage] gives us the current pending state
   * the moment the chat screen subscribes, regardless of timing.
   */
  val pendingAuth: StateFlow<ReceiveMessageUpdate?> =
    messageReceiver.onReceiveMessage(deviceId)
      .map { update -> update.takeIf { it.status is ReceiveMessageStatus.PendingAuthorization } }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

  fun openUrlClicked(url: String) {
    viewModelScope.launch {
      try {
        val success = fileManager.openUrl(url)
        if (!success) {
          _uiState.update {
            it.copy(error = "Unable to open link. No handler found.")
          }
        }
      } catch (e: Exception) {
        log("DeviceChatViewModel", "Failed to open url $url", e)
        _uiState.update {
          it.copy(error = "Failed to open link: ${e.message}")
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

  fun retryFileTransfer(failedFileTransferId: Long) {
    viewModelScope.launch {
      val row = messageRepository.getFileTransferById(failedFileTransferId).first() ?: run {
        _uiState.update { it.copy(error = "Could not find the original file to retry.") }
        return@launch
      }
      val sourcePath = row.file_path
      if (sourcePath.isBlank()) {
        _uiState.update { it.copy(error = "The original file path is no longer available.") }
        return@launch
      }
      val file = runCatching { PlatformFile(Path(sourcePath)) }.getOrNull()
      if (file == null) {
        _uiState.update { it.copy(error = "Could not reopen the original file.") }
        return@launch
      }
      sendFiles(listOf(file))
    }
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
