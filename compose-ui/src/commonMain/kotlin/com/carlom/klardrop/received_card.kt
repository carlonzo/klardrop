package com.carlom.klardrop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.carlom.klardrop.common.communication.message.ConnectionInfoMessage
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.components.IncomingTransferCard
import com.carlom.klardrop.theme.KdTheme
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveNotification(
    modifier: Modifier = Modifier,
    receiveUpdate: ReceiveMessageUpdate,
    onClicked: () -> Unit,
    onDismissed: () -> Unit,
    onConnectionInfoAccepted: (ConnectionInfoMessage) -> Unit = {},
) {
    val motion = KdTheme.motion
    val isPending = receiveUpdate.status is ReceiveMessageStatus.PendingAuthorization

    var visible by remember { mutableStateOf(true) }

    val dismissState = rememberSwipeToDismissBoxState(
        initialValue = SwipeToDismissBoxValue.Settled,
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                visible = false
            }
            true
        }
    )

    LaunchedEffect(receiveUpdate.status) {
        val status = receiveUpdate.status
        if (status is ReceiveMessageStatus.Completed || status is ReceiveMessageStatus.Failed) {
            delay(motion.dSlow.toLong())
            visible = false
            delay(motion.dBase.toLong())
            onDismissed()
        }
    }

    AnimatedVisibility(
        visible = visible,
        exit = fadeOut(tween(motion.dBase)) + shrinkVertically(tween(motion.dBase)),
        modifier = modifier,
    ) {
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {},
        ) {
            if (isPending) {
                val status = receiveUpdate.status as ReceiveMessageStatus.PendingAuthorization
                val firstFile = receiveUpdate.messages.filterIsInstance<FileMessage>().firstOrNull()
                val isText = firstFile == null &&
                    receiveUpdate.messages.any { it is TextMessage }
                IncomingTransferCard(
                    senderName = receiveUpdate.device?.name ?: "Unknown device",
                    fileName = firstFile?.fileName,
                    fileSize = null,
                    subtitle = if (isText) "wants to send you a message" else "wants to send you a file",
                    onAccept = {
                        status.acceptTransfer(true)
                        onClicked()
                    },
                    onDecline = {
                        status.acceptTransfer(false)
                        onDismissed()
                    },
                )
            } else {
                val firstFile = receiveUpdate.messages.filterIsInstance<FileMessage>().firstOrNull()
                val firstConnectionInfo = receiveUpdate.messages.filterIsInstance<ConnectionInfoMessage>().firstOrNull()
                IncomingTransferCard(
                    senderName = receiveUpdate.device?.name ?: "Unknown device",
                    fileName = firstFile?.fileName ?: firstConnectionInfo?.let { "Wi-Fi: ${it.ssid}" },
                    fileSize = null,
                    onAccept = {
                        firstConnectionInfo?.let { onConnectionInfoAccepted(it) }
                        onDismissed()
                    },
                    onDecline = { onDismissed() },
                )
            }
        }
    }
}
