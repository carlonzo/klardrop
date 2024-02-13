package com.carlom.klardrop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveTransferUpdate
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveNotification(
  modifier: Modifier = Modifier,
  receiveUpdate: ReceiveTransferUpdate,
  callbacks: ReceiveNotificationsCallbacks
) {

  val dismissState = rememberSwipeToDismissBoxState(initialValue = SwipeToDismissBoxValue.Settled, confirmValueChange = {
    if (it != SwipeToDismissBoxValue.Settled) {
      callbacks.onCardDismissed(receiveUpdate)
    }
    true
  })

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
      Card(
        modifier = Modifier
          .heightIn(min = 80.dp)
          .padding(horizontal = 8.dp, vertical = 16.dp)
          .alpha(cardAlpha),
      ) {

        Box(
          modifier = Modifier
            .padding(8.dp)
            .clickable { callbacks.onReceivedCardClicked(receiveUpdate) }
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

private fun messageType(update: ReceiveTransferUpdate): String {
  return if (update.messages.first() is TextMessage) {
    if (update.messages.size == 1) "text"
    else "texts"
  } else {
    if (update.messages.size == 1) "file"
    else "files"
  }
}

@Composable
private fun ReceiveNotificationProgress(update: ReceiveTransferUpdate, status: ReceiveMessageStatus.Progress) {
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
private fun ReceiveNotificationCompleted(update: ReceiveTransferUpdate) {
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

interface ReceiveNotificationsCallbacks {
  fun onReceivedCardClicked(receiveUpdate: ReceiveTransferUpdate)
  fun onCardDismissed(receiveUpdate: ReceiveTransferUpdate)
}

