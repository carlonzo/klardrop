@file:Suppress("unused")

package com.carlom.klardrop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.components.KdShareDevice
import com.carlom.klardrop.components.KdStatus
import com.carlom.klardrop.components.ShareSheet
import com.carlom.klardrop.theme.AppTheme
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.launch
import platform.Foundation.NSURL
import platform.UIKit.UIViewController

class DiscoveryBridge {
  val klardrop = Klardrop(internalPlatformDependency = InternalPlatformDependencies(ApplicationInfo()))

  init {
    klardrop.init()
  }

  fun RootKlardropApp() = ComposeUIViewController {
    AppTheme {
      KlardropApp(
        klardrop = klardrop,
      )
    }
  }

  /**
   * Builds the device-picker shown after another app shares files into Klardrop.
   * [filePaths] are absolute paths inside the App Group container, copied there by
   * the share extension. [onDismiss] is invoked on completion so the host can tear
   * down the sheet and delete the now-sent temp files — never call it before the
   * transfer finishes or the source files would be removed mid-read.
   */
  fun makeShareViewController(
    filePaths: List<String>,
    onDismiss: () -> Unit,
  ): UIViewController = ComposeUIViewController {
    val scope = rememberCoroutineScope()
    val helper = remember {
      ShowDevicesControllerHelper(
        coroutineScope = scope,
        visibleDevices = klardrop.commonComponent.visibleDevices(),
        messageRepository = klardrop.commonComponent.messageRepository(),
        trustStorage = klardrop.commonComponent.trustStorage(),
        reachabilitySource = klardrop.commonComponent.reachability(),
      )
    }

    val devices by helper.devicesFlow.collectAsState(initial = emptyList())
    var selectedId by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val deviceList = devices.toList()
    val (trusted, nearby) = deviceList.partition { it.trustStatus == TrustStatus.Trusted }

    AppTheme {
      ShareSheet(
        trustedDevices = trusted.map { it.toKdShareDevice() },
        nearbyDevices = nearby.map { it.toKdShareDevice() },
        selectedId = selectedId,
        onSelectDevice = { if (!sending) selectedId = it.id },
        onSend = { share ->
          if (sending) return@ShareSheet
          share ?: return@ShareSheet
          val target = deviceList.firstOrNull { it.deviceId == share.id } ?: return@ShareSheet
          sending = true

          val messenger = klardrop.commonComponent.messenger()
          val platformFileSystem = klardrop.commonComponent.platformFileSystem()
          val coroutines = klardrop.commonComponent.coroutines()

          // appScope outlives this sheet so the transfer survives dismissal.
          coroutines.appScope.launch {
            filePaths.forEach { path ->
              val file = PlatformFile(NSURL.fileURLWithPath(path))
              val fileData = runCatching { platformFileSystem.getResolvedFileData(file) }.getOrNull()
                ?: return@forEach
              // Must collect the returned flow: send() emits into a SharedFlow with a
              // single-slot buffer and suspends after the first emit until drained.
              val progress = messenger.send(
                target.deviceId,
                FileMessage(fileData.fileName, fileData.fileSize, fileData.mimeType).toSendRequest(file)
              ).untilCompleted()
              helper.collectProgress(progress, target.deviceId)
            }
            // Files are only safe to delete once every transfer has finished.
            onDismiss()
          }
        },
      )
    }
  }
}

private fun DeviceUi.toKdShareDevice(): KdShareDevice = KdShareDevice(
  id = deviceId,
  name = deviceName,
  kind = deviceType.toKdDeviceKind(),
  isTrusted = trustStatus == TrustStatus.Trusted,
  status = reachability.toKdStatus(),
)

private fun Reachability.toKdStatus(): KdStatus? = when (this) {
  Reachability.Reachable -> KdStatus.Ok
  Reachability.Unreachable -> KdStatus.Err
  else -> null
}
