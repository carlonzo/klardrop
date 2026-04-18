package com.carlom.klardrop.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.carlom.klardrop.common.database.Messages
import com.carlom.klardrop.common.persistence.FileTransferStatus
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType
import com.carlom.klardrop.common.utils.FileTypeUtils
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher

private const val GROUP_GAP_MILLIS: Long = 5 * 60 * 1000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceChatScreen(
  deviceName: String,
  isOwned: Boolean,
  viewModel: DeviceChatViewModel,
  onBackClicked: () -> Unit,
  onOpenFileRequest: (filePath: String) -> Unit
) {
  val messagesState by viewModel.messages.collectAsState()
  val uiState by viewModel.uiState.collectAsState()
  var textToSend by remember { mutableStateOf("") }
  var attachmentMenuOpen by remember { mutableStateOf(false) }

  val snackbarHostState = remember { SnackbarHostState() }

  val filePickerLauncher = rememberFilePickerLauncher(mode = FileKitMode.Multiple()) { files ->
    if (!files.isNullOrEmpty()) {
      viewModel.sendFiles(files)
    }
    attachmentMenuOpen = false
  }

  val imagePickerLauncher = rememberFilePickerLauncher(
    mode = FileKitMode.Multiple(),
    type = FileKitType.ImageAndVideo
  ) { files ->
    if (!files.isNullOrEmpty()) {
      viewModel.sendFiles(files)
    }
    attachmentMenuOpen = false
  }

  uiState.error?.let { error ->
    LaunchedEffect(error) {
      snackbarHostState.showSnackbar(error)
      viewModel.clearError()
    }
  }

  val sortedMessages by remember(messagesState) {
    derivedStateOf { messagesState.sortedByDescending { it.timestamp } }
  }

  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        title = {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              text = deviceName,
              style = MaterialTheme.typography.titleMedium
            )
            if (isOwned) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Person,
                  contentDescription = null,
                  modifier = Modifier.size(12.dp),
                  tint = MaterialTheme.colorScheme.primary
                )
                Text(
                  text = "Your device",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary
                )
              }
            }
          }
        },
        navigationIcon = {
          IconButton(onClick = onBackClicked) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface
        )
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) }
  ) { paddingValues ->
    val layoutDirection = LocalLayoutDirection.current
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(
          top = paddingValues.calculateTopPadding(),
          start = paddingValues.calculateStartPadding(layoutDirection),
          end = paddingValues.calculateEndPadding(layoutDirection)
        )
    ) {
      if (sortedMessages.isEmpty()) {
        ChatEmptyState(
          deviceName = deviceName,
          isOwned = isOwned,
          modifier = Modifier.weight(1f)
        )
      } else {
        MessagesList(
          messages = sortedMessages,
          messageRepository = viewModel.messageRepository,
          onOpenFileRequest = onOpenFileRequest,
          modifier = Modifier.weight(1f)
        )
      }

      ChatInputBar(
        text = textToSend,
        onTextChange = { textToSend = it },
        onSend = {
          if (textToSend.isNotBlank()) {
            viewModel.sendTextMessage(textToSend)
            textToSend = ""
          }
        },
        attachmentMenuOpen = attachmentMenuOpen,
        onToggleAttachmentMenu = { attachmentMenuOpen = !attachmentMenuOpen },
        onPickFiles = { filePickerLauncher.launch() },
        onPickMedia = { imagePickerLauncher.launch() },
        bottomPadding = paddingValues.calculateBottomPadding()
      )
    }
  }
}

