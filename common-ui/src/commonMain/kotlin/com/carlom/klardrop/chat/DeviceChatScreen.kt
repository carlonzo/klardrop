package com.carlom.klardrop.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.OnDataToSend
import com.carlom.klardrop.components.Banner
import com.carlom.klardrop.components.Bubble
import com.carlom.klardrop.components.ChatHeader
import com.carlom.klardrop.components.DateChip
import com.carlom.klardrop.components.DeviceAvatar
import com.carlom.klardrop.components.FileCard
import com.carlom.klardrop.components.KdAvatarStyle
import com.carlom.klardrop.components.KdBannerTone
import com.carlom.klardrop.components.KdBubbleDirection
import com.carlom.klardrop.components.KdDeviceKind
import com.carlom.klardrop.components.KdFileState
import com.carlom.klardrop.components.KdStatus
import com.carlom.klardrop.components.MessageInput
import com.carlom.klardrop.dropTargetForSending
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.communication.message.FileMessage as ProtoFileMessage
import com.carlom.klardrop.common.communication.message.TextMessage as ProtoTextMessage
import com.carlom.klardrop.common.database.Messages
import com.carlom.klardrop.common.persistence.FileTransferStatus
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.theme.KdTheme
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher

private const val GROUP_GAP_MILLIS: Long = 5 * 60 * 1000L

enum class DeviceChatMode { Screen, Pane }

