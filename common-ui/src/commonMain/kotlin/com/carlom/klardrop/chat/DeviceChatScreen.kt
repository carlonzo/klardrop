package com.carlom.klardrop.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.database.Messages
import com.carlom.klardrop.common.persistence.FileTransferStatus
import com.carlom.klardrop.common.persistence.MessageRepository
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceChatScreen(
  deviceId: String,
  deviceName: String,
  viewModel: DeviceChatViewModel,
  onBackClicked: () -> Unit,
  onOpenFileRequest: (filePath: String) -> Unit // Added
) {
  val messagesState by viewModel.messages.collectAsState()
  val uiState by viewModel.uiState.collectAsState()
  val messageSendProgress by viewModel.messageSendProgress.collectAsState()
  var textToSend by remember { mutableStateOf("") }
  var showAttachmentMenu by remember { mutableStateOf(false) }

  // File pickers
  val filePickerLauncher = rememberFilePickerLauncher(mode = FileKitMode.Multiple()) { files ->
    if (!files.isNullOrEmpty()) {
      viewModel.sendFiles(files)
    }
  }

  val imagePickerLauncher = rememberFilePickerLauncher(
    mode = FileKitMode.Multiple(), 
    type = FileKitType.ImageAndVideo
  ) { files ->
    if (!files.isNullOrEmpty()) {
      viewModel.sendFiles(files)
    }
  }

  // Show error snackbar if there's an error
  uiState.error?.let { error ->
    LaunchedEffect(error) {
      // In a real app, you might want to show a Snackbar here
      // For now, just clear the error after a delay
      delay(3000)
      viewModel.clearError()
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(title = { Text(deviceName) }, navigationIcon = {
        IconButton(onClick = onBackClicked) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
      })
    }) { paddingValues ->
    Column(
      modifier = Modifier.fillMaxSize().padding(paddingValues).padding(8.dp)
    ) {
      if (messagesState.isEmpty()) {
        // Empty state
        Box(
          modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center
        ) {
          Text(
            text = "No messages yet. Start a conversation!", style = MaterialTheme.typography.bodyMedium, color = Color.Gray
          )
        }
      } else {
        LazyColumn(
          modifier = Modifier.weight(1f), reverseLayout = true // To show newest messages at the bottom
        ) {
          items(messagesState.sortedByDescending { it.timestamp }) { message -> // Ensure order again if not guaranteed by flow
            // Placeholder: Determine if it's a text or file message
            // This logic will need to be more robust, potentially fetching File_transfer details
            if (message.message_type == "FILE" && message.file_transfer_id != null) {
              FileMessageBubble(
                message = message,
                messageRepository = viewModel.messageRepository,
                onOpenFileRequest = { onOpenFileRequest(it) }
              ) // Pass repository
            } else {
              TextMessageBubble(
                message = message,
                sendProgress = messageSendProgress[message.id]
              )
            }
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically
      ) {
        // Attachment button with dropdown menu
        Box {
          IconButton(
            onClick = { showAttachmentMenu = true }
          ) {
            Icon(Icons.Default.Add, contentDescription = "Attach")
          }
          
          DropdownMenu(
            expanded = showAttachmentMenu,
            onDismissRequest = { showAttachmentMenu = false }
          ) {
            DropdownMenuItem(
              text = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Default.AttachFile, contentDescription = null)
                  Spacer(modifier = Modifier.width(8.dp))
                  Text("Files")
                }
              },
              onClick = {
                showAttachmentMenu = false
                filePickerLauncher.launch()
              }
            )
            DropdownMenuItem(
              text = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Default.Image, contentDescription = null)
                  Spacer(modifier = Modifier.width(8.dp))
                  Text("Photos & Videos")
                }
              },
              onClick = {
                showAttachmentMenu = false
                imagePickerLauncher.launch()
              }
            )
          }
        }
        
        OutlinedTextField(
          value = textToSend,
          onValueChange = { textToSend = it },
          label = { Text("Type a message") },
          modifier = Modifier.weight(1f),
          maxLines = 3
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
          onClick = {
            if (textToSend.isNotBlank()) {
              viewModel.sendTextMessage(textToSend)
              textToSend = ""
            }
          }, enabled = textToSend.isNotBlank()
        ) {
          Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
      }
    }
  }
}