@Composable
private fun ChatEmptyState(
  deviceName: String,
  isOwned: Boolean,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(32.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(
      modifier = Modifier
        .size(72.dp)
        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = Icons.Default.Person,
        contentDescription = null,
        modifier = Modifier.size(36.dp),
        tint = MaterialTheme.colorScheme.onPrimaryContainer
      )
    }
    Spacer(Modifier.height(16.dp))
    Text(
      text = if (isOwned) "Connected to $deviceName" else "Send something to $deviceName",
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(6.dp))
    Text(
      text = if (isOwned) {
        "Anything you send here stays in sync across your devices."
      } else {
        "Type a message or attach a file to start the conversation."
      },
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

@Composable
private fun MessagesList(
  messages: List<Messages>,
  messageRepository: MessageRepository,
  onOpenFileRequest: (filePath: String) -> Unit,
  modifier: Modifier = Modifier
) {
  LazyColumn(
    modifier = modifier.fillMaxWidth(),
    reverseLayout = true,
    contentPadding = androidx.compose.foundation.layout.PaddingValues(
      horizontal = 12.dp,
      vertical = 12.dp
    )
  ) {
    items(
      items = messages,
      key = { it.id }
    ) { message ->
      val index = messages.indexOf(message)

      // In reverseLayout, "previous" visually = next index (older).
      val older = messages.getOrNull(index + 1)
      val newer = messages.getOrNull(index - 1)

      val isFirstOfGroup = older == null ||
        older.is_sender != message.is_sender ||
        message.timestamp - older.timestamp > GROUP_GAP_MILLIS

      val isLastOfGroup = newer == null ||
        newer.is_sender != message.is_sender ||
        newer.timestamp - message.timestamp > GROUP_GAP_MILLIS

      val showDayDivider = older == null ||
        chatDayKey(older.timestamp) != chatDayKey(message.timestamp)

      MessageRow(
        message = message,
        messageRepository = messageRepository,
        onOpenFileRequest = onOpenFileRequest,
        isFirstOfGroup = isFirstOfGroup,
        isLastOfGroup = isLastOfGroup,
        showTimestamp = isLastOfGroup
      )

      if (showDayDivider) {
        DayDivider(message.timestamp)
      }
    }
  }
}

@Composable
private fun DayDivider(epochMillis: Long) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 12.dp),
    horizontalArrangement = Arrangement.Center
  ) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surfaceVariant
    ) {
      Text(
        text = formatChatDay(epochMillis),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
      )
    }
  }
}

@Composable
private fun MessageRow(
  message: Messages,
  messageRepository: MessageRepository,
  onOpenFileRequest: (filePath: String) -> Unit,
  isFirstOfGroup: Boolean,
  isLastOfGroup: Boolean,
  showTimestamp: Boolean
) {
  val isSender = message.is_sender != 0L
  val arrangement = if (isSender) Arrangement.End else Arrangement.Start
  val topPadding = if (isFirstOfGroup) 8.dp else 2.dp

  Column {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = topPadding),
      horizontalArrangement = arrangement
    ) {
      if (message.message_type == MessageType.FILE.name && message.file_transfer_id != null) {
        FileMessageBubble(
          message = message,
          messageRepository = messageRepository,
          onOpenFileRequest = onOpenFileRequest,
          shape = bubbleShape(isSender, isFirstOfGroup, isLastOfGroup)
        )
      } else if (message.message_type == MessageType.TEXT.name) {
        TextMessageBubble(
          message = message,
          shape = bubbleShape(isSender, isFirstOfGroup, isLastOfGroup)
        )
      } else {
        UnknownMessageBubble(message)
      }
    }

    if (showTimestamp) {
      Text(
        text = formatChatTime(message.timestamp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 2.dp),
        textAlign = if (isSender) {
          androidx.compose.ui.text.style.TextAlign.End
        } else {
          androidx.compose.ui.text.style.TextAlign.Start
        }
      )
    }
  }
}

private fun bubbleShape(isSender: Boolean, isFirstOfGroup: Boolean, isLastOfGroup: Boolean): Shape {
  val big = 18.dp
  val small = 6.dp
  return if (isSender) {
    RoundedCornerShape(
      topStart = big,
      topEnd = if (isFirstOfGroup) big else small,
      bottomEnd = if (isLastOfGroup) big else small,
      bottomStart = big
    )
  } else {
    RoundedCornerShape(
      topStart = if (isFirstOfGroup) big else small,
      topEnd = big,
      bottomEnd = big,
      bottomStart = if (isLastOfGroup) big else small
    )
  }
}

