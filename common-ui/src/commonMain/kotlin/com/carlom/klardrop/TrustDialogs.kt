package com.carlom.klardrop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import com.carlom.klardrop.components.KdDeviceKind
import com.carlom.klardrop.components.PairingDialog
import com.carlom.klardrop.theme.KdTheme
import com.carlom.klardrop.theme.kdElevation

private fun String.toKdDeviceKind(): KdDeviceKind = when (this.uppercase()) {
  "MOBILE" -> KdDeviceKind.Android
  "DESKTOP" -> KdDeviceKind.Pc
  else -> KdDeviceKind.Unknown
}

@Composable
internal fun PairingApprovalDialog(
  state: PairingDialogState,
  onDismiss: () -> Unit
) {
  if (state.isError) {
    Dialog(onDismissRequest = onDismiss) {
      val colors = KdTheme.colors
      val typography = KdTheme.typography
      val radii = KdTheme.radii
      val spacing = KdTheme.spacing

      Surface(
        modifier = Modifier.kdElevation(level = 3, shape = radii.shapeXl),
        shape = radii.shapeXl,
        color = colors.bg2,
      ) {
        Column(
          modifier = Modifier
            .padding(spacing.s6)
            .fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text(
            text = "Couldn't link device",
            style = typography.headline.copy(color = colors.text),
            textAlign = TextAlign.Center,
          )

          Spacer(Modifier.height(spacing.s3))

          Text(
            text = state.errorMessage ?: "Something went wrong while linking. Please try again.",
            style = typography.body.copy(color = colors.text2),
            textAlign = TextAlign.Center,
          )

          Spacer(Modifier.height(spacing.s6))

          Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            shape = radii.shapeMd,
            colors = ButtonDefaults.buttonColors(
              containerColor = colors.accent,
              contentColor = colors.textInv,
            ),
          ) {
            Text("Close", style = typography.body)
          }
        }
      }
    }
  } else {
    Dialog(onDismissRequest = onDismiss) {
      PairingDialog(
        remoteDeviceName = state.deviceName,
        remoteKind = state.deviceType.toKdDeviceKind(),
        body = "Accept this device into Your devices? You'll be able to send files and messages without prompting.",
        onCancel = onDismiss,
        onConfirm = state.onAccept,
      )
    }
  }
}

@Composable
internal fun LinkDeviceConfirmDialog(
  device: DeviceUi,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit
) {
  Dialog(onDismissRequest = onDismiss) {
    PairingDialog(
      remoteDeviceName = device.deviceName,
      remoteKind = device.deviceType.toKdDeviceKind(),
      body = "Only do this if it's your own device. Your devices share clipboard, files, and message history with each other automatically.",
      confirmLabel = "Yes, it's mine",
      onCancel = onDismiss,
      onConfirm = onConfirm,
    )
  }
}
