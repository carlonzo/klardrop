package com.carlom.klardrop

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
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.trust.PairingApprovalCallback
import com.carlom.klardrop.common.trust.PairingProtocolCoordinator
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.log
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
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
  private val messageRepository: MessageRepository,
  private val trustStorage: com.carlom.klardrop.common.trust.TrustStorage,
  private val trustManager: com.carlom.klardrop.common.trust.TrustManager,
  private val pairingProtocolCoordinator: PairingProtocolCoordinator,
  private val currentDeviceProvider: com.carlom.klardrop.common.discovery.CurrentDeviceProvider,
  private val localPropertiesRepository: com.carlom.klardrop.common.persistence.LocalPropertiesRepository
) : OnDeviceActionListener, ReceiveNotificationsCallbacks, PairingApprovalCallback {

  constructor(commonComponent: CommonComponent) : this(
    commonComponent.coroutines(),
    commonComponent.visibleDevices(),
    commonComponent.messenger(),
    commonComponent.platformFileSystem(),
    commonComponent.clipboardManager(),
    commonComponent.messageRepository(),
    commonComponent.trustStorage(),
    commonComponent.trustManager(),
    commonComponent.pairingProtocolCoordinator(),
    commonComponent.currentDeviceProvider(),
    commonComponent.localPropertiesRepository()
  )

  private val controllerScope = coroutines.newScope(coroutines.mainDispatcher + SupervisorJob())
  private val showDevicesHelper = ShowDevicesControllerHelper(controllerScope, visibleDevices, messageRepository, trustStorage)

  val screenStateFlow = MutableStateFlow(DiscoveryScreenState())

  init {
    controllerScope.launch {
      messenger.receive().collect { (remoteDeviceId, flowOfUpdates) -> // Changed
        listenNewMessagesReceived(remoteDeviceId, flowOfUpdates) // Changed
      }
    }

    controllerScope.launch {
      showDevicesHelper.devicesFlow.collect {
        screenStateFlow.update { state ->
          state.copy(devices = it.toList())
        }
      }
    }

    // Register the pairing approval callback
    trustManager.setPairingApprovalCallback(this)
    log("DiscoveryController", "Registered pairingApprovalCallback with TrustManager.")
    
    // Register pairing completion callback
    pairingProtocolCoordinator.onPairingCompleted = { deviceId, deviceName, success ->
      log("DiscoveryController", "Pairing completion callback: $deviceName ($deviceId), success: $success")
      if (success) {
        log("DiscoveryController", "Updating UI to show device $deviceName as Trusted")
        updateDeviceTrustStatus(deviceId, TrustStatus.Trusted)
      } else {
        log("DiscoveryController", "Updating UI to show device $deviceName as Untrusted (pairing failed)")
        updateDeviceTrustStatus(deviceId, TrustStatus.Untrusted)
      }
    }

    // Ensure identity fields are loaded for the IdentityCard on first render
    loadDeviceNames()
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
    log("DiscoveryController", "on device click for chat: ${deviceUi.deviceName}")
    
    // Mark messages as read in the database
    controllerScope.launch {
      messageRepository.markMessagesAsRead(deviceUi.deviceId)
    }
    
    screenStateFlow.update { currentState ->
      val updatedDevices = currentState.devices.map {
        if (it.deviceId == deviceUi.deviceId) {
          it.copy(hasUnreadMessages = false) // Clear unread on click
        } else it
      }
      currentState.copy(
        devices = updatedDevices,
        navigateToChatDeviceId = deviceUi.deviceId,
        navigateToChatDeviceName = deviceUi.deviceName
      )
    }
  }

  // This method might still be used if sending is initiated from somewhere else,
  // or could be refactored if all sending now happens via chat.
  // For now, keeping it as is.
  override fun onSendData(deviceUi: DeviceUi, onDataToSend: OnDataToSend) {

    when (onDataToSend) {
      is OnDataToSend.FilesList -> sendFiles(deviceUi.deviceId, onDataToSend.files)
      is OnDataToSend.Text -> sendText(deviceUi.deviceId, onDataToSend.text)
    }

  }

  private fun listenNewMessagesReceived(remoteDeviceId: String, flow: Flow<ReceiveMessageUpdate>) { // Changed

    val receiveId = Random.nextInt() // Used to track individual notification cards

    controllerScope.launch {

      var messageProcessedForUnreadUpdate = false
      flow.transformWhile {
        emit(it)
        !it.status.isFinished()
      }.collect { receiveMessageUpdate ->
        screenStateFlow.update { currentState ->
          val messagesMap = currentState.receivingMessages.toMutableMap()
          messagesMap[receiveId] = receiveMessageUpdate

          var updatedDevices = currentState.devices
          if (!messageProcessedForUnreadUpdate && receiveMessageUpdate.status.isFinished() && receiveMessageUpdate.status !is ReceiveMessageStatus.Failed) {
            if (remoteDeviceId != currentState.navigateToChatDeviceId) {
              updatedDevices = currentState.devices.map { deviceUi ->
                if (deviceUi.deviceId == remoteDeviceId) {
                  deviceUi.copy(hasUnreadMessages = true)
                } else deviceUi
              }
            }
            messageProcessedForUnreadUpdate = true // Mark as processed for this flow instance
          }

          currentState.copy(
            devices = updatedDevices,
            receivingMessages = messagesMap
          )
        }
      }
    }
  }

  fun dispose() {
    // TODO implement dispose logic
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

  fun onBackFromChat() {
    screenStateFlow.update {
      it.copy(navigateToChatDeviceId = null, navigateToChatDeviceName = null)
    }
  }

  override fun onAddToTrusted(deviceUi: DeviceUi) {
    log("DiscoveryController", "onAddToTrusted() called for device: ${deviceUi.deviceName} (${deviceUi.deviceId})")
    log("DiscoveryController", "Adding device ${deviceUi.deviceName} to trusted")
    
    // Update UI to show pairing state
    log("DiscoveryController", "Updating UI to show Pairing state")
    updateDeviceTrustStatus(deviceUi.deviceId, TrustStatus.Pairing)
    
    coroutines.appScope.launch {
      log("DiscoveryController", "Calling pairingProtocolCoordinator.initiatePairing(${deviceUi.deviceId})")
      val result = pairingProtocolCoordinator.initiatePairing(deviceUi.deviceId)
      log("DiscoveryController", "pairingProtocolCoordinator.initiatePairing() returned: ${if (result.isSuccess) "SUCCESS" else "FAILURE"}")
      
      result.fold(
        onSuccess = {
          log("DiscoveryController", "SUCCESS: Pairing initiation succeeded for ${deviceUi.deviceName}")
          log("DiscoveryController", "Successfully initiated pairing with ${deviceUi.deviceName}")
          // The TrustManager callbacks will update the UI state via trust flow
        },
        onFailure = { error ->
          log("DiscoveryController", "FAILURE: Pairing initiation failed for ${deviceUi.deviceName}: ${error.message}")
          log("DiscoveryController", "Failed to initiate pairing with ${deviceUi.deviceName}: ${error.message}")
          // Reset to untrusted state on failure
          updateDeviceTrustStatus(deviceUi.deviceId, TrustStatus.Untrusted)
        }
      )
    }
  }

  override fun onRemoveTrust(deviceUi: DeviceUi) {
    log("DiscoveryController", "Removing trust for device ${deviceUi.deviceName}")
    
    coroutines.appScope.launch {
      trustStorage.removeTrustedDevice(deviceUi.deviceId)
      updateDeviceTrustStatus(deviceUi.deviceId, TrustStatus.Untrusted)
    }
  }

  private fun updateDeviceTrustStatus(deviceId: String, newStatus: TrustStatus) {
    screenStateFlow.update { currentState ->
      val updatedDevices = currentState.devices.map { device ->
        if (device.deviceId == deviceId) {
          device.copy(trustStatus = newStatus)
        } else device
      }
      currentState.copy(devices = updatedDevices)
    }
  }

  // Implementation of PairingApprovalCallback
  override fun onPairingRequested(
    deviceId: String,
    deviceName: String,
    deviceType: String,
    onAccept: suspend () -> Unit,
    onReject: suspend () -> Unit
  ) {
    log("DiscoveryController", "onPairingRequested for device: $deviceName ($deviceId)")
    log("DiscoveryController", "About to update pairingDialogState in screenStateFlow")
    controllerScope.launch {
      screenStateFlow.update { currentState ->
        // Check if a pairing dialog is already active
        if (currentState.pairingDialogState != null) {
          log("DiscoveryController", "Ignoring duplicate/concurrent pairing request for $deviceId. A dialog is already active.")
          return@update currentState // Don't update the state
        }

        log("DiscoveryController", "Creating PairingDialogState for $deviceName")
        val newState = currentState.copy(
          pairingDialogState = PairingDialogState(
            deviceId = deviceId,
            deviceName = deviceName,
            deviceType = deviceType,
            onAccept = {
              controllerScope.launch {
                try {
                  onAccept()
                  screenStateFlow.update { it.copy(pairingDialogState = null) }
                  updateDeviceTrustStatus(deviceId, TrustStatus.Trusted)
                  log("DiscoveryController", "Pairing accepted for $deviceName")
                } catch (e: Exception) {
                  log("DiscoveryController", "Failed to accept pairing: ${e.message}")
                  screenStateFlow.update { state ->
                    state.copy(
                      pairingDialogState = state.pairingDialogState?.copy(
                        isError = true,
                        errorMessage = "Failed to accept pairing: ${e.message}"
                      )
                    )
                  }
                  updateDeviceTrustStatus(deviceId, TrustStatus.Untrusted)
                }
              }
            },
            onReject = {
              controllerScope.launch {
                try {
                  onReject()
                  screenStateFlow.update { it.copy(pairingDialogState = null) }
                  updateDeviceTrustStatus(deviceId, TrustStatus.Untrusted)
                  log("DiscoveryController", "Pairing rejected for $deviceName")
                } catch (e: Exception) {
                  log("DiscoveryController", "Failed to reject pairing: ${e.message}")
                  screenStateFlow.update { state ->
                    state.copy(
                      pairingDialogState = state.pairingDialogState?.copy(
                        isError = true,
                        errorMessage = "Failed to reject pairing: ${e.message}"
                      )
                    )
                  }
                  updateDeviceTrustStatus(deviceId, TrustStatus.Untrusted)
                }
              }
            }
          )
        )
        log("DiscoveryController", "Successfully updated screenStateFlow with pairingDialogState for $deviceName")
        log("DiscoveryController", "New state pairingDialogState: ${newState.pairingDialogState != null}")
        newState
      }
      log("DiscoveryController", "Current screenStateFlow pairingDialogState after update: ${screenStateFlow.value.pairingDialogState != null}")
      screenStateFlow.value.pairingDialogState?.let { state ->
        log("DiscoveryController", "PairingDialogState details: deviceId=${state.deviceId}, deviceName=${state.deviceName}")
      }
    }
  }

  fun dismissPairingDialog() {
    screenStateFlow.update { it.copy(pairingDialogState = null) }
  }

  fun loadDeviceNames() {
    controllerScope.launch {
      val systemName = com.carlom.klardrop.common.CommonPlatformDependencies.getDeviceName()
      val properties = localPropertiesRepository.getProperty()
      screenStateFlow.update { state ->
        state.copy(
          systemDeviceName = systemName,
          currentDeviceName = properties.customDeviceName?.takeIf { it.isNotBlank() } ?: systemName
        )
      }
    }
  }

  fun saveCustomDeviceName(customName: String?) {
    controllerScope.launch {
      currentDeviceProvider.updateCustomDeviceName(customName)
      loadDeviceNames()
    }
  }
}

data class DiscoveryScreenState(
  val devices: List<DeviceUi> = emptyList(),
  val receivingMessages: Map<Int, ReceiveMessageUpdate> = emptyMap(),
  val navigateToChatDeviceId: String? = null,    // New
  val navigateToChatDeviceName: String? = null,   // New
  val pairingDialogState: PairingDialogState? = null,
  val currentDeviceName: String? = null,
  val systemDeviceName: String? = null
)

data class PairingDialogState(
  val deviceId: String,
  val deviceName: String,
  val deviceType: String,
  val onAccept: () -> Unit,
  val onReject: () -> Unit,
  val isError: Boolean = false,
  val errorMessage: String? = null
)

data class DeviceUi(
  val deviceId: String,
  val deviceName: String,
  val deviceType: DeviceType,
  val activityState: ActivityState = ActivityState.Idle,
  val connectionTypes: List<DeviceConnection.DeviceConnectionType>,
  val hasUnreadMessages: Boolean = false,
  val trustStatus: TrustStatus = TrustStatus.Unknown
)

sealed interface TrustStatus {
  object Unknown : TrustStatus
  object Untrusted : TrustStatus  
  object Trusted : TrustStatus
  object Pairing : TrustStatus
}

sealed interface ActivityState {

  data object Idle : ActivityState

  data class SentCompleted(val error: Boolean = false) : ActivityState
//  data class ReceiveCompleted(val error: Boolean = false) : ActivityState

  data class Sending(val progressPercentage: Int) : ActivityState

//  data class Receiving(val progressPercentage : Int) : ActivityState

}