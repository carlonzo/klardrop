package com.carlom.klardrop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
internal fun PairingApprovalDialog(
  state: PairingDialogState,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = if (state.isError) "Couldn't link device" else "Is this your device?"
      )
    },
    text = {
      if (state.isError) {
        Text(state.errorMessage ?: "Something went wrong while linking. Please try again.")
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(
            text = "${state.deviceName} wants to link with this device.",
            style = MaterialTheme.typography.bodyLarge
          )
          Text(
            text = "Only accept if it's your own device. Linked devices stay in sync — clipboard, files, and message history are shared between them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    },
    confirmButton = {
      if (state.isError) {
        Button(onClick = onDismiss) { Text("Close") }
      } else {
        Button(onClick = { state.onAccept() }) { Text("Yes, it's mine") }
      }
    },
    dismissButton = {
      if (!state.isError) {
        TextButton(onClick = { state.onReject() }) { Text("Not mine") }
      }
    }
  )
}

@Composable
internal fun LinkDeviceConfirmDialog(
  device: DeviceUi,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add ${device.deviceName} to your devices?") },
    text = {
      Text(
        text = "Only do this if it's your own device. Your devices share clipboard, files, and message history with each other automatically.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    },
    confirmButton = {
      Button(onClick = onConfirm) { Text("Yes, it's mine") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    }
  )
}