@Composable
private fun TextMessageBubble(
  message: Messages,
  shape: Shape
) {
  val isSender = message.is_sender != 0L
  val container = if (isSender) {
    MaterialTheme.colorScheme.primaryContainer
  } else {
    MaterialTheme.colorScheme.surfaceVariant
  }
  val onContainer = if (isSender) {
    MaterialTheme.colorScheme.onPrimaryContainer
  } else {
    MaterialTheme.colorScheme.onSurface
  }

  Surface(
    shape = shape,
    color = container,
    modifier = Modifier.widthIn(max = 320.dp)
  ) {
    SelectionContainer {
      Text(
        text = message.content,
        style = MaterialTheme.typography.bodyLarge,
        color = onContainer,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
      )
    }
  }
}

@Composable
private fun UnknownMessageBubble(message: Messages) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.errorContainer
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Icon(
        imageVector = Icons.Default.ErrorOutline,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.size(16.dp)
      )
      Text(
        text = "Unsupported message (${message.message_type})",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer
      )
    }
  }
}

@Composable
private fun FileMessageBubble(
  message: Messages,
  messageRepository: MessageRepository,
  onOpenFileRequest: (filePath: String) -> Unit,
  shape: Shape
) {
  val fileTransferState by messageRepository.getFileTransferById(message.file_transfer_id ?: return).collectAsState(null)

  val isSender = message.is_sender != 0L
  val currentStatus = fileTransferState?.status
  val filePath = fileTransferState?.file_path
  val fileName = fileTransferState?.file_name ?: message.content

  val isCompletedReceivedFile = !isSender &&
    currentStatus == FileTransferStatus.COMPLETED.name &&
    filePath != null

  val container = if (isSender) {
    MaterialTheme.colorScheme.primaryContainer
  } else {
    MaterialTheme.colorScheme.surfaceVariant
  }
  val onContainer = if (isSender) {
    MaterialTheme.colorScheme.onPrimaryContainer
  } else {
    MaterialTheme.colorScheme.onSurface
  }

  val bubbleModifier = if (isCompletedReceivedFile) {
    Modifier.clickable { onOpenFileRequest(filePath!!) }
  } else {
    Modifier
  }

  Surface(
    shape = shape,
    color = container,
    modifier = Modifier
      .widthIn(max = 280.dp)
      .then(bubbleModifier)
  ) {
    Column(modifier = Modifier.padding(10.dp)) {
      when (currentStatus) {
        FileTransferStatus.IN_PROGRESS.name -> {
          FileBubbleHeader(fileName, onContainer)
          Spacer(Modifier.height(6.dp))
          fileTransferState?.let { state ->
            if (state.total_size > 0) {
              LinearProgressIndicator(
                progress = { (state.transferred_size.toFloat() / state.total_size) },
                modifier = Modifier
                  .fillMaxWidth()
                  .height(4.dp)
              )
              Spacer(Modifier.height(4.dp))
              Text(
                text = "${formatBytes(state.transferred_size)} of ${formatBytes(state.total_size)}",
                style = MaterialTheme.typography.labelSmall,
                color = onContainer.copy(alpha = 0.7f)
              )
            } else {
              Text(
                text = "Sending…",
                style = MaterialTheme.typography.labelSmall,
                color = onContainer.copy(alpha = 0.7f)
              )
            }
          }
        }

        FileTransferStatus.COMPLETED.name -> {
          if (filePath != null && fileTransferState?.mime_type?.let { FileTypeUtils.isImageOrVideoMimeType(it) } == true) {
            AsyncImage(
              model = filePath,
              contentDescription = fileName,
              modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
              contentScale = ContentScale.FillWidth
            )
          }
          FileBubbleHeader(fileName, onContainer)
          Spacer(Modifier.height(2.dp))
          Text(
            text = buildString {
              append(formatBytes(fileTransferState?.total_size ?: 0L))
              if (isCompletedReceivedFile) append(" · Tap to open")
            },
            style = MaterialTheme.typography.labelSmall,
            color = onContainer.copy(alpha = 0.7f)
          )
        }

        FileTransferStatus.FAILED.name -> {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.ErrorOutline,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
              text = fileName,
              style = MaterialTheme.typography.titleSmall.copy(textDecoration = TextDecoration.LineThrough),
              color = onContainer
            )
          }
          Spacer(Modifier.height(4.dp))
          Text(
            text = "Transfer failed",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
          )
        }

        else -> {
          FileBubbleHeader(fileName, onContainer)
          Spacer(Modifier.height(2.dp))
          Text(
            text = "Preparing…",
            style = MaterialTheme.typography.labelSmall,
            color = onContainer.copy(alpha = 0.7f)
          )
        }
      }
    }
  }
}

