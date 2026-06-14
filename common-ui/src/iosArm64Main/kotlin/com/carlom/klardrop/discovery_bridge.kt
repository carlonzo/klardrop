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
    val deviceList = devices.toList()
    val (trusted, nearby) = deviceList.partition { it.trustStatus == TrustStatus.Trusted }

    AppTheme {
      ShareSheet(
        trustedDevices = trusted.map { it.toKdShareDevice() },
        nearbyDevices = nearby.map { it.toKdShareDevice() },
        selectedId = selectedId,
        onSelectDevice = { selectedId = it.id },
        onSend = { share ->
          share ?: return@ShareSheet
          val target = deviceList.firstOrNull { it.deviceId == share.id } ?: return@ShareSheet
          val messenger = klardrop.commonComponent.messenger()
          val platformFileSystem = klardrop.commonComponent.platformFileSystem()
          val coroutines = klardrop.commonComponent.coroutines()
          coroutines.appScope.launch {
            filePaths.forEach { path ->
              val file: PlatformFile = NSURL.fileURLWithPath(path) ?: return@forEach
              runCatching { platformFileSystem.getResolvedFileData(file) }.getOrNull()?.let { fileData ->
                messenger.send(
                  target.deviceId,
                  FileMessage(fileData.fileName, fileData.fileSize, fileData.mimeType).toSendRequest(file)
                )
              }
            }
          }
          onDismiss()
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