@Composable
fun DeviceChatScreen(
    deviceName: String,
    isOwned: Boolean,
    viewModel: DeviceChatViewModel,
    onBackClicked: () -> Unit,
    onOpenFileRequest: (filePath: String) -> Unit,
    onOpenUrlRequest: (url: String) -> Unit,
    mode: DeviceChatMode = DeviceChatMode.Screen,
) {
    val messagesState by viewModel.messages.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val pendingAuth by viewModel.pendingAuth.collectAsState()
    val reachability by viewModel.reachability.collectAsState()
    var textToSend by remember { mutableStateOf("") }
    var dropHovered by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    val filePickerLauncher = rememberFilePickerLauncher(mode = FileKitMode.Multiple()) { files ->
        if (!files.isNullOrEmpty()) viewModel.sendFiles(files)
    }

    val imagePickerLauncher = rememberFilePickerLauncher(
        mode = FileKitMode.Multiple(),
        type = FileKitType.ImageAndVideo,
    ) { files ->
        if (!files.isNullOrEmpty()) viewModel.sendFiles(files)
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

    val isOffline = !isOwned && reachability == Reachability.Unreachable

    val headerStatus: KdStatus? = when {
        isOwned -> null
        reachability == Reachability.Reachable -> KdStatus.Ok
        reachability == Reachability.Unreachable -> KdStatus.Err
        else -> KdStatus.Warn
    }

    val headerSubText = when {
        isOwned -> "Your device"
        reachability == Reachability.Reachable -> "Reachable"
        reachability == Reachability.Unreachable -> "Offline"
        else -> "Connecting…"
    }

    val headerAvatarStyle = if (isOwned) KdAvatarStyle.Tinted else KdAvatarStyle.Neutral

    val dropModifier = Modifier.dropTargetForSending(
        onDataDropped = { data ->
            when (data) {
                is OnDataToSend.FilesList -> if (data.files.isNotEmpty()) viewModel.sendFiles(data.files)
                is OnDataToSend.Text -> if (data.text.isNotBlank()) viewModel.sendTextMessage(data.text)
                is OnDataToSend.WifiCredentials -> Unit
            }
        },
        onDragStateChange = { dropHovered = it },
    )

    val colors = KdTheme.colors
    val spacing = KdTheme.spacing

    Scaffold(
        modifier = dropModifier,
        topBar = {
            ChatHeader(
                deviceName = deviceName,
                subText = headerSubText,
                kind = KdDeviceKind.Unknown,
                avatarStyle = headerAvatarStyle,
                status = headerStatus,
                isReachable = !isOffline,
                toolbarVariant = mode == DeviceChatMode.Pane,
                onBack = onBackClicked,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = colors.bg0,
    ) { paddingValues ->
        val dropTint = if (dropHovered) colors.accentBg else colors.bg0

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .background(dropTint),
        ) {
            pendingAuth?.let { update ->
                IncomingAuthBanner(
                    update = update,
                    modifier = Modifier.padding(horizontal = spacing.s3, vertical = spacing.s2),
                )
            }

            if (isOffline) {
                Banner(
                    tone = KdBannerTone.Err,
                    title = "Device is offline",
                    body = "You'll be reconnected automatically when the device is reachable.",
                    modifier = Modifier.padding(horizontal = spacing.s3, vertical = spacing.s2),
                )
            }

            if (sortedMessages.isEmpty()) {
                ChatEmptyState(
                    deviceName = deviceName,
                    isOwned = isOwned,
                    onPickFiles = { filePickerLauncher.launch() },
                    onPickPhotos = { imagePickerLauncher.launch() },
                    modifier = Modifier.weight(1f),
                )
            } else {
                MessagesList(
                    messages = sortedMessages,
                    messageRepository = viewModel.messageRepository,
                    onOpenFileRequest = onOpenFileRequest,
                    onOpenUrlRequest = onOpenUrlRequest,
                    isOffline = isOffline,
                    modifier = Modifier.weight(1f),
                )
            }

            MessageInput(
                value = textToSend,
                onValueChange = { textToSend = it },
                onSend = {
                    if (textToSend.isNotBlank()) {
                        viewModel.sendTextMessage(textToSend)
                        textToSend = ""
                    }
                },
                onAttach = { filePickerLauncher.launch() },
                enabled = !isOffline,
                desktopVariant = mode == DeviceChatMode.Pane,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bg0)
                    .padding(horizontal = spacing.s3, vertical = spacing.s2)
                    .navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun IncomingAuthBanner(
    update: ReceiveMessageUpdate,
    modifier: Modifier = Modifier,
) {
    val status = update.status as? ReceiveMessageStatus.PendingAuthorization ?: return
    val sender = update.device?.name ?: "this device"
    val itemCount = update.messages.size
    val preview = update.messages.firstOrNull()?.let { msg ->
        when (msg) {
            is ProtoTextMessage -> "“${msg.text.take(80)}”"
            is ProtoFileMessage -> msg.fileName
            else -> null
        }
    }

    Banner(
        tone = KdBannerTone.Warn,
        title = "$sender wants to send you ${if (itemCount == 1) "an item" else "$itemCount items"}",
        body = preview,
        trailing = {
            TextButton(onClick = { status.acceptTransfer(false) }) {
                Text("Reject", color = KdTheme.colors.text2)
            }
            TextButton(onClick = { status.acceptTransfer(true) }) {
                Text("Accept", color = KdTheme.colors.accent)
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatEmptyState(
    deviceName: String,
    isOwned: Boolean,
    onPickFiles: () -> Unit,
    onPickPhotos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing
    val radii = KdTheme.radii

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DeviceAvatar(
                kind = KdDeviceKind.Unknown,
                style = if (isOwned) KdAvatarStyle.Tinted else KdAvatarStyle.Neutral,
                size = spacing.heroAvatar,
            )

            Spacer(Modifier.height(spacing.gap))

            Text(
                text = if (isOwned) "Connected to $deviceName" else "Send something to $deviceName",
                style = typography.headline.copy(color = colors.text),
            )

            Spacer(Modifier.height(spacing.gap))

            Text(
                text = if (isOwned) {
                    "Anything you send here stays in sync across your devices."
                } else {
                    "Type a message or attach a file to start the conversation."
                },
                style = typography.body.copy(color = colors.text2),
            )

            Spacer(Modifier.height(spacing.gap))

            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.s2),
            ) {
                listOf(
                    "Files" to onPickFiles,
                    "Photos" to onPickPhotos,
                ).forEach { (label, action) ->
                    Box(
                        modifier = Modifier
                            .background(colors.bg1, radii.shapePill)
                            .combinedClickable(onClick = action)
                            .padding(horizontal = spacing.s3, vertical = spacing.s2),
                    ) {
                        Text(
                            text = label,
                            style = typography.body.copy(color = colors.text2),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .background(colors.bg1, radii.shapePill)
                        .padding(horizontal = spacing.s3, vertical = spacing.s2),
                ) {
                    Text(
                        text = "Text",
                        style = typography.body.copy(color = colors.text2),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessagesList(
    messages: List<Messages>,
    messageRepository: MessageRepository,
    onOpenFileRequest: (filePath: String) -> Unit,
    onOpenUrlRequest: (url: String) -> Unit,
    isOffline: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.firstOrNull()?.id) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (listState.firstVisibleItemIndex < 3) {
            listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .then(if (isOffline) Modifier.alpha(0.7f) else Modifier),
        reverseLayout = true,
        contentPadding = PaddingValues(
            horizontal = KdTheme.spacing.s3,
            vertical = KdTheme.spacing.s3,
        ),
    ) {
        items(
            items = messages,
            key = { it.id },
        ) { message ->
            val index = messages.indexOf(message)
            val older = messages.getOrNull(index + 1)

            val isFirstOfGroup = older == null ||
                older.is_sender != message.is_sender ||
                message.timestamp - older.timestamp > GROUP_GAP_MILLIS

            val showDayDivider = older == null ||
                chatDayKey(older.timestamp) != chatDayKey(message.timestamp)

            MessageRow(
                message = message,
                messageRepository = messageRepository,
                onOpenFileRequest = onOpenFileRequest,
                onOpenUrlRequest = onOpenUrlRequest,
                isFirstOfGroup = isFirstOfGroup,
            )

            if (showDayDivider) {
                DateChip(
                    label = formatChatDay(message.timestamp),
                    modifier = Modifier.padding(vertical = KdTheme.spacing.s3),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: Messages,
    messageRepository: MessageRepository,
    onOpenFileRequest: (filePath: String) -> Unit,
    onOpenUrlRequest: (url: String) -> Unit,
    isFirstOfGroup: Boolean,
) {
    val isSender = message.is_sender != 0L
    val direction = if (isSender) KdBubbleDirection.Out else KdBubbleDirection.In
    val topPadding = if (isFirstOfGroup) KdTheme.spacing.s2 else KdTheme.spacing.s1
    val timestamp = formatChatTime(message.timestamp)

    Column(modifier = Modifier.padding(top = topPadding)) {
        when {
            message.message_type == MessageType.FILE.name && message.file_transfer_id != null -> {
                FileMessageBubble(
                    message = message,
                    messageRepository = messageRepository,
                    direction = direction,
                    timestamp = timestamp,
                    onOpenFileRequest = onOpenFileRequest,
                )
            }
            message.message_type == MessageType.TEXT.name -> {
                TextMessageBubble(
                    message = message,
                    direction = direction,
                    timestamp = timestamp,
                    onOpenUrlRequest = onOpenUrlRequest,
                )
            }
            else -> {
                UnknownMessageBubble(
                    message = message,
                    direction = direction,
                    timestamp = timestamp,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextMessageBubble(
    message: Messages,
    direction: KdBubbleDirection,
    timestamp: String,
    onOpenUrlRequest: (url: String) -> Unit,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val openableUrl = remember(message.content) { openableUrlOrNull(message.content) }

    if (openableUrl != null) {
        Bubble(
            direction = direction,
            timestamp = timestamp,
            content = {
                Text(
                    text = message.content,
                    style = typography.body.copy(
                        color = colors.accent,
                        textDecoration = TextDecoration.Underline,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onOpenUrlRequest(openableUrl) }),
                )
            },
        )
    } else {
        Bubble(
            direction = direction,
            timestamp = timestamp,
            content = {
                SelectionContainer {
                    Text(
                        text = message.content,
                        style = typography.body.copy(color = colors.text),
                    )
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileMessageBubble(
    message: Messages,
    messageRepository: MessageRepository,
    direction: KdBubbleDirection,
    timestamp: String,
    onOpenFileRequest: (filePath: String) -> Unit,
) {
    val fileTransferState by messageRepository.getFileTransferById(
        message.file_transfer_id ?: return
    ).collectAsState(null)

    val isSender = message.is_sender != 0L
    val currentStatus = fileTransferState?.status
    val filePath = fileTransferState?.file_path
    val fileName = fileTransferState?.file_name ?: message.content
    val totalSize = fileTransferState?.total_size ?: 0L
    val transferredSize = fileTransferState?.transferred_size ?: 0L

    val fileState: KdFileState = when (currentStatus) {
        FileTransferStatus.IN_PROGRESS.name -> {
            val progress = if (totalSize > 0) transferredSize.toFloat() / totalSize else 0f
            if (isSender) KdFileState.Sending(progress) else KdFileState.Receiving(progress)
        }
        FileTransferStatus.COMPLETED.name -> KdFileState.Done
        FileTransferStatus.FAILED.name, FileTransferStatus.REJECTED.name -> KdFileState.Failed
        else -> if (isSender) KdFileState.Sending(0f) else KdFileState.Receiving(0f)
    }

    val openablePath = filePath?.takeIf {
        !isSender && currentStatus == FileTransferStatus.COMPLETED.name
    }

    Bubble(
        direction = direction,
        timestamp = timestamp,
        content = {
            FileCard(
                fileName = fileName,
                fileSize = if (totalSize > 0) formatBytes(totalSize) else null,
                state = fileState,
                onRetry = {},
                modifier = if (openablePath != null) {
                    Modifier.combinedClickable(onClick = { onOpenFileRequest(openablePath) })
                } else {
                    Modifier
                },
            )
        },
    )
}

@Composable
private fun UnknownMessageBubble(
    message: Messages,
    direction: KdBubbleDirection,
    timestamp: String,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography

    Bubble(
        direction = direction,
        timestamp = timestamp,
        content = {
            Text(
                text = "Unsupported message (${message.message_type})",
                style = typography.caption.copy(color = colors.err),
            )
        },
    )
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
