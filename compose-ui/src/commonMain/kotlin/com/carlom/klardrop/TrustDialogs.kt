package com.carlom.klardrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
  isLargeScreen: Boolean,
  onDismiss: () -> Unit
) {
  if (state.isError) {
    PairingShell(isLargeScreen = isLargeScreen, onDismiss = onDismiss) {
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
    PairingShell(isLargeScreen = isLargeScreen, onDismiss = onDismiss) {
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
  isLargeScreen: Boolean,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit
) {
  PairingShell(isLargeScreen = isLargeScreen, onDismiss = onDismiss) {
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

/**
 * Confirms a destructive Forget Device action initiated from the overflow menu on a
 * trusted-device row. Reuses [PairingDialog] for visual consistency with the link-confirm
 * counterpart on the other side of the device's lifecycle.
 */
@Composable
internal fun ForgetDeviceConfirmDialog(
  device: DeviceUi,
  isLargeScreen: Boolean,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  PairingShell(isLargeScreen = isLargeScreen, onDismiss = onDismiss) {
    PairingDialog(
      remoteDeviceName = device.deviceName,
      remoteKind = device.deviceType.toKdDeviceKind(),
      body = "This device will no longer share files, messages, or clipboard with " +
        "${device.deviceName}. You'll have to pair again to reconnect.",
      confirmLabel = "Forget",
      onCancel = onDismiss,
      onConfirm = onConfirm,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingShell(
  isLargeScreen: Boolean,
  onDismiss: () -> Unit,
  content: @Composable () -> Unit,
) {
  if (isLargeScreen) {
    Dialog(onDismissRequest = onDismiss) {
      content()
    }
  } else {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = KdTheme.spacing
    ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = sheetState,
      shape = KdTheme.radii.shapeSheet,
      containerColor = KdTheme.colors.bg1,
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = spacing.s4, end = spacing.s4, bottom = spacing.s6),
      ) {
        content()
      }
    }
  }
}
