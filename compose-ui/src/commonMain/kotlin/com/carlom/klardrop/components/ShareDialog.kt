package com.carlom.klardrop.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.TrustStatus
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.share.ShareSheetDismissPolicy
import com.carlom.klardrop.common.share.ShareSheetDismissTrigger
import com.carlom.klardrop.platformFileFromPath
import com.carlom.klardrop.theme.KdTheme
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareDialog(
  files: List<String>,
  discoveryController: DiscoveryController,
  isLargeScreen: Boolean = true,
  onDismiss: () -> Unit,
) {
  val state by discoveryController.screenStateFlow.collectAsState()
  val devices = state.devices
  val (trusted, nearby) = remember(devices) {
    devices.partition { it.trustStatus == TrustStatus.Trusted }
  }
  val trustedShare = remember(trusted) { trusted.map { it.toKdShareDevice() } }
  val nearbyShare = remember(nearby) { nearby.map { it.toKdShareDevice() } }

  var selectedId by remember(trustedShare, nearbyShare) {
    mutableStateOf(
      trustedShare.firstOrNull()?.id ?: nearbyShare.firstOrNull()?.id
    )
  }
  var transferProgress by remember { mutableStateOf<MessengerSendProgress?>(null) }

  LaunchedEffect(transferProgress) {
    if (
      ShareSheetDismissPolicy.shouldDismiss(
        ShareSheetDismissTrigger.Completed,
        progress = transferProgress,
        handoffComplete = transferProgress != null,
      )
    ) {
      delay(1200)
      onDismiss()
    }
  }

  val onHide = {
    if (
      ShareSheetDismissPolicy.shouldDismiss(
        ShareSheetDismissTrigger.UserHide,
        progress = transferProgress,
        handoffComplete = transferProgress != null,
      )
    ) {
      onDismiss()
    }
  }

  val content = @Composable {
    val fileTitle = if (files.size == 1) {
      files.first().substringAfterLast('/')
    } else {
      "${files.size} files"
    }

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = KdTheme.spacing.s3),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (transferProgress == null) {
        Text(
          text = "Send $fileTitle",
          style = KdTheme.typography.headline,
          color = KdTheme.colors.text,
          modifier = Modifier.padding(horizontal = KdTheme.spacing.s4),
        )
        Spacer(Modifier.height(KdTheme.spacing.s2))

        ShareSheet(
          trustedDevices = trustedShare,
          nearbyDevices = nearbyShare,
          selectedId = selectedId,
          onSelectDevice = { selectedId = it.id },
          onSend = { share ->
            val target = share?.id?.let { id -> devices.firstOrNull { it.deviceId == id } } ?: return@ShareSheet
            if (files.isEmpty()) {
              if (ShareSheetDismissPolicy.shouldDismiss(ShareSheetDismissTrigger.EmptyPayload)) {
                onDismiss()
              }
              return@ShareSheet
            }
            val platformFiles = files.map { platformFileFromPath(it) }
            // Connecting first so a dismiss cannot land before sendFiles is launched.
            transferProgress = MessengerSendProgress.Pending
            discoveryController.sendFiles(
              deviceId = target.deviceId,
              files = platformFiles,
              onProgress = { progress ->
                transferProgress = progress
              },
            )
          },
        )
      } else {
        SendStatus(
          progress = transferProgress,
          onHide = onHide,
        )
      }
    }
  }

  val colors = KdTheme.colors
  val radii = KdTheme.radii

  // Same sheet-lifetime rule as share-from-outside: do not close on send or while
  // Connecting/Sending. Hide after sendFiles has been launched is allowed.
  val allowSwipeDismiss = ShareSheetDismissPolicy.shouldDismiss(
    ShareSheetDismissTrigger.SwipeAway,
    progress = transferProgress,
    handoffComplete = transferProgress != null,
  )
  val onRequestDismiss = {
    if (allowSwipeDismiss) onDismiss()
  }

  if (isLargeScreen) {
    Dialog(onDismissRequest = onRequestDismiss) {
      Surface(
        modifier = Modifier.widthIn(min = 380.dp, max = 460.dp),
        shape = radii.shapeXl,
        color = colors.bg1,
      ) {
        content()
      }
    }
  } else {
    ModalBottomSheet(
      onDismissRequest = onRequestDismiss,
      sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
      shape = radii.shapeSheet,
      containerColor = colors.bg1,
    ) {
      content()
    }
  }
}