@Composable
private fun FileBubbleHeader(fileName: String, onContainer: Color) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(
      imageVector = Icons.Default.InsertDriveFile,
      contentDescription = null,
      tint = onContainer.copy(alpha = 0.8f),
      modifier = Modifier.size(18.dp)
    )
    Spacer(Modifier.width(8.dp))
    Text(
      text = fileName,
      style = MaterialTheme.typography.titleSmall,
      color = onContainer,
      maxLines = 2
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatInputBar(
  text: String,
  onTextChange: (String) -> Unit,
  onSend: () -> Unit,
  attachmentMenuOpen: Boolean,
  onToggleAttachmentMenu: () -> Unit,
  onPickFiles: () -> Unit,
  onPickMedia: () -> Unit,
  bottomPadding: androidx.compose.ui.unit.Dp
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp
  ) {
    Column(
      modifier = Modifier.padding(
        start = 8.dp,
        end = 8.dp,
        top = 8.dp,
        bottom = 8.dp + bottomPadding
      )
    ) {
      AnimatedVisibility(
        visible = attachmentMenuOpen,
        enter = fadeIn() + expandHorizontally(),
        exit = fadeOut() + shrinkHorizontally()
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp, start = 4.dp, end = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          AttachmentChip(
            label = "Files",
            icon = Icons.Default.AttachFile,
            onClick = onPickFiles
          )
          AttachmentChip(
            label = "Photos & videos",
            icon = Icons.Default.Image,
            onClick = onPickMedia
          )
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggleAttachmentMenu) {
          Icon(
            imageVector = Icons.Default.AttachFile,
            contentDescription = if (attachmentMenuOpen) "Hide attachments" else "Attach"
          )
        }

        OutlinedTextField(
          value = text,
          onValueChange = onTextChange,
          placeholder = { Text("Message") },
          modifier = Modifier.weight(1f),
          maxLines = 4,
          shape = RoundedCornerShape(20.dp)
        )

        Spacer(Modifier.width(8.dp))

        val canSend = text.isNotBlank()
        Surface(
          shape = CircleShape,
          color = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
          modifier = Modifier
            .size(44.dp)
            .clickable(enabled = canSend, onClick = onSend)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Send,
              contentDescription = "Send",
              tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }
    }
  }
}

@Composable
private fun AttachmentChip(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  onClick: () -> Unit
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.clickable(onClick = onClick)
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface
      )
    }
  }
}

private fun formatBytes(bytes: Long): String {
  if (bytes < 1024) return "$bytes B"
  val kb = bytes / 1024.0
  if (kb < 1024) return "${kb.formatOneDecimal()} KB"
  val mb = kb / 1024.0
  if (mb < 1024) return "${mb.formatOneDecimal()} MB"
  val gb = mb / 1024.0
  return "${gb.formatOneDecimal()} GB"
}

private fun Double.formatOneDecimal(): String {
  val rounded = (this * 10).toLong() / 10.0
  val whole = rounded.toLong()
  val frac = ((rounded - whole) * 10).toLong()
  return if (frac == 0L) "$whole" else "$whole.$frac"
}
