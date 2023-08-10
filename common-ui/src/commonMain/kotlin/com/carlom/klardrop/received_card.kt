package com.carlom.klardrop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.Card
import androidx.compose.material3.DismissValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismiss
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDismissState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
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
  onDismissed: (ReceiveMessageUpdate) -> Unit = {},
  ) {

  val dismissState = rememberDismissState(initialValue = DismissValue.Default, confirmValueChange = {
    if (it != DismissValue.Default) {
      onDismissed(receiveUpdate)
    }
    true
  })

  val cardAlpha = if (dismissState.targetValue != DismissValue.Default) {
    (dismissState.progress - 1).absoluteValue
  } else {
    1f
  }

  SwipeToDismiss(
    modifier = modifier,
    state = dismissState,
    background = {},
    dismissContent = {
      Card(
        modifier = Modifier
          .heightIn(min = 80.dp)
          .padding(horizontal = 8.dp, vertical = 16.dp)
          .alpha(cardAlpha),
      ) {

        Box(modifier = Modifier
          .padding(8.dp)
        ) {
          when (val status = receiveUpdate.status) {
            is ReceiveMessageStatus.Progress -> ReceiveNotificationProgress(receiveUpdate, status)

            ReceiveMessageStatus.Completed -> ReceiveNotificationCompleted(receiveUpdate)

            is ReceiveMessageStatus.Failed -> {
              Text("Error receiving ${receiveUpdate.messages.size} messages because ${status.reason}")
            }

            is ReceiveMessageStatus.PendingAuthorization -> Text("Pending authorization")
            ReceiveMessageStatus.Started -> Text("Receiving ${receiveUpdate.messages.size} messages")
          }
        }

      }
    },
  )


}

private fun messageType(update: ReceiveMessageUpdate): String {
  return if (update.messages.first() is TextMessage) {
    if (update.messages.size == 1) "text"
    else "texts"
  } else {
    if (update.messages.size == 1) "file"
    else "files"
  }
}

@Composable
private fun ReceiveNotificationProgress(update: ReceiveMessageUpdate, status: ReceiveMessageStatus.Progress) {
  Column {
    val hasSender = update.device?.name != null

    var header = "Receiving ${update.messages.size} ${messageType(update)}"
    if (hasSender) {
      header += " from ${update.device?.name}"
    }
    Text(header)

    Spacer(modifier = Modifier.padding(8.dp))

    val totalProgress = status.messages.sumOf { it.second } / update.messages.size
    Text("Progress: $totalProgress %")
  }
}

@Composable
private fun ReceiveNotificationCompleted(update: ReceiveMessageUpdate) {
  Column {
    var header = "Received ${update.messages.size} ${messageType(update)}"

    val hasSender = update.device?.name != null
    if (hasSender) {
      header += " from ${update.device?.name}"
    }

    Text(header)

    Spacer(modifier = Modifier.padding(8.dp))

    update.messages.forEach {
      when (it) {
        is TextMessage -> Text(it.text)
        is FileMessage -> Text(it.fileName)
        else -> Text("Unknown message type ${it.type}")
      }
    }

  }
}