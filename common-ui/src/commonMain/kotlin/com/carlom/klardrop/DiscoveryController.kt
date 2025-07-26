package com.carlom.klardrop

import com.carlom.klardrop.common.CommonPlatformDependencies
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.log
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import io.github.vinceglb.filekit.toKotlinxIoPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

// TODO should this be composable and get the dispose callback to cancel scope?
class DiscoveryController(
  private val coroutines: Coroutines,
  visibleDevices: VisibleDevices,
  private val messenger: Messenger,
  private val platformFileSystem: PlatformFileSystem,
  private val clipboardManager: ClipboardManager,
  private val trustManager: com.carlom.klardrop.common.trust.TrustManager? = null,
) : OnDeviceActionListener, ReceiveNotificationsCallbacks {

  constructor(commonComponent: CommonComponent) : this(
    commonComponent.coroutines(),
    commonComponent.visibleDevices(),
    commonComponent.messenger(),
    commonComponent.platformFileSystem(),
    commonComponent.clipboardManager(),
    commonComponent.trustManager()
  )

  private val controllerScope = coroutines.newScope(coroutines.mainDispatcher + SupervisorJob())
  private val showDevicesHelper = ShowDevicesControllerHelper(controllerScope, visibleDevices)

  val actionsFlow = MutableSharedFlow<ActionUi>()
  val screenStateFlow = MutableStateFlow(DiscoveryScreenState())

  init {
    controllerScope.launch {
      messenger.receive().collect {
        listenNewMessagesReceived(it)
      }
    }

    controllerScope.launch {
      showDevicesHelper.devicesFlow.collect { devices ->
        screenStateFlow.update { state ->
          // Enhance devices with trust information
          val enhancedDevices = devices.map { deviceUi ->
            trustManager?.let { tm ->
              val trustStatus = runCatching {
                val isTrusted = tm.isDeviceTrusted(deviceUi.deviceId).let { 
                  coroutines.mainDispatcher { it }
                }
                val trustLevel = tm.getDeviceTrustLevel(deviceUi.deviceId).let {
                  coroutines.mainDispatcher { it }
                }
                
                deviceUi.copy(
                  trustStatus = if (isTrusted) {
                    com.carlom.klardrop.common.trust.model.TrustStatus.TRUSTED
                  } else {
                    com.carlom.klardrop.common.trust.model.TrustStatus.UNTRUSTED
                  },
                  trustLevel = trustLevel,
                  isTrustGroupMember = isTrusted
                )
              }.getOrNull() ?: deviceUi
            } ?: deviceUi
          }
          
          state.copy(devices = enhancedDevices.toList())
        }
      }
    }
    
    // Listen for trust events
    trustManager?.let { tm ->
      controllerScope.launch {
        tm.getTrustEvents().collect { event ->
          handleTrustEvent(event)
        }
      }
    }
  }

  private fun sendText(deviceId: String, text: String) {
    coroutines.appScope.launch {
      messenger.send(deviceId, TextMessage(text = text).toSimpleSendRequest())
        .untilCompleted().let { showDevicesHelper.collectProgress(it, deviceId) }
    }
  }

  private fun sendFiles(deviceId: String, files: List<PlatformFile>) {
    coroutines.appScope.launch {
      files.forEach { file ->

        val fileData = runCatching { platformFileSystem.getResolvedFileData(file) }
          .onFailure { log("DiscoveryController", "Unable to resolve file at path $file. File cannot be sent!", it) }
          .getOrNull() ?: return@forEach

        messenger.send(
          deviceId, FileMessage(
            fileData.fileName,
            fileData.fileSize,
            fileData.mimeType
          ).toSendRequest(file)
        ).untilCompleted().let { showDevicesHelper.collectProgress(it, deviceId) }
      }
    }
  }

  override fun onDeviceClick(deviceUi: DeviceUi) {
    log("DiscoveryController", "on device click: ${deviceUi.deviceName}")
    controllerScope.launch {
      actionsFlow.emit(ActionUi.OnDeviceClicked(deviceUi))
    }
  }

  override fun onSendData(deviceUi: DeviceUi, onDataToSend: OnDataToSend) {

    when (onDataToSend) {
      is OnDataToSend.FilesList -> sendFiles(deviceUi.deviceId, onDataToSend.files)
      is OnDataToSend.Text -> sendText(deviceUi.deviceId, onDataToSend.text)
    }

  }

  private fun listenNewMessagesReceived(flow: Flow<ReceiveMessageUpdate>) {

    val receiveId = Random.nextInt()

    controllerScope.launch {

      flow.transformWhile {
        emit(it)

        !it.status.isFinished()
      }.collect { receiveMessageUpdate ->
        screenStateFlow.update {
          val messages = it.receivingMessages.toMutableMap()
          messages[receiveId] = receiveMessageUpdate

          it.copy(
            receivingMessages = messages
          )
        }
      }

    }

  }

  fun dispose() {
    controllerScope.cancel()
  }

  fun readFromClipboard(): String {
    return clipboardManager.read()
  }

  override fun onReceivedCardClicked(receiveUpdate: ReceiveMessageUpdate) {

    if (receiveUpdate.status is ReceiveMessageStatus.Completed) {

      val firstMessage = receiveUpdate.messages.first()

      if (firstMessage is TextMessage) {
        val text = firstMessage.text
        clipboardManager.write(text)
      }

    }

  }

  override fun onCardDismissed(receiveUpdate: ReceiveMessageUpdate) {
    screenStateFlow.update {
      val key = it.receivingMessages.entries.firstOrNull { entry -> entry.value == receiveUpdate }?.key

      if (key == null) it
      else it.copy(
        receivingMessages = it.receivingMessages - key
      )
    }

  }
  
  /**
   * Handle trust device action
   */
  fun onTrustDevice(deviceId: String) {
    trustManager?.let { tm ->
      coroutines.appScope.launch {
        try {
          actionsFlow.emit(ActionUi.PairingStarted)
          
          // Initiate pairing
          tm.initiatePairing(deviceId)
          
          // Wait a bit for pairing to complete (in real implementation, this would be event-driven)
          kotlinx.coroutines.delay(2000)
          
          // Check if pairing succeeded
          val isTrusted = tm.isDeviceTrusted(deviceId)
          
          actionsFlow.emit(ActionUi.PairingCompleted(
            success = isTrusted,
            errorMessage = if (!isTrusted) "Failed to establish trust" else null
          ))
          
          // Update device list to reflect new trust status
          showDevicesHelper.refreshDevices()
          
        } catch (e: Exception) {
          log("DiscoveryController", "Failed to trust device", e)
          actionsFlow.emit(ActionUi.PairingCompleted(
            success = false,
            errorMessage = e.message ?: "Unknown error"
          ))
        }
      }
    }
  }
  
  /**
   * Handle trust events from TrustManager
   */
  private suspend fun handleTrustEvent(event: com.carlom.klardrop.common.trust.protocol.TrustEvent) {
    when (event) {
      is com.carlom.klardrop.common.trust.protocol.TrustEvent.NewDeviceNearby -> {
        // Create a trust notification
        val notification = com.carlom.klardrop.common.trust.model.TrustNotification.NewDeviceNearby(
          device = event.device,
          onAccept = {
            onTrustDevice(event.device.deviceId)
          },
          onDecline = {
            // Remove notification from UI
            screenStateFlow.update { state ->
              state.copy(
                trustNotifications = state.trustNotifications.filterNot { 
                  it.device.deviceId == event.device.deviceId 
                }
              )
            }
          }
        )
        
        // Add notification to screen state
        screenStateFlow.update { state ->
          state.copy(
            trustNotifications = state.trustNotifications + notification
          )
        }
        
        // Also emit as action for immediate handling
        actionsFlow.emit(ActionUi.TrustNotification(notification))
      }
      
      is com.carlom.klardrop.common.trust.protocol.TrustEvent.DeviceJoined,
      is com.carlom.klardrop.common.trust.protocol.TrustEvent.DeviceRemoved,
      is com.carlom.klardrop.common.trust.protocol.TrustEvent.DeviceUpdated -> {
        // Refresh device list when trust relationships change
        showDevicesHelper.refreshDevices()
      }
      
      is com.carlom.klardrop.common.trust.protocol.TrustEvent.ClipboardUpdate -> {
        // Handle clipboard sync updates
        if (event.deviceId != trustManager?.currentDeviceKeypair?.value?.deviceId) {
          clipboardManager.write(event.content)
        }
      }
      
      else -> {
        // Other events can be logged or handled as needed
        log("DiscoveryController", "Unhandled trust event: $event")
      }
    }
  }
}

