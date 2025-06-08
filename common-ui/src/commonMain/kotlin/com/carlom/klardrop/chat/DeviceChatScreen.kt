package com.carlom.klardrop.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color // Added
import androidx.compose.ui.text.style.TextDecoration // Added
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.database.File_transfers
import com.carlom.klardrop.common.database.Messages
import com.carlom.klardrop.common.persistence.FileTransferStatus // Added for enum access
import com.carlom.klardrop.common.persistence.MessageRepository

@Composable
fun DeviceChatScreen(
    deviceId: String,
    deviceName: String,
    viewModel: DeviceChatViewModel,
    onBackClicked: () -> Unit,
    onOpenFileRequest: (filePath: String) -> Unit // Added
) {
    val messagesState by viewModel.messages.collectAsState()
    var textToSend by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(deviceName) },
                navigationIcon = {
                    IconButton(onClick = onBackClicked) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(8.dp)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                reverseLayout = true // To show newest messages at the bottom
            ) {
                items(messagesState.sortedByDescending { it.timestamp }) { message -> // Ensure order again if not guaranteed by flow
                    // Placeholder: Determine if it's a text or file message
                    // This logic will need to be more robust, potentially fetching File_transfer details
                    if (message.message_type == "FILE" && message.file_transfer_id != null) {
                        FileMessageBubble(message = message, messageRepository = viewModel.messageRepository) // Pass repository
                    } else {
                        TextMessageBubble(message = message)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                    },
                    enabled = textToSend.isNotBlank()
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
fun TextMessageBubble(message: Messages) {
    // Basic representation, align based on is_sender
    val horizontalArrangement = if (message.is_sender) Arrangement.End else Arrangement.Start
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = horizontalArrangement
    ) {
        Card(
            backgroundColor = if (message.is_sender) MaterialTheme.colors.primaryVariant else MaterialTheme.colors.secondaryVariant,
            elevation = 2.dp
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
fun FileMessageBubble(
    message: Messages,
    messageRepository: MessageRepository,
    onOpenFileRequest: (filePath: String) -> Unit
) {
    val fileTransferState by messageRepository.getFileTransferById(message.file_transfer_id!!)
        .collectAsState(null)

    val isSender = message.is_sender
    val currentStatus = fileTransferState?.status
    val filePath = fileTransferState?.file_path
    val fileName = fileTransferState?.file_name ?: message.content

    val isCompletedReceivedFile = !isSender && currentStatus == FileTransferStatus.COMPLETED.name && filePath != null

    val bubbleModifier = if (isCompletedReceivedFile) {
        Modifier.clickable { onOpenFileRequest(filePath!!) }
    } else {
        Modifier
    }

    val horizontalArrangement = if (isSender) Arrangement.End else Arrangement.Start
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = horizontalArrangement
    ) {
        Card(
            backgroundColor = if (isSender) MaterialTheme.colors.primaryVariant else MaterialTheme.colors.secondaryVariant,
            elevation = 2.dp,
            modifier = Modifier.widthIn(max = 280.dp).then(bubbleModifier)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                when (currentStatus) {
                    FileTransferStatus.IN_PROGRESS.name -> {
                        Text(fileName, style = MaterialTheme.typography.subtitle1)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Status: $currentStatus", style = MaterialTheme.typography.caption)
                        if (fileTransferState!!.total_size > 0) {
                            LinearProgressIndicator(
                                progress = fileTransferState!!.transferred_size.toFloat() / fileTransferState!!.total_size.toFloat(),
                                modifier = Modifier.fillMaxWidth().height(4.dp).padding(vertical = 2.dp)
                            )
                            Text(
                                text = "${(fileTransferState!!.transferred_size.toFloat() / 1024).toInt()}KB / ${(fileTransferState!!.total_size.toFloat() / 1024).toInt()}KB",
                                style = MaterialTheme.typography.caption
                            )
                        }
                    }
                    FileTransferStatus.COMPLETED.name -> {
                        Text(fileName, style = MaterialTheme.typography.subtitle1)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Status: $currentStatus", style = MaterialTheme.typography.caption)
                        Text(
                            text = "Size: ${(fileTransferState!!.total_size.toFloat() / 1024).toInt()}KB" + if (isCompletedReceivedFile) " (Click to open)" else "",
                            style = MaterialTheme.typography.caption
                        )
                    }
                    FileTransferStatus.FAILED.name -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⚠️", style = MaterialTheme.typography.h6.copy(color = Color.Red)) // Larger icon
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = fileName,
                                style = MaterialTheme.typography.subtitle1.copy(textDecoration = TextDecoration.LineThrough)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Transfer failed",
                            style = MaterialTheme.typography.caption.copy(color = Color.Red)
                        )
                    }
                    else -> { // Fallback for null status or other states
                        Text(fileName, style = MaterialTheme.typography.subtitle1)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Loading file info...", style = MaterialTheme.typography.caption)
                    }
                }
            }
        }
    }
}
