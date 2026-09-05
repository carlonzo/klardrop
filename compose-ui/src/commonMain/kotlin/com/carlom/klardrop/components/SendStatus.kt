package com.carlom.klardrop.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.theme.KdTheme

/**
 * Shared transfer status component showing progress, completion, or error.
 */
@Composable
fun SendStatus(progress: MessengerSendProgress?, onHide: () -> Unit) {
  val spacing = KdTheme.spacing
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(spacing.s5),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    when (progress) {
      is MessengerSendProgress.InProgress -> {
        LinearProgressIndicator(
          progress = { progress.percentage / 100f },
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(spacing.s3))
        Text("Sending… ${progress.percentage}%", style = KdTheme.typography.body)
      }

      is MessengerSendProgress.Completed -> Text("Sent ✓", style = KdTheme.typography.body)

      is MessengerSendProgress.Error ->
        Text("Couldn't send: ${progress.message}", style = KdTheme.typography.body)

      else -> { // null / Pending / AwaitingRecipient
        CircularProgressIndicator()
        Spacer(Modifier.height(spacing.s3))
        Text("Waiting for receiver to accept…", style = KdTheme.typography.body)
      }
    }

    Spacer(Modifier.height(spacing.s4))
    val terminal = progress is MessengerSendProgress.Completed || progress is MessengerSendProgress.Error
    TextButton(onClick = onHide) {
      Text(if (terminal) "Close" else "Hide — keeps sending in background")
    }
    Spacer(Modifier.height(spacing.s5))
  }
}
