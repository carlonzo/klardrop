package com.carlom.klardrop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.communication.message.ConnectionInfoMessage
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveNotification(
  modifier: Modifier = Modifier,
  receiveUpdate: ReceiveMessageUpdate,
  onClicked: () -> Unit,
  onDismissed: () -> Unit,
  onConnectionInfoAccepted: (ConnectionInfoMessage) -> Unit = {},
) {
  val connectionInfo = receiveUpdate.messages.firstOrNull() as? ConnectionInfoMessage

  @Suppress("DEPRECATION")
  val dismissState = rememberSwipeToDismissBoxState(
    initialValue = SwipeToDismissBoxValue.Settled,
    confirmValueChange = {
      if (it != SwipeToDismissBoxValue.Settled) {
        onDismissed()
      }
      true
    }
  )

  val cardAlpha = if (dismissState.targetValue != SwipeToDismissBoxValue.Settled) {
    (dismissState.progress - 1).absoluteValue
  } else {
    1f
  }

  SwipeToDismissBox(
    modifier = modifier,
    state = dismissState,
    backgroundContent = {},
    content = {
      Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier = Modifier
          .heightIn(min = 72.dp)
          .padding(horizontal = 12.dp, vertical = 8.dp)
          .alpha(cardAlpha)
          .clickable { onClicked() }
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          val (icon, tint) = iconAndTint(receiveUpdate)
          IconBubble(icon = icon, tint = tint)

          Column(
            modifier = Modifier
              .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            ReceiveBody(receiveUpdate)

            if (connectionInfo != null && receiveUpdate.status is ReceiveMessageStatus.Completed) {
              Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onConnectionInfoAccepted(connectionInfo) }) {
                  Text("Join Wi-Fi")
                }
              }
            }
          }

          IconButton(
            onClick = onDismissed,
            modifier = Modifier.size(28.dp)
          ) {
            Icon(
              imageVector = Icons.Filled.Close,
              contentDescription = "Dismiss",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp)
            )
          }
        }
      }
    }
  )
}

@Composable
private fun ReceiveBody(update: ReceiveMessageUpdate) {
  when (val status = update.status) {
    is ReceiveMessageStatus.Progress -> {
      Text(
        text = headerLine("Receiving", update),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Medium
      )
      val totalProgress = if (update.messages.isNotEmpty()) {
        status.messages.sumOf { it.second } / update.messages.size
      } else 0
      LinearProgressIndicator(
        progress = { totalProgress / 100f },
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surface
      )
    }

    ReceiveMessageStatus.Completed -> {
      Text(
        text = headerLine("Received", update),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Medium
      )
      update.messages.take(3).forEach { msg ->
        val preview = when (msg) {
          is TextMessage -> msg.text
          is FileMessage -> msg.fileName
          is ConnectionInfoMessage -> "Wi-Fi: ${msg.ssid}"
          else -> "Unknown"
        }
        Text(
          text = preview,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
      if (update.messages.size > 3) {
        Text(
          text = "+${update.messages.size - 3} more",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    is ReceiveMessageStatus.Failed -> {
      Text(
        text = "Couldn't receive",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.error,
        fontWeight = FontWeight.Medium
      )
      Text(
        text = status.reason,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
    }

    is ReceiveMessageStatus.PendingAuthorization -> {
      Text(
        text = "Incoming transfer",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Medium
      )
      Text(
        text = "Waiting for your approval",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }

    ReceiveMessageStatus.Started -> {
      Text(
        text = headerLine("Receiving", update),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Medium
      )
    }
  }
}

@Composable
private fun IconBubble(icon: ImageVector, tint: Color) {
  Box(
    modifier = Modifier
      .size(36.dp),
    contentAlignment = Alignment.Center
  ) {
    Surface(
      shape = CircleShape,
      color = tint.copy(alpha = 0.15f),
      modifier = Modifier.size(36.dp)
    ) {}
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = tint,
      modifier = Modifier.size(20.dp)
    )
  }
}

private fun iconAndTint(update: ReceiveMessageUpdate): Pair<ImageVector, Color> {
  val first = update.messages.firstOrNull()
  val isFile = first is FileMessage
  val isWifi = first is ConnectionInfoMessage
  val isComplete = update.status is ReceiveMessageStatus.Completed
  val isError = update.status is ReceiveMessageStatus.Failed
  val isPending = update.status is ReceiveMessageStatus.PendingAuthorization
  return when {
    isError -> Icons.Filled.ErrorOutline to Color(0xFFCF6679)
    isPending -> Icons.Filled.LockOpen to Color(0xFFE2A03F)
    isWifi -> Icons.Filled.Wifi to Color(0xFF4CAF50)
    isComplete -> Icons.Filled.CheckCircle to Color(0xFF4CAF50)
    isFile -> Icons.AutoMirrored.Filled.InsertDriveFile to Color(0xFF7B8CFF)
    else -> Icons.AutoMirrored.Filled.TextSnippet to Color(0xFF7B8CFF)
  }
}

private fun messageType(update: ReceiveMessageUpdate): String {
  val first = update.messages.firstOrNull()
  return when {
    first is ConnectionInfoMessage -> "Wi-Fi credentials"
    first is TextMessage && update.messages.size == 1 -> "text"
    first is TextMessage -> "texts"
    update.messages.size == 1 -> "file"
    else -> "files"
  }
}

private fun headerLine(verb: String, update: ReceiveMessageUpdate): String {
  val base = "$verb ${update.messages.size} ${messageType(update)}"
  val sender = update.device?.name
  return if (sender != null) "$base from $sender" else base
}

interface ReceiveNotificationsCallbacks {
  fun onReceivedCardClicked(receiveUpdate: ReceiveMessageUpdate)
  fun onCardDismissed(id: Int)
  fun onConnectionInfoAccepted(message: ConnectionInfoMessage) {}
}
