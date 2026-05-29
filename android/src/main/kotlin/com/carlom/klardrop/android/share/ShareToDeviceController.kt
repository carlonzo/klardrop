package com.carlom.klardrop.android.share

import com.carlom.klardrop.DeviceUi
import com.carlom.klardrop.ShowDevicesControllerHelper
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.utils.Coroutines
import android.content.Context
import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.log
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

class ShareToDeviceController(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val messenger: Messenger,
  private val platformFileSystem: PlatformFileSystem,
  private val messageRepository: MessageRepository,
  private val trustStorage: com.carlom.klardrop.common.trust.TrustStorage,
  private val reachabilitySource: StateFlow<Map<String, Reachability>>,
  private val context: Context,
) {

  constructor(commonComponent: CommonComponent, context: Context) : this(
    coroutines = commonComponent.coroutines(),
    visibleDevices = commonComponent.visibleDevices(),
    messenger = commonComponent.messenger(),
    platformFileSystem = commonComponent.platformFileSystem(),
    messageRepository = commonComponent.messageRepository(),
    trustStorage = commonComponent.trustStorage(),
    reachabilitySource = commonComponent.reachability(),
    context = context,
  )

  private val controllerScope = coroutines.newScope(coroutines.mainDispatcher)
  private val showDevicesHelper = ShowDevicesControllerHelper(controllerScope, visibleDevices, messageRepository, trustStorage, reachabilitySource)
  private val wifiLock by lazy { WifiTransferLock(context) }

  val devicesFlow: Flow<Collection<DeviceUi>> = showDevicesHelper.devicesFlow

  fun dispose() {
    controllerScope.cancel()
  }

  /** Fire-and-forget text send. Text carries no content grant, so nothing needs to be cached. */
  fun sendText(deviceId: String, text: String) {
    coroutines.appScope.launch {
      runCatching {
        messenger.send(deviceId, TextMessage(text = text).toSimpleSendRequest()).untilCompleted().collect { }
      }.onFailure { log("ShareToDeviceController", "Text send failed", it) }
    }
  }

  /**
   * Copy each small shared file into app cache (suspending until the bytes are safely cached, while
   * the caller still holds the `content://` read grant), then send the cached copies on the app
   * scope so the transfer outlives the share Activity. Returns once copying is done — the caller is
   * then free to finish the Activity; the grant is no longer needed.
   *
   * Large files are NOT routed here; they go through [FileSendService] which streams straight from
   * the URI under a forwarded grant (see the share Activity's routing).
   */
  suspend fun prepareAndSendSmallFiles(deviceId: String, files: List<PlatformFile>) {
    if (files.isEmpty()) return
    val prepared = files.map { platformFileSystem.prepareFileForSending(it) }

    coroutines.appScope.launch {
      // Keep WiFi at full power for the whole batch; released in finally on success OR failure.
      wifiLock.acquire()
      try {
        prepared.forEach { p ->
          runCatching {
            messenger.send(
              deviceId,
              FileMessage(p.data.fileName, p.data.fileSize, p.data.mimeType).toSendRequest(p.file),
            ).untilCompleted().collect { }
          }.onFailure { log("ShareToDeviceController", "Small-file send of ${p.data.fileName} failed", it) }
          // Best-effort cleanup once the transfer reached a terminal state (incl. exhausted retries).
          runCatching { platformFileSystem.delete(Path(p.file.path)) }
        }
      } finally {
        wifiLock.release()
      }
    }
  }

}
