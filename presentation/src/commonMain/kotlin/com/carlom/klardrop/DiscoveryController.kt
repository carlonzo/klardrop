package com.carlom.klardrop

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.communication.message.ConnectionInfoMessage
import com.carlom.klardrop.common.notifications.AppNotification
import com.carlom.klardrop.common.notifications.ForegroundState
import com.carlom.klardrop.common.notifications.NotificationAction
import com.carlom.klardrop.common.notifications.Notifier
import com.carlom.klardrop.common.permissions.PermissionsMonitor
import com.carlom.klardrop.common.permissions.PermissionsState
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.toSendRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.TrustedDevicesDirectory
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.features.ConnectionInfoJoiner
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
  private val trustedDevicesDirectory: TrustedDevicesDirectory,
  private val trustManager: com.carlom.klardrop.common.trust.TrustManager,
  private val pairingProtocolCoordinator: PairingProtocolCoordinator,
  private val currentDeviceProvider: com.carlom.klardrop.common.discovery.CurrentDeviceProvider,
  private val localPropertiesRepository: com.carlom.klardrop.common.persistence.LocalPropertiesRepository,
  private val connectionInfoJoiner: ConnectionInfoJoiner,
  reachability: StateFlow<Map<String, Reachability>>,
  private val permissionsMonitor: PermissionsMonitor,
  private val notifier: Notifier,
  private val foregroundState: ForegroundState,
) : OnDeviceActionListener, ReceiveNotificationsCallbacks, PairingApprovalCallback {

  constructor(commonComponent: CommonComponent) : this(
    commonComponent.coroutines(),
    commonComponent.visibleDevices(),
    commonComponent.messenger(),
    commonComponent.platformFileSystem(),
    commonComponent.clipboardManager(),
    commonComponent.messageRepository(),
    commonComponent.trustedDevicesDirectory(),
    commonComponent.trustManager(),
    commonComponent.pairingProtocolCoordinator(),
    commonComponent.currentDeviceProvider(),
    commonComponent.localPropertiesRepository(),
    commonComponent.connectionInfoJoiner(),
    commonComponent.reachability(),
    commonComponent.permissionsMonitor(),
    commonComponent.notifier(),
    commonComponent.foregroundState(),
  )

  private val controllerScope = coroutines.newScope(coroutines.mainDispatcher + SupervisorJob())
  private val showDevicesHelper = ShowDevicesControllerHelper(
    controllerScope,
    visibleDevices.visibleDevices,
    messageRepository,
    trustedDevicesDirectory.trustedDevices,
    reachability,
  )

  val permissionsState: StateFlow<PermissionsState> = permissionsMonitor.observe()
    .stateIn(controllerScope, SharingStarted.Eagerly, PermissionsState.EMPTY)

  /**
   * Re-read permission state on demand. Called by the platform app right after
   * an in-app permission prompt returns: that prompt only pauses the host
   * Activity, so [permissionsState] would otherwise stay stale (showing the
   * banner for an already-granted permission) until the next foreground cycle.
   */
  fun refreshPermissions() {
    permissionsMonitor.refresh()
  }

  /** Whether "stay discoverable in background" is enabled (persisted). The Android app observes
   *  this same pref to start/stop its discovery foreground service. */
  val backgroundDiscoveryEnabled: StateFlow<Boolean> = localPropertiesRepository.properties
    .map { it.backgroundDiscoveryEnabled }
    .stateIn(controllerScope, SharingStarted.Eagerly, false)

  /** Background discoverability is backed by a foreground service — Android-only for now. The
   *  Settings UI hides the toggle on platforms where it has no effect. */
  val supportsBackgroundDiscovery: Boolean =
    com.carlom.klardrop.common.CommonPlatformDependencies.osType() == com.carlom.klardrop.common.utils.OsType.ANDROID

  fun setBackgroundDiscoveryEnabled(enabled: Boolean) {
    controllerScope.launch { localPropertiesRepository.saveBackgroundDiscoveryEnabled(enabled) }
  }

  // Live pairing requests keyed by deviceId so a notification action delivered
  // out-of-band (the user tapping Accept on a backgrounded notification) can
  // resolve the same callbacks the in-app dialog would have invoked.
  private data class PendingPairing(
    val deviceName: String,
    val onAccept: suspend () -> Unit,
    val onReject: suspend () -> Unit,
  )
  private val pendingPairings = mutableMapOf<String, PendingPairing>()

  val screenStateFlow = MutableStateFlow(DiscoveryScreenState())

  private var activeChatDeviceId: String? = null

  fun setActiveChatDeviceId(deviceId: String?) {
    activeChatDeviceId = deviceId
  }

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
    
    // Observe peer revocations: local trust is already gone by the time we see the event;
    // we just surface it to the user with Dismiss / Pair actions in the banner stack.
    controllerScope.launch {
      pairingProtocolCoordinator.peerRevokedTrust.collect { event ->
        val deviceName = screenStateFlow.value.devices
          .firstOrNull { it.deviceId == event.deviceId }
          ?.deviceName
          ?: event.deviceId
        log("DiscoveryController", "PeerRevokedTrust from $deviceName (${event.deviceId})")
        updateDeviceTrustStatus(event.deviceId, TrustStatus.Untrusted)
        screenStateFlow.update { state ->
          // Dedupe: at most one revoked-trust notification per peer at a time.
          val existing = state.notifications.firstOrNull {
            it is UiNotification.PeerRevokedTrust && it.deviceId == event.deviceId
          }
          if (existing != null) return@update state
          state.copy(
            notifications = state.notifications + UiNotification.PeerRevokedTrust(
              id = Random.nextInt(),
              deviceId = event.deviceId,
              deviceName = deviceName,
            )
          )
        }
      }
    }

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

    // Route system-notification action taps back into the same accept/reject
    // path the in-app dialog uses, so the user can resolve a pairing without
    // having to bring the app forward.
    controllerScope.launch {
      notifier.actions.collect { action ->
        when (action) {
          is NotificationAction.PairingAccepted -> acceptPairing(action.deviceId)
          is NotificationAction.PairingRejected -> rejectPairing(action.deviceId)
          is NotificationAction.Opened -> {
            // Tapping the body brings the app forward; the persisted
            // pairingDialogState will render automatically. Nothing extra to do.
          }
        }
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

    controllerScope.launch {
      messageRepository.markMessagesAsRead(deviceUi.deviceId)
    }

    screenStateFlow.update { currentState ->
      val updatedDevices = currentState.devices.map {
        if (it.deviceId == deviceUi.deviceId) {
          it.copy(hasUnreadMessages = false)
        } else it
      }
      currentState.copy(devices = updatedDevices)
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
            if (remoteDeviceId != activeChatDeviceId) {
              updatedDevices = currentState.devices.map { deviceUi ->
                if (deviceUi.deviceId == remoteDeviceId) {
                  deviceUi.copy(hasUnreadMessages = true)
                } else deviceUi
              }
            }
            messageProcessedForUnreadUpdate = true
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

  override fun onCardDismissed(id: Int) {
    screenStateFlow.update {
      if (id !in it.receivingMessages) it
      else it.copy(receivingMessages = it.receivingMessages - id)
    }
  }

  override fun onConnectionInfoAccepted(message: ConnectionInfoMessage) {
    coroutines.appScope.launch {
      val joined = connectionInfoJoiner.tryJoin(message)
      log(
        "DiscoveryController",
        if (joined) "Requested OS join for Wi-Fi '${message.ssid}'"
        else "Fallback: copied password for '${message.ssid}' to clipboard"
      )
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

  override fun onForgetDevice(deviceUi: DeviceUi) {
    log("DiscoveryController", "Forgetting trusted device ${deviceUi.deviceName}")

    coroutines.appScope.launch {
      // Coordinator sends a signed revocation (best-effort) BEFORE wiping the local
      // trust entry, then removes locally regardless of send outcome.
      pairingProtocolCoordinator.unpair(deviceUi.deviceId, reason = "user_unpaired")
      updateDeviceTrustStatus(deviceUi.deviceId, TrustStatus.Untrusted)
    }
  }

  override fun onNotificationDismissed(notificationId: Int) {
    screenStateFlow.update { state ->
      state.copy(notifications = state.notifications.filterNot { it.id == notificationId })
    }
  }

  override fun onNotificationPair(notificationId: Int) {
    val notification = screenStateFlow.value.notifications.firstOrNull { it.id == notificationId }
      ?: return
    when (notification) {
      is UiNotification.PeerRevokedTrust -> {
        // Reuse the same path as tapping "+" on a Nearby row — confirmation has already
        // been given implicitly by tapping Pair on the notification, so no second prompt.
        val deviceUi = screenStateFlow.value.devices.firstOrNull { it.deviceId == notification.deviceId }
        if (deviceUi != null) {
          onAddToTrusted(deviceUi)
        } else {
          log("DiscoveryController", "onNotificationPair: device ${notification.deviceId} no longer visible")
        }
      }
    }
    // Always remove the notification once the user has acted on it.
    onNotificationDismissed(notificationId)
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
    pendingPairings[deviceId] = PendingPairing(deviceName, onAccept, onReject)

    controllerScope.launch {
      screenStateFlow.update { currentState ->
        // Check if a pairing dialog is already active
        if (currentState.pairingDialogState != null) {
          log("DiscoveryController", "Ignoring duplicate/concurrent pairing request for $deviceId. A dialog is already active.")
          return@update currentState // Don't update the state
        }

        log("DiscoveryController", "Creating PairingDialogState for $deviceName")
        currentState.copy(
          pairingDialogState = PairingDialogState(
            deviceId = deviceId,
            deviceName = deviceName,
            deviceType = deviceType,
            onAccept = { controllerScope.launch { acceptPairing(deviceId) } },
            onReject = { controllerScope.launch { rejectPairing(deviceId) } }
          )
        )
      }

      // System notification only fires when the user can't see the in-app
      // dialog. Foreground state is observed reactively but the request
      // arrives once, so we sample the current value here.
      if (!foregroundState.isForeground.value) {
        log("DiscoveryController", "App backgrounded; posting pairing notification for $deviceName")
        notifier.show(
          AppNotification.IncomingPairing(
            id = deviceId,
            deviceId = deviceId,
            deviceName = deviceName,
          )
        )
      }
    }
  }

  private suspend fun acceptPairing(deviceId: String) {
    val pending = pendingPairings[deviceId] ?: run {
      log("DiscoveryController", "acceptPairing: no pending request for $deviceId (already resolved?)")
      return
    }
    try {
      pending.onAccept()
      pendingPairings.remove(deviceId)
      notifier.cancel(deviceId)
      // Only clear the dialog if it still belongs to *this* request — a later
      // pairing might already have replaced it.
      screenStateFlow.update { state ->
        if (state.pairingDialogState?.deviceId == deviceId) state.copy(pairingDialogState = null)
        else state
      }
      updateDeviceTrustStatus(deviceId, TrustStatus.Trusted)
      log("DiscoveryController", "Pairing accepted for ${pending.deviceName}")
    } catch (e: Exception) {
      log("DiscoveryController", "Failed to accept pairing: ${e.message}")
      screenStateFlow.update { state ->
        val current = state.pairingDialogState ?: return@update state
        if (current.deviceId != deviceId) return@update state
        state.copy(
          pairingDialogState = current.copy(
            isError = true,
            errorMessage = "Failed to accept pairing: ${e.message}"
          )
        )
      }
      updateDeviceTrustStatus(deviceId, TrustStatus.Untrusted)
    }
  }

  private suspend fun rejectPairing(deviceId: String) {
    val pending = pendingPairings[deviceId] ?: run {
      log("DiscoveryController", "rejectPairing: no pending request for $deviceId (already resolved?)")
      return
    }
    try {
      pending.onReject()
      pendingPairings.remove(deviceId)
      notifier.cancel(deviceId)
      screenStateFlow.update { state ->
        if (state.pairingDialogState?.deviceId == deviceId) state.copy(pairingDialogState = null)
        else state
      }
      updateDeviceTrustStatus(deviceId, TrustStatus.Untrusted)
      log("DiscoveryController", "Pairing rejected for ${pending.deviceName}")
    } catch (e: Exception) {
      log("DiscoveryController", "Failed to reject pairing: ${e.message}")
      screenStateFlow.update { state ->
        val current = state.pairingDialogState ?: return@update state
        if (current.deviceId != deviceId) return@update state
        state.copy(
          pairingDialogState = current.copy(
            isError = true,
            errorMessage = "Failed to reject pairing: ${e.message}"
          )
        )
      }
      updateDeviceTrustStatus(deviceId, TrustStatus.Untrusted)
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
  val pairingDialogState: PairingDialogState? = null,
  val currentDeviceName: String? = null,
  val systemDeviceName: String? = null,
  /**
   * In-app notifications surfaced in the top banner stack. Distinct from
   * [receivingMessages] (which is purely about inbound transfers); this is the
   * generic surface for system-level events the user should see — currently
   * just peer-revoked-trust, but the seam exists for future ones.
   */
  val notifications: List<UiNotification> = emptyList(),
)

sealed interface UiNotification {
  val id: Int

  /**
   * A previously-paired peer revoked us. Local trust has already been removed by the
   * time this is surfaced. The banner offers Dismiss (clear it) and Pair (re-pair).
   */
  data class PeerRevokedTrust(
    override val id: Int,
    val deviceId: String,
    val deviceName: String,
  ) : UiNotification
}

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
  val trustStatus: TrustStatus = TrustStatus.Unknown,
  val reachability: Reachability = Reachability.Unknown,
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
