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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.carlom.klardrop.DeviceUi
import com.carlom.klardrop.TrustStatus
import com.carlom.klardrop.android.appKlardrop
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.qrshare.QrSharePayload
import com.carlom.klardrop.common.qrshare.QrShareSession
import com.carlom.klardrop.common.qrshare.QrShareState
import com.carlom.klardrop.common.qrshare.SharedFile
import com.carlom.klardrop.common.share.ShareSheetDismissPolicy
import com.carlom.klardrop.common.share.ShareSheetDismissTrigger
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.components.QrShareSheet
import com.carlom.klardrop.components.SendStatus
import com.carlom.klardrop.components.ShareSheet
import com.carlom.klardrop.components.toKdShareDevice
import com.carlom.klardrop.theme.AppTheme
import com.carlom.klardrop.theme.KdTheme
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareToDeviceActivity : ComponentActivity() {

  private val klardrop: Klardrop
    get() = appKlardrop()

  private val qrShareSession: QrShareSession
    get() = klardrop.commonComponent.qrShareSession()

  private lateinit var shareToDeviceController: ShareToDeviceController

  private val showQrSheetState = mutableStateOf(false)

  // What this share invocation carries; populated from the launch intent. Exactly one is non-empty.
  private var pendingText: String? = null
  private var pendingUris: List<Uri> = emptyList()

  private val requestNotificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort; FGS runs regardless */ }

  @OptIn(ExperimentalMaterial3Api::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    shareToDeviceController = ShareToDeviceController(klardrop.commonComponent)

    val currentState = qrShareSession.state.value
    val isReattach = currentState is QrShareState.QrVisible ||
      (currentState is QrShareState.Serving && currentState.qrStillVisible)
    if (isReattach) {
      showQrSheetState.value = true
      parseIntent()
    } else {
      qrShareSession.cancel()
      showQrSheetState.value = false
      parseIntent()
      maybeRequestNotificationPermission()
    }

    setContent {
      AppTheme {
        val qrState by qrShareSession.state.collectAsState()
        val devices by shareToDeviceController.devicesFlow.collectAsState(emptyList<DeviceUi>())
        var selectedId by remember { mutableStateOf<String?>(null) }
        var dismissed by remember { mutableStateOf(false) }
        // Non-null from the Send tap — Connecting is shown before dispatch so a content-height
        // change cannot race onDismissRequest. Progress is mirrored through ActiveSends.
        var transferId by remember { mutableStateOf<String?>(null) }
        // True after FileTransferService.start / sendText has been handed off. Hide may then
        // finish(); swipe-down mid-send still must not.
        var handoffComplete by remember { mutableStateOf(false) }
        val sendProgress = transferId?.let { id -> ActiveSends.flow(id)?.collectAsState()?.value }

        val dismissGate = remember { ShareSheetDismissGate() }
        dismissGate.progress = sendProgress
        dismissGate.handoffComplete = handoffComplete
        dismissGate.dismissed = dismissed
        val confirmValueChange = remember<(SheetValue) -> Boolean> {
          { newValue ->
            if (newValue != SheetValue.Hidden) {
              true
            } else {
              dismissGate.dismissed ||
                ShareSheetDismissPolicy.shouldDismiss(
                  ShareSheetDismissTrigger.SwipeAway,
                  progress = dismissGate.progress,
                  handoffComplete = dismissGate.handoffComplete,
                )
            }
          }
        }
        val sheetState = rememberModalBottomSheetState(
          skipPartiallyExpanded = true,
          confirmValueChange = confirmValueChange,
        )

        LaunchedEffect(dismissed) {
          if (dismissed) {
            sheetState.hide()
            finish()
          }
        }

        LaunchedEffect(sendProgress) {
          if (
            ShareSheetDismissPolicy.shouldDismiss(
              ShareSheetDismissTrigger.Completed,
              progress = sendProgress,
              handoffComplete = handoffComplete,
            )
          ) {
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
          onDismissRequest = {
            if (showQrSheetState.value) {
              dismissQrSheet()
            } else if (
              ShareSheetDismissPolicy.shouldDismiss(
                ShareSheetDismissTrigger.SwipeAway,
                progress = sendProgress,
                handoffComplete = handoffComplete,
              )
            ) {
              dismissed = true
            }
          },
          sheetState = sheetState,
          shape = KdTheme.radii.shapeSheet,
          containerColor = KdTheme.colors.bg1,
        ) {
          when {
            showQrSheetState.value -> {
              QrShareSheet(
                state = qrState,
                onDismiss = { dismissQrSheet() },
                onCancel = {
                  qrShareSession.cancel()
                  finish()
                },
                isText = pendingText != null,
              )
            }
            transferId == null -> {
              ShareSheet(
                trustedDevices = trustedShare,
                nearbyDevices = nearbyShare,
                selectedId = selectedId,
                onSelectDevice = { selectedId = it.id },
                onShareViaQr = {
                  maybeRequestNotificationPermission(isQr = true)
                  showQrSheetState.value = true
                  lifecycleScope.launch {
                    try {
                      val (files, payload) = buildQrPayload()
                      FileTransferService.startQrSession(this@ShareToDeviceActivity, files)
                      qrShareSession.start(payload)
                      FileTransferService.onQrSessionStartCompleted()
                    } catch (e: Throwable) {
                      log("ShareToDeviceActivity", "Failed to start QR share", e)
                      FileTransferService.clearQrSessionHeld()
                    }
                  }
                },
                onSend = { share ->
                  val target = share?.id?.let(byId::get) ?: return@ShareSheet
                  if (pendingText == null && pendingUris.isEmpty()) {
                    if (ShareSheetDismissPolicy.shouldDismiss(ShareSheetDismissTrigger.EmptyPayload)) {
                      dismissed = true
                    }
                    return@ShareSheet
                  }
                  // Flip to Connecting synchronously, then dispatch while this Activity is still
                  // visible so FileTransferService.start can take the URI grant.
                  val id = ActiveSends.create()
                  transferId = id
                  lifecycleScope.launch {
                    try {
                      val handedOff = dispatch(target.deviceId, id)
                      if (!handedOff) {
                        if (ShareSheetDismissPolicy.shouldDismiss(ShareSheetDismissTrigger.EmptyPayload)) {
                          dismissed = true
                        }
                      } else {
                        handoffComplete = true
                      }
                    } catch (e: Throwable) {
                      log("ShareToDeviceActivity", "Failed to dispatch share to ${target.deviceId}", e)
                      ActiveSends.publish(id, MessengerSendProgress.Error(e.message ?: "Transfer failed"))
                    }
                  }
                },
              )
            }
            else -> {
              SendStatus(
                progress = sendProgress,
                onHide = {
                  if (
                    ShareSheetDismissPolicy.shouldDismiss(
                      ShareSheetDismissTrigger.UserHide,
                      progress = sendProgress,
                      handoffComplete = handoffComplete,
                    )
                  ) {
                    dismissed = true
                  }
                },
              )
            }
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    val currentState = qrShareSession.state.value
    val isReattach = currentState is QrShareState.QrVisible ||
      (currentState is QrShareState.Serving && currentState.qrStillVisible)
    if (isReattach) {
      // restore QR UI, ignore new SHARE payload
      showQrSheetState.value = true
    } else {
      setIntent(intent)
      qrShareSession.cancel()
      showQrSheetState.value = false
      parseIntent()
      maybeRequestNotificationPermission()
    }
  }

  private fun dismissQrSheet() {
    qrShareSession.dismissQrSheet()
    finish()
  }

  private suspend fun buildQrPayload(): Pair<List<FileTransferService.SendFile>, QrSharePayload> {
    val text = pendingText
    if (text != null) {
      return emptyList<FileTransferService.SendFile>() to QrSharePayload.Text(text)
    }

    val fileSystem = klardrop.commonComponent.platformFileSystem()
    val ioDispatcher = klardrop.commonComponent.coroutines().ioDispatcher

    val resolved = withContext(ioDispatcher) {
      pendingUris.map { uri ->
        val platformFile = PlatformFile(uri)
        val data = fileSystem.getResolvedFileData(platformFile)
        val sendFile = FileTransferService.SendFile(uri, data.fileName, data.fileSize, data.mimeType)
        val sharedFile = SharedFile(
          file = platformFile,
          fileName = data.fileName,
          mimeType = data.mimeType,
          fileSize = data.fileSize,
        )
        sendFile to sharedFile
      }
    }
    return resolved.map { it.first } to QrSharePayload.Files(resolved.map { it.second })
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
   * Hand the shared payload off while we still hold the read grant. Text is sent via Messenger
   * and mirrored into [ActiveSends] under [transferId]. Files of any size stream through
   * [FileTransferService] under the forwarded grant — even a tiny file gates on the receiver
   * accepting, so it needs the same foreground anchor as a big one. Must be called while this
   * Activity is still visible. Returns false when there is nothing to send.
   */
  private suspend fun dispatch(deviceId: String, transferId: String): Boolean {
    pendingText?.let {
      shareToDeviceController.sendText(deviceId, it, transferId)
      return true
    }
    if (pendingUris.isEmpty()) return false

    val fileSystem = klardrop.commonComponent.platformFileSystem()
    val ioDispatcher = klardrop.commonComponent.coroutines().ioDispatcher

    // Resolve name/size/mime now, while the grant is valid.
    val files = withContext(ioDispatcher) {
      pendingUris.map { uri ->
        val data = fileSystem.getResolvedFileData(PlatformFile(uri))
        FileTransferService.SendFile(uri, data.fileName, data.fileSize, data.mimeType)
      }
    }

    FileTransferService.start(this, deviceId, files, transferId)
    return true
  }

  private fun maybeRequestNotificationPermission(isQr: Boolean = false) {
    // Only file shares can spin up FileTransferService (and its progress notification). A text share
    // never does, so don't pester the user with a permission prompt for one.
    // For QR shares (even text), FileTransferService is always started.
    if (!isQr && pendingUris.isEmpty()) return
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
    val currentState = qrShareSession.state.value
    if (isFinishing && !isChangingConfigurations && currentState is QrShareState.QrVisible && !qrShareSession.hasClaimed) {
      qrShareSession.cancel()
    }
    shareToDeviceController.dispose()
    super.onDestroy()
  }
}

/**
 * Mutable holder so [rememberModalBottomSheetState]'s `confirmValueChange` can stay a
 * stable lambda (recreating the lambda would reset the sheet) while still reading the
 * latest in-flight progress.
 */
private class ShareSheetDismissGate {
  var progress: MessengerSendProgress? = null
  var handoffComplete: Boolean = false
  var dismissed: Boolean = false
}