data class DiscoveryScreenState(
  val devices: List<DeviceUi> = emptyList(),
  val receivingMessages: Map<Int, ReceiveMessageUpdate> = emptyMap(),
  val trustNotifications: List<com.carlom.klardrop.common.trust.model.TrustNotification.NewDeviceNearby> = emptyList()
)

sealed interface ActionUi {

  class OnDeviceClicked(val deviceUi: DeviceUi) : ActionUi
  
  class TrustNotification(val notification: com.carlom.klardrop.common.trust.model.TrustNotification) : ActionUi
  
  object PairingStarted : ActionUi
  
  data class PairingCompleted(val success: Boolean, val errorMessage: String? = null) : ActionUi

}

data class DeviceUi(
  val deviceId: String,
  val deviceName: String,
  val deviceType: DeviceType,
  val activityState: ActivityState = ActivityState.Idle,
  val connectionTypes: List<DeviceConnection.DeviceConnectionType>,
  val trustStatus: com.carlom.klardrop.common.trust.model.TrustStatus = com.carlom.klardrop.common.trust.model.TrustStatus.UNTRUSTED,
  val trustLevel: com.carlom.klardrop.protos.trust.TrustLevel? = null,
  val isTrustGroupMember: Boolean = false,
  val hasClipboardSyncPermission: Boolean = false,
  val hasFileSendPermission: Boolean = true,
  val hasFileReceivePermission: Boolean = true
)

sealed interface ActivityState {

  data object Idle : ActivityState

  data class SentCompleted(val error: Boolean = false) : ActivityState
//  data class ReceiveCompleted(val error: Boolean = false) : ActivityState

  data class Sending(val progressPercentage: Int) : ActivityState

//  data class Receiving(val progressPercentage : Int) : ActivityState

}