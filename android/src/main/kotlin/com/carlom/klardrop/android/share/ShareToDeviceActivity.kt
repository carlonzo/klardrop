package com.carlom.klardrop.android.share

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.carlom.klardrop.DeviceUi
import com.carlom.klardrop.TrustStatus
import com.carlom.klardrop.android.appKlardrop
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.delay
import com.carlom.klardrop.components.KdDeviceKind
import com.carlom.klardrop.components.KdShareDevice
import com.carlom.klardrop.components.KdStatus
import com.carlom.klardrop.components.SendStatus
import com.carlom.klardrop.components.ShareSheet
import com.carlom.klardrop.components.toKdShareDevice
import com.carlom.klardrop.theme.AppTheme
import com.carlom.klardrop.theme.KdTheme
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareToDeviceActivity : ComponentActivity() {

  private val klardrop: Klardrop
    get() = appKlardrop()

  private lateinit var shareToDeviceController: ShareToDeviceController

  // What this share invocation carries; populated from the launch intent. Exactly one is non-empty.
  private var pendingText: String? = null
  private var pendingUris: List<Uri> = emptyList()

  private val requestNotificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort; FGS runs regardless */ }

  @OptIn(ExperimentalMaterial3Api::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    shareToDeviceController = ShareToDeviceController(klardrop.commonComponent)

    parseIntent()
    // Large transfers post a progress notification from FileTransferService; ask up-front so it can show.
    maybeRequestNotificationPermission()

    setContent {
      AppTheme {
        val devices by shareToDeviceController.devicesFlow.collectAsState(emptyList<DeviceUi>())
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        var selectedId by remember { mutableStateOf<String?>(null) }
        var dismissed by remember { mutableStateOf(false) }
        // Non-null once a file transfer is handed to FileTransferService; the sheet then shows live
        // status off ActiveSends instead of the device list. The transfer lives in the service, so
        // dismissing the sheet just "minimizes" — the bytes keep flowing in the background.
        var transferId by remember { mutableStateOf<String?>(null) }
        val sendProgress = transferId?.let { id -> ActiveSends.flow(id)?.collectAsState()?.value }

        LaunchedEffect(dismissed) {
          if (dismissed) {
            sheetState.hide()
            finish()
          }
        }

        LaunchedEffect(sendProgress) {
          if (sendProgress is MessengerSendProgress.Completed) {
            delay(900)
            dismissed = true
          }
        }

        val deviceList = devices.toList()
        val byId = deviceList.associateBy { it.deviceId }
        val (trustedShare, nearbyShare) = deviceList.partition {
          it.trustStatus == TrustStatus.Trusted
        }.let { (trusted, nearby) ->
          trusted.map { it.toKdShareDevice() } to nearby.map { it.toKdShareDevice() }
        }

        ModalBottomSheet(
          onDismissRequest = { dismissed = true },
          sheetState = sheetState,
          shape = KdTheme.radii.shapeSheet,
          containerColor = KdTheme.colors.bg1,
        ) {
          if (transferId == null) {
            ShareSheet(
              trustedDevices = trustedShare,
              nearbyDevices = nearbyShare,
              selectedId = selectedId,
              onSelectDevice = { selectedId = it.id },
              onSend = { share ->
                val target = share?.id?.let(byId::get) ?: return@ShareSheet
                // Hand the payload to FileTransferService (file) while we still hold the read grant, or
                // fire-and-forget the text. Files keep the sheet open to show status; text dismisses.
                lifecycleScope.launch {
                  try {
                    val id = dispatch(target.deviceId)
                    if (id == null) dismissed = true else transferId = id
                  } catch (e: Throwable) {
                    log("ShareToDeviceActivity", "Failed to dispatch share to ${target.deviceId}", e)
                    dismissed = true
                  }
                }
              },
            )
          } else {
            SendStatus(progress = sendProgress, onHide = { dismissed = true })
          }
        }
      }
    }
  }

  private fun parseIntent() {
    when (intent?.action) {
      Intent.ACTION_SEND -> {
        if ("text/plain" == intent.type) {
          log("ShareToDeviceActivity", "Handling text $intent")
          pendingText = intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
          log("ShareToDeviceActivity", "Handling file $intent")
          extractUri(intent)?.let { pendingUris = listOf(it) }
        }
      }

      Intent.ACTION_SEND_MULTIPLE -> {
        log("ShareToDeviceActivity", "Handling multiple files $intent")
        pendingUris = extractUris(intent)
      }

      else -> {
        log("ShareToDeviceActivity", "Unhandled intent: $intent")
      }
    }
  }

  /**
   * Hand the shared payload off while we still hold the read grant. Text is fire-and-forget
   * (returns null). Files of any size stream through [FileTransferService] under the forwarded grant —
   * even a tiny file gates on the receiver accepting, so it needs the same foreground anchor as a
   * big one. Returns the [ActiveSends] transfer id to observe, or null for text/empty.
   */
  private suspend fun dispatch(deviceId: String): String? {
    pendingText?.let {
      shareToDeviceController.sendText(deviceId, it)
      return null
    }
    if (pendingUris.isEmpty()) return null

    val fileSystem = klardrop.commonComponent.platformFileSystem()
    val ioDispatcher = klardrop.commonComponent.coroutines().ioDispatcher

    // Resolve name/size/mime now, while the grant is valid.
    val files = withContext(ioDispatcher) {
      pendingUris.map { uri ->
        val data = fileSystem.getResolvedFileData(PlatformFile(uri))
        FileTransferService.SendFile(uri, data.fileName, data.fileSize, data.mimeType)
      }
    }

    val transferId = ActiveSends.create()
    // Forwards the grant into the service; safe to finish the Activity at any point after.
    FileTransferService.start(this, deviceId, files, transferId)
    return transferId
  }

  private fun maybeRequestNotificationPermission() {
    // Only file shares can spin up FileTransferService (and its progress notification). A text share
    // never does, so don't pester the user with a permission prompt for one.
    if (pendingUris.isEmpty()) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
    if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
  }

  private fun extractUri(intent: Intent): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
    }

  private fun extractUris(intent: Intent): List<Uri> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.filterIsInstance<Uri>()
    }.orEmpty()

  override fun onDestroy() {
    shareToDeviceController.dispose()
    super.onDestroy()
  }
}