@Composable
fun TextMessageBubble(
  message: Messages,
  sendProgress: com.carlom.klardrop.common.communication.MessengerSendProgress? = null
) {
  // Basic representation, align based on is_sender
  val horizontalArrangement = if (message.is_sender != 0L) Arrangement.End else Arrangement.Start
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = horizontalArrangement
  ) {
    Card(
      colors = CardDefaults.cardColors(
        containerColor = if (message.is_sender != 0L) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
      ), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
      Box(modifier = Modifier.padding(8.dp)) {
        Text(text = message.content)
        
        // Show status indicator for sent messages only
        if (message.is_sender != 0L && sendProgress != null) {
          when (sendProgress) {
            is com.carlom.klardrop.common.communication.MessengerSendProgress.Pending,
            is com.carlom.klardrop.common.communication.MessengerSendProgress.InProgress -> {
              CircularProgressIndicator(
                modifier = Modifier
                  .size(12.dp)
                  .align(Alignment.BottomEnd)
                  .padding(2.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
              )
            }
            is com.carlom.klardrop.common.communication.MessengerSendProgress.Error -> {
              Icon(
                imageVector = Icons.Default.Cancel,
                contentDescription = "Failed",
                modifier = Modifier
                  .size(12.dp)
                  .align(Alignment.BottomEnd),
                tint = Color.Red
              )
            }
            is com.carlom.klardrop.common.communication.MessengerSendProgress.Completed -> {
              // No indicator for completed messages
            }
          }
        }
      }
    }
  }
}

@Composable
fun FileMessageBubble(
  message: Messages, messageRepository: MessageRepository, onOpenFileRequest: (filePath: String) -> Unit
) {
  val fileTransferState by messageRepository.getFileTransferById(message.file_transfer_id ?: return).collectAsState(null)

  val isSender = message.is_sender != 0L
  val currentStatus = fileTransferState?.status
  val filePath = fileTransferState?.file_path
  val fileName = fileTransferState?.file_name ?: message.content

  val isCompletedReceivedFile = !isSender && currentStatus == FileTransferStatus.COMPLETED.name && filePath != null

  val bubbleModifier = if (isCompletedReceivedFile) {
    Modifier.clickable { onOpenFileRequest(filePath) }
  } else {
    Modifier
  }

  val horizontalArrangement = if (isSender) Arrangement.End else Arrangement.Start
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = horizontalArrangement
  ) {
    Card(
      colors = CardDefaults.cardColors(
        containerColor = if (isSender) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
      ), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), modifier = Modifier.widthIn(max = 280.dp).then(bubbleModifier)
    ) {
      Column(modifier = Modifier.padding(8.dp)) {
        when (currentStatus) {
          FileTransferStatus.IN_PROGRESS.name -> {
            Text(fileName, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Status: $currentStatus", style = MaterialTheme.typography.labelMedium)
            fileTransferState?.let { state ->
              if (state.total_size > 0) {
                LinearProgressIndicator(
                  progress = state.transferred_size.toFloat() / state.total_size.toFloat(),
                  modifier = Modifier.fillMaxWidth().height(4.dp).padding(vertical = 2.dp)
                )
                Text(
                  text = "${(state.transferred_size.toFloat() / 1024).toInt()}KB / ${(state.total_size.toFloat() / 1024).toInt()}KB",
                  style = MaterialTheme.typography.labelMedium
                )
              }
            }
          }

          FileTransferStatus.COMPLETED.name -> {
            Text(fileName, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Status: $currentStatus", style = MaterialTheme.typography.labelMedium)
            fileTransferState?.let { state ->
              Text(
                text = "Size: ${(state.total_size.toFloat() / 1024).toInt()}KB" + if (isCompletedReceivedFile) " (Click to open)" else "",
                style = MaterialTheme.typography.labelMedium
              )
            }
          }

          FileTransferStatus.FAILED.name -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text("⚠️", style = MaterialTheme.typography.labelLarge.copy(color = Color.Red)) // Larger icon
              Spacer(Modifier.width(8.dp))
              Text(
                text = fileName, style = MaterialTheme.typography.titleSmall.copy(textDecoration = TextDecoration.LineThrough)
              )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              "Transfer failed", style = MaterialTheme.typography.labelMedium.copy(color = Color.Red)
            )
          }

          else -> { // Fallback for null status or other states
            Text(fileName, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Loading file info...", style = MaterialTheme.typography.labelMedium)
          }
        }
      }
    }
  }
}
