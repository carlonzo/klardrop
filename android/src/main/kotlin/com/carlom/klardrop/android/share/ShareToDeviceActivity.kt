package com.carlom.klardrop.android.share

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import com.carlom.klardrop.android.applicationComponent
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.components.KdDeviceKind
import com.carlom.klardrop.components.KdShareDevice
import com.carlom.klardrop.components.KdStatus
import com.carlom.klardrop.components.ShareSheet
import com.carlom.klardrop.theme.AppTheme
import com.carlom.klardrop.theme.KdTheme
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ShareToDeviceActivity : AppCompatActivity() {

  @Inject
  lateinit var klardrop: Klardrop

  private lateinit var shareToDeviceController: ShareToDeviceController

  // What this share invocation carries; populated from the launch intent. Exactly one is non-empty.
  private var pendingText: String? = null
  private var pendingUris: List<Uri> = emptyList()

  private val requestNotificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort; FGS runs regardless */ }

  @OptIn(ExperimentalMaterial3Api::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    applicationComponent().inject(this)

    shareToDeviceController = ShareToDeviceController(klardrop.commonComponent, applicationContext)

    parseIntent()
    // Large transfers post a progress notification from FileSendService; ask up-front so it can show.
    maybeRequestNotificationPermission()

    setContent {
      AppTheme {
        val devices by shareToDeviceController.devicesFlow.collectAsState(emptyList<DeviceUi>())
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        var selectedId by remember { mutableStateOf<String?>(null) }
        var dismissed by remember { mutableStateOf(false) }

        LaunchedEffect(dismissed) {
          if (dismissed) {
            sheetState.hide()
            finish()
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
          ShareSheet(
            trustedDevices = trustedShare,
            nearbyDevices = nearbyShare,
            selectedId = selectedId,
            onSelectDevice = { selectedId = it.id },
            onSend = { share ->
              val target = share?.id?.let(byId::get) ?: return@ShareSheet
              // Keep the Activity (and its content:// read grant) alive until the payload is safely
              // handed off — bytes copied to cache for small files, or the grant forwarded to
              // FileSendService for big ones — then dismiss. dispatch() does not wait for the whole
              // network transfer, so sharing still feels instant.
              lifecycleScope.launch {
                try {
                  dispatch(target.deviceId)
                } catch (e: Throwable) {
                  log("ShareToDeviceActivity", "Failed to dispatch share to ${target.deviceId}", e)
                } finally {
                  dismissed = true
                }
              }
            },
          )
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
   * Hand the shared payload to the right path while we still hold the read grant. Suspends only
   * for the small-file copy; returns before the network transfer finishes so the share sheet can
   * dismiss promptly.
   */
  private suspend fun dispatch(deviceId: String) {
    pendingText?.let {
      shareToDeviceController.sendText(deviceId, it)
      return
    }
    if (pendingUris.isEmpty()) return

    val fileSystem = klardrop.commonComponent.platformFileSystem()
    val ioDispatcher = klardrop.commonComponent.coroutines().ioDispatcher

    // Resolve name/size/mime now, while the grant is valid, and split big vs small.
    val resolved = withContext(ioDispatcher) {
      pendingUris.map { uri -> uri to fileSystem.getResolvedFileData(PlatformFile(uri)) }
    }

    val bigFiles = resolved
      .filter { it.second.fileSize > BIG_FILE_THRESHOLD_BYTES }
      .map { (uri, data) -> FileSendService.BigFile(uri, data.fileName, data.fileSize, data.mimeType) }
    val smallFiles = resolved
      .filter { it.second.fileSize <= BIG_FILE_THRESHOLD_BYTES }
      .map { PlatformFile(it.first) }

    if (bigFiles.isNotEmpty()) {
      // Forwards the grant into the service; safe to finish the Activity right after.
      FileSendService.start(this, deviceId, bigFiles)
    }
    if (smallFiles.isNotEmpty()) {
      // Suspends until the bytes are cached; only then is it safe to drop the grant.
      shareToDeviceController.prepareAndSendSmallFiles(deviceId, smallFiles)
    }
  }

  private fun maybeRequestNotificationPermission() {
    // Only file shares can spin up FileSendService (and its progress notification). A text share
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

  private companion object {
    /** Files larger than this stream from a foreground service; smaller ones copy to cache. */
    const val BIG_FILE_THRESHOLD_BYTES = 5L * 1024 * 1024
  }
}

private fun DeviceUi.toKdShareDevice(): KdShareDevice = KdShareDevice(
  id = deviceId,
  name = deviceName,
  kind = deviceType.toShareKind(),
  isTrusted = trustStatus == TrustStatus.Trusted,
  status = reachability.toKdStatus(),
)

private fun DeviceType.toShareKind(): KdDeviceKind = when (this) {
  DeviceType.MOBILE -> KdDeviceKind.Android
  DeviceType.DESKTOP -> KdDeviceKind.Pc
  DeviceType.UNKNOWN -> KdDeviceKind.Unknown
}

private fun Reachability.toKdStatus(): KdStatus? = when (this) {
  Reachability.Reachable -> KdStatus.Ok
  Reachability.Unreachable -> KdStatus.Err
  Reachability.Probing,
  Reachability.Unknown -> null
}
