package com.carlom.klardrop

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
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
import com.carlom.klardrop.common.connectivity.ConnectivityRestrictions
import com.carlom.klardrop.common.connectivity.ConnectivityRestrictionMonitor
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
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

// TODO should this be composable and get the dispose callback to cancel scope?
public class DiscoveryController internal constructor(
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
  private val connectivityRestrictionMonitor: ConnectivityRestrictionMonitor,
  private val notifier: Notifier,
  private val foregroundState: ForegroundState,
) : OnDeviceActionListener, ReceiveNotificationsCallbacks, PairingApprovalCallback {

  public constructor(commonComponent: CommonComponent) : this(
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
    commonComponent.connectivityRestrictionMonitor(),
    commonComponent.notifier(),
    commonComponent.foregroundState(),
  )

  private val controllerScope = coroutines.newScope(coroutines.mainDispatcher + SupervisorJob())

  /**
   * Pairing-failure messages per device. Lives OUTSIDE the DeviceUi list because
   * [ShowDevicesControllerHelper] rebuilds that list from discovery on every tick, which
   * would wipe a field set directly on the rows — the map is merged back in at collect time.
   * Accessed only from [controllerScope] (single-threaded main dispatcher).
   */
  private val pairingErrors = mutableMapOf<String, String>()

  private val showDevicesHelper = ShowDevicesControllerHelper(
    controllerScope,
    visibleDevices.visibleDevices,
    messageRepository,
    trustedDevicesDirectory.trustedDevices,
    reachability,
  )

  public val permissionsState: StateFlow<PermissionsState> = permissionsMonitor.observe()
    .stateIn(controllerScope, SharingStarted.Eagerly, PermissionsState.EMPTY)

  /**
   * OS-level connectivity restrictions on THIS device (battery saver, metered deny).
   * Surfaced as a banner and used to prefix pairing-failure reasons — a connect
   * failure while restricted is this device's OS dropping packets, not the peer.
   */
  public val connectivityRestrictions: StateFlow<ConnectivityRestrictions> =
    connectivityRestrictionMonitor.observe()
      .stateIn(controllerScope, SharingStarted.Eagerly, ConnectivityRestrictions.EMPTY)

  /** Re-read restriction state on demand — called after the OS exemption prompt returns. */
  public fun refreshConnectivityRestrictions(): Unit {
    connectivityRestrictionMonitor.refresh()
  }

  /**
   * Re-read permission state on demand. Called by the platform app right after
   * an in-app permission prompt returns: that prompt only pauses the host
   * Activity, so [permissionsState] would otherwise stay stale (showing the
   * banner for an already-granted permission) until the next foreground cycle.
   */
  public fun refreshPermissions(): Unit {
    permissionsMonitor.refresh()
  }

  /** Whether "stay discoverable in background" is enabled (persisted). The Android app observes
   *  this same pref to start/stop its discovery foreground service. */
  public val backgroundDiscoveryEnabled: StateFlow<Boolean> = localPropertiesRepository.properties
    .map { it.backgroundDiscoveryEnabled }
    .stateIn(controllerScope, SharingStarted.Eagerly, false)

  /** Background discoverability is backed by a foreground service — Android-only for now. The
   *  Settings UI hides the toggle on platforms where it has no effect. */
  public val supportsBackgroundDiscovery: Boolean =
    com.carlom.klardrop.common.CommonPlatformDependencies.osType() == com.carlom.klardrop.common.utils.OsType.ANDROID

  public fun setBackgroundDiscoveryEnabled(enabled: Boolean): Unit {
    controllerScope.launch { localPropertiesRepository.saveBackgroundDiscoveryEnabled(enabled) }
  }

  /** Same as [setBackgroundDiscoveryEnabled], but returns only once [backgroundDiscoveryEnabled]
   *  publishes [enabled], so a caller (the control plane's POST /settings) can rely on the next
   *  read seeing it. Bounded so a concurrent writer can't hang the caller. */
  public suspend fun setBackgroundDiscoveryEnabledAndWait(enabled: Boolean): Unit {
    localPropertiesRepository.saveBackgroundDiscoveryEnabled(enabled)
    withTimeoutOrNull(5.seconds) { backgroundDiscoveryEnabled.first { it == enabled } }
  }

  private val _outgoingTransfers = MutableSharedFlow<OutgoingTransfer>(extraBufferCapacity = 64)
  public val outgoingTransfers: SharedFlow<OutgoingTransfer> = _outgoingTransfers.asSharedFlow()

  // Live pairing requests keyed by deviceId so a notification action delivered
  // out-of-band (the user tapping Accept on a backgrounded notification) can
  // resolve the same callbacks the in-app dialog would have invoked.
  private data class PendingPairing(
    val deviceName: String,
    val onAccept: suspend () -> Unit,
    val onReject: suspend () -> Unit,
  )
  private val pendingPairings = mutableMapOf<String, PendingPairing>()

  /**
   * Pairing requests that arrived while another dialog was active, FIFO. Presented by
   * [presentNextQueuedPairing] when the active dialog resolves (accept/reject/dismiss).
   */
  private data class QueuedPairingRequest(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
  )
  private val queuedPairingRequests = ArrayDeque<QueuedPairingRequest>()

  public val screenStateFlow: MutableStateFlow<DiscoveryScreenState> = MutableStateFlow(DiscoveryScreenState())

  private var activeChatDeviceId: String? = null

  public fun setActiveChatDeviceId(deviceId: String?): Unit {
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
          state.copy(
            devices = it.toList().map { device ->
              device.copy(pairingError = pairingErrors[device.deviceId])
            }
          )
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

    // Register pairing completion callback. The coordinator fires its callbacks on its own
    // Dispatchers.Default scope, so every one of them hops onto controllerScope first —
    // [pairingErrors] is a plain map confined to the main dispatcher.
    pairingProtocolCoordinator.onPairingCompleted = { deviceId, deviceName, success ->
      controllerScope.launch {
        log("DiscoveryController", "Pairing completion callback: $deviceName ($deviceId), success: $success")
        if (success) {
          log("DiscoveryController", "Updating UI to show device $deviceName as Trusted")
          updateDeviceTrustStatus(deviceId, TrustStatus.Trusted)
          setPairingError(deviceId, null)
        } else {
          log("DiscoveryController", "Updating UI to show device $deviceName as Untrusted (pairing failed)")
          markUntrustedUnlessStored(deviceId)
        }
      }
    }

    // Surface pairing failures (send failure, ack/session timeout, peer rejection, response
    // delivery failure) as a per-device error message. The PairingFailed event is the single
    // source of truth for these — onAddToTrusted's own failure path only resets trust status.
    pairingProtocolCoordinator.onPairingFailed = { deviceId, reason ->
      controllerScope.launch {
        val deviceName = screenStateFlow.value.devices
          .firstOrNull { it.deviceId == deviceId }
          ?.deviceName
          ?: deviceId
        log("DiscoveryController", "Pairing failed for $deviceName ($deviceId): $reason")
        markUntrustedUnlessStored(deviceId)
        setPairingError(deviceId, pairingFailureText(deviceName, reason).withRestrictionPrefix())
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

  public suspend fun debugSendTextAndWait(deviceId: String, text: String): String {
    val finalStatus = messenger.send(deviceId, TextMessage(text = text).toSimpleSendRequest())
      .untilCompleted()
      .lastOrNull()
    return when (finalStatus) {
      is com.carlom.klardrop.common.communication.MessengerSendProgress.Completed -> "completed"
      is com.carlom.klardrop.common.communication.MessengerSendProgress.Error ->
        "error:${finalStatus.message}"
      else -> "unknown:$finalStatus"
    }
  }

  /**
   * Starts a tracked text send and returns without waiting for delivery, so a control-plane
   * caller can answer a submission immediately with a stable transfer id.
   *
   * [register] runs after the transfer is minted and BEFORE it is emitted on [outgoingTransfers]:
   * the flow has no replay, so anything that observes this transfer must already be able to
   * resolve it. [sendTextTracked] is the plain blocking variant.
   */
  public suspend fun startTrackedTextSend(
    deviceId: String,
    text: String,
    register: suspend (OutgoingTransfer) -> Unit = {},
  ): OutgoingTransfer {
    val message = TextMessage(text = text)
    val flow = messenger.send(deviceId, message.toSimpleSendRequest()).untilCompleted()
    val transfer = OutgoingTransfer(
      id = message.id.toString(),
      deviceId = deviceId,
      fileName = message.text,
      totalSize = 0L,
      flow = flow,
      kind = TRANSFER_KIND_TEXT,
    )
    register(transfer)
    _outgoingTransfers.tryEmit(transfer)
    coroutines.appScope.launch {
      showDevicesHelper.collectProgress(flow, deviceId)
    }
    return transfer
  }

  /** Blocking variant of [startTrackedTextSend]: the same tracked send, awaited to a terminal
   *  progress value, returning the minted message id. */
  public suspend fun sendTextTracked(deviceId: String, text: String): String {
    val transfer = startTrackedTextSend(deviceId, text)
    transfer.flow.lastOrNull()
    return transfer.id
  }

  public fun sendFiles(
    deviceId: String,
    files: List<PlatformFile>,
    onProgress: ((MessengerSendProgress) -> Unit)? = null,
  ): Unit {
    coroutines.appScope.launch {
      startPreparedFiles(deviceId, prepareOutgoingFiles(files), onProgress)
    }
  }

  /**
   * Resolves every submitted file and mints its [FileMessage], reporting a human-readable
   * [PreparedFile.error] for each path that cannot be resolved instead of dropping it.
   * Returns one entry per submitted file, in order. Never throws.
   */
  public fun prepareOutgoingFiles(files: List<PlatformFile>): List<PreparedFile> =
    files.map { file ->
      val resolved = runCatching { platformFileSystem.getResolvedFileData(file) }
      resolved.exceptionOrNull()?.let {
        log(
          "DiscoveryController",
          "Unable to resolve file at path ${file.path}. File cannot be sent!",
          it,
        )
      }
      val fileData = resolved.getOrNull()
      if (fileData == null) {
        val cause = resolved.exceptionOrNull()
        PreparedFile(
          file = file,
          path = file.path,
          fileName = null,
          message = null,
          error = "cannot resolve file at ${file.path}: " +
            (cause?.message ?: cause?.let { it::class.simpleName } ?: "file is not readable"),
        )
      } else {
        PreparedFile(
          file = file,
          path = file.path,
          fileName = fileData.fileName,
          message = FileMessage(
            fileData.fileName,
            fileData.fileSize,
            fileData.mimeType
          ),
          error = null,
        )
      }
    }

  /**
   * Sends already-prepared files one after another on the calling coroutine (use it from
   * `coroutines.appScope.launch` for the historical fire-and-forget behavior). Entries whose
   * [PreparedFile.error] is set are skipped — the caller that prepared them reports them.
   */
  public suspend fun startPreparedFiles(
    deviceId: String,
    prepared: List<PreparedFile>,
    onProgress: ((MessengerSendProgress) -> Unit)? = null,
  ): Unit {
    prepared.forEach { entry ->
      val fileMessage = entry.message ?: return@forEach
      val file = entry.file

      val flow = messenger.send(
        deviceId, fileMessage.toSendRequest(file)
      ).untilCompleted()

      _outgoingTransfers.tryEmit(
        OutgoingTransfer(
          id = fileMessage.id.toString(),
          deviceId = deviceId,
          fileName = entry.fileName.orEmpty(),
          totalSize = fileMessage.fileSize,
          flow = flow,
        )
      )

      if (onProgress != null) {
        coroutines.appScope.launch {
          flow.collect { progress ->
            onProgress(progress)
          }
        }
      }
      showDevicesHelper.collectProgress(flow, deviceId)
    }
  }

  public override fun onDeviceClick(deviceUi: DeviceUi): Unit {
    log("DiscoveryController", "on device click for chat: ${deviceUi.deviceName}")
    markDeviceRead(deviceUi.deviceId)
  }

  /** Marks [deviceId]'s messages read on disk and clears its unread flag in [screenStateFlow].
   *  The DB write is fire-and-forget: the UI flag flips immediately, matching how the Compose
   *  chat screen has always behaved on a device click. Callers that need the DB write to have
   *  landed before they return (e.g. the debug HTTP API) should use [markDeviceReadAndWait]. */
  public fun markDeviceRead(deviceId: String): Unit {
    controllerScope.launch {
      messageRepository.markMessagesAsRead(deviceId)
    }
    clearUnreadFlag(deviceId)
  }

  /** Same effect as [markDeviceRead], but awaits the DB write before clearing the unread flag,
   *  so a caller that awaits this suspend fun can rely on the DB being marked read once it
   *  returns. */
  public suspend fun markDeviceReadAndWait(deviceId: String): Unit {
    messageRepository.markMessagesAsRead(deviceId)
    clearUnreadFlag(deviceId)
  }

  private fun clearUnreadFlag(deviceId: String) {
    screenStateFlow.update { currentState ->
      val updatedDevices = currentState.devices.map {
        if (it.deviceId == deviceId) {
          it.copy(hasUnreadMessages = false)
        } else it
      }
      currentState.copy(devices = updatedDevices)
    }
  }

  // This method might still be used if sending is initiated from somewhere else,
  // or could be refactored if all sending now happens via chat.
  // For now, keeping it as is.
  public override fun onSendData(deviceUi: DeviceUi, onDataToSend: OnDataToSend): Unit {

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

  public fun dispose(): Unit {
    // TODO implement dispose logic
    controllerScope.cancel()
  }

  public fun readFromClipboard(): String {
    return clipboardManager.read()
  }

  public override fun onReceivedCardClicked(receiveUpdate: ReceiveMessageUpdate): Unit {

    if (receiveUpdate.status is ReceiveMessageStatus.Completed) {

      val firstMessage = receiveUpdate.messages.first()

      if (firstMessage is TextMessage) {
        val text = firstMessage.text
        clipboardManager.write(text)
      }

    }

  }

  public override fun onCardDismissed(id: Int): Unit {
    screenStateFlow.update {
      if (id !in it.receivingMessages) it
      else it.copy(receivingMessages = it.receivingMessages - id)
    }
  }

  public override fun onConnectionInfoAccepted(message: ConnectionInfoMessage): Unit {
    coroutines.appScope.launch {
      val joined = connectionInfoJoiner.tryJoin(message)
      log(
        "DiscoveryController",
        if (joined) "Requested OS join for Wi-Fi '${message.ssid}'"
        else "Fallback: copied password for '${message.ssid}' to clipboard"
      )
    }
  }

  public override fun onAddToTrusted(deviceUi: DeviceUi): Unit {
    log("DiscoveryController", "onAddToTrusted() called for device: ${deviceUi.deviceName} (${deviceUi.deviceId})")
    log("DiscoveryController", "Adding device ${deviceUi.deviceName} to trusted")

    // Next interaction clears the previous attempt's error.
    setPairingError(deviceUi.deviceId, null)

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
          // Reset to untrusted state on failure. The error text itself is surfaced via the
          // PairingFailed event (see onPairingFailed above) — single source of truth.
          updateDeviceTrustStatus(deviceUi.deviceId, TrustStatus.Untrusted)
        }
      )
    }
  }

  public override fun onForgetDevice(deviceUi: DeviceUi): Unit {
    log("DiscoveryController", "Forgetting trusted device ${deviceUi.deviceName}")

    coroutines.appScope.launch {
      // Coordinator sends a signed revocation (best-effort) BEFORE wiping the local
      // trust entry, then removes locally regardless of send outcome.
      pairingProtocolCoordinator.unpair(deviceUi.deviceId, reason = "user_unpaired")
      updateDeviceTrustStatus(deviceUi.deviceId, TrustStatus.Untrusted)
    }
  }

  public override fun onNotificationDismissed(notificationId: Int): Unit {
    screenStateFlow.update { state ->
      state.copy(notifications = state.notifications.filterNot { it.id == notificationId })
    }
  }

  public override fun onNotificationPair(notificationId: Int): Unit {
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

  /**
   * Show the device as untrusted — unless the trust store says we ARE paired with it.
   *
   * Not every pairing failure means "we are not paired with this peer": the acceptor emits
   * response-delivery-failed AFTER trust is persisted, the receiver emits clock-skew for a
   * peer that may already be trusted, and a peer's rejection of a re-pair does not erase the
   * key we still hold. Downgrading the row on those contradicts the store, so the store —
   * not the event — decides the status. The failure text is surfaced either way.
   */
  private fun markUntrustedUnlessStored(deviceId: String) {
    if (trustedDevicesDirectory.trustedDevices.value.containsKey(deviceId)) return
    updateDeviceTrustStatus(deviceId, TrustStatus.Untrusted)
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

  private fun setPairingError(deviceId: String, message: String?) {
    if (message == null) pairingErrors.remove(deviceId) else pairingErrors[deviceId] = message
    screenStateFlow.update { currentState ->
      currentState.copy(
        devices = currentState.devices.map { device ->
          if (device.deviceId == deviceId) device.copy(pairingError = message) else device
        }
      )
    }
  }

  /**
   * Human-readable phrasing for a [PairingEvent.PairingFailed] reason. The raw reason strings
   * ("connect-failed(IOException)", …) are for logs and tests; this is what the user reads.
   */
  private fun pairingFailureText(deviceName: String, reason: String): String = when {
    reason == "no-endpoints" || reason.startsWith("connect-failed") ->
      "Could not reach $deviceName — the device may be offline or a firewall blocks direct connections"
    reason == "ack-timeout" ->
      "$deviceName did not respond in time — check that both devices are on the same network"
    reason == "session-timeout" ->
      "Pairing with $deviceName timed out — try again"
    reason == "rejected-by-peer" ->
      "$deviceName declined the pairing request"
    else -> "Could not pair with $deviceName"
  }

  /**
   * T11: when this device's OS is actively blocking connections (battery saver /
   * metered deny), prefix the failure reason so the user looks HERE first instead
   * of suspecting the peer.
   */
  private fun String.withRestrictionPrefix(): String {
    val notice = connectivityRestrictions.value.activeBlockerNotice() ?: return this
    return "$notice — $this"
  }

  // Implementation of PairingApprovalCallback
  public override fun onPairingRequested(
    deviceId: String,
    deviceName: String,
    deviceType: String,
    onAccept: suspend () -> Unit,
    onReject: suspend () -> Unit
  ): Unit {
    log("DiscoveryController", "onPairingRequested for device: $deviceName ($deviceId)")
    pendingPairings[deviceId] = PendingPairing(deviceName, onAccept, onReject)

    controllerScope.launch {
      val active = screenStateFlow.value.pairingDialogState
      when {
        // Duplicate/replayed request for a device we are already showing or already
        // queued: one dialog per device.
        active?.deviceId == deviceId || queuedPairingRequests.any { it.deviceId == deviceId } ->
          log("DiscoveryController", "Duplicate pairing request for $deviceId (already showing or queued); ignoring")

        active != null -> {
          log("DiscoveryController", "A pairing dialog is already active; queueing request for $deviceName ($deviceId)")
          queuedPairingRequests.addLast(QueuedPairingRequest(deviceId, deviceName, deviceType))
        }

        else -> presentPairingDialog(deviceId, deviceName, deviceType)
      }
    }
  }

  private fun presentPairingDialog(deviceId: String, deviceName: String, deviceType: String) {
    log("DiscoveryController", "Creating PairingDialogState for $deviceName")
    screenStateFlow.update { currentState ->
      if (currentState.pairingDialogState != null) return@update currentState
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

  /**
   * Present the next queued pairing request, if the dialog slot is free. Requests resolved
   * out-of-band while queued (e.g. a notification action) are skipped.
   */
  private fun presentNextQueuedPairing() {
    while (screenStateFlow.value.pairingDialogState == null && queuedPairingRequests.isNotEmpty()) {
      val next = queuedPairingRequests.removeFirst()
      if (pendingPairings.containsKey(next.deviceId)) {
        presentPairingDialog(next.deviceId, next.deviceName, next.deviceType)
      } else {
        log("DiscoveryController", "Skipping queued pairing request for ${next.deviceId}: already resolved")
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
      presentNextQueuedPairing()
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
      presentNextQueuedPairing()
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

  public fun dismissPairingDialog(): Unit {
    screenStateFlow.update { it.copy(pairingDialogState = null) }
    presentNextQueuedPairing()
  }

  public fun loadDeviceNames(): Unit {
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

  public fun saveCustomDeviceName(customName: String?): Unit {
    controllerScope.launch {
      saveCustomDeviceNameAndWait(customName)
    }
  }

  /** Same as [saveCustomDeviceName], but returns only once the new name is persisted, so a caller
   *  (the control plane's /rename-device and POST /settings) can rely on the next read seeing it. */
  public suspend fun saveCustomDeviceNameAndWait(customName: String?): Unit {
    currentDeviceProvider.updateCustomDeviceName(customName)
    loadDeviceNames()
  }

  // --- Debug-control entry points. Same methods the UI buttons call, keyed by device id. ---

  public fun debugPair(deviceId: String): Unit {
    onAddToTrusted(requireDevice(deviceId))
  }

  public suspend fun debugUnpairAndWait(deviceId: String): Unit {
    log("DiscoveryController", "debugUnpairAndWait($deviceId)")
    pairingProtocolCoordinator.unpair(deviceId, reason = "user_unpaired")
    updateDeviceTrustStatus(deviceId, TrustStatus.Untrusted)
  }

  public fun debugAcceptPairing(deviceId: String): Unit {
    controllerScope.launch { acceptPairing(deviceId) }
  }

  public fun debugRejectPairing(deviceId: String): Unit {
    controllerScope.launch { rejectPairing(deviceId) }
  }

  public fun debugSendFile(deviceId: String, path: String): Unit {
    sendFiles(deviceId, listOf(platformFileFromPath(path)))
  }

  public fun debugAcceptIncoming(receiveId: Int): Unit {
    decideIncoming(receiveId, accept = true)
  }

  public fun debugRejectIncoming(receiveId: Int): Unit {
    decideIncoming(receiveId, accept = false)
  }

  public fun debugAcceptIncomingFrom(deviceId: String): Unit {
    val match = screenStateFlow.value.receivingMessages.entries.firstOrNull { (_, update) ->
      update.status is ReceiveMessageStatus.PendingAuthorization &&
        (update.device?.deviceId == deviceId || update.device?.deviceId?.startsWith(deviceId) == true)
    } ?: error("No pending incoming transfer from $deviceId")
    decideIncoming(match.key, accept = true)
  }

  private fun decideIncoming(receiveId: Int, accept: Boolean) {
    val update = screenStateFlow.value.receivingMessages[receiveId]
      ?: error("No incoming transfer with receiveId=$receiveId")
    val status = update.status as? ReceiveMessageStatus.PendingAuthorization
      ?: error("Incoming $receiveId is not awaiting authorization (status=${update.status})")
    status.acceptTransfer(accept)
  }

  private fun findDevice(deviceId: String): DeviceUi? {
    val devices = screenStateFlow.value.devices
    return devices.firstOrNull { it.deviceId == deviceId }
      ?: devices.firstOrNull { it.deviceId.startsWith(deviceId) }
      ?: devices.firstOrNull { deviceId.startsWith(it.deviceId) }
  }

  private fun requireDevice(deviceId: String): DeviceUi {
    return findDevice(deviceId)
      ?: error(
        "No device matching '$deviceId' in the list " +
          "(${screenStateFlow.value.devices.map { it.deviceId }})"
      )
  }
}

/** [OutgoingTransfer.kind] of a file send — the historical default. */
public const val TRANSFER_KIND_FILE: String = "file"

/** [OutgoingTransfer.kind] of a text or clipboard send. */
public const val TRANSFER_KIND_TEXT: String = "text"

/**
 * One observed outgoing transfer. [kind] is [TRANSFER_KIND_FILE] for a file send and
 * [TRANSFER_KIND_TEXT] for a text/clipboard send; it defaults to the file case so every
 * existing construction site keeps its exact previous meaning.
 */
public data class OutgoingTransfer(
  public val id: String,
  public val deviceId: String,
  public val fileName: String,
  public val totalSize: Long,
  public val flow: Flow<MessengerSendProgress>,
  public val kind: String = TRANSFER_KIND_FILE,
)

/**
 * A submitted file resolved for sending: [message] is the minted [FileMessage], or null with a
 * human-readable [error] when the path could not be resolved. Nothing is ever dropped silently.
 */
public data class PreparedFile(
  public val file: PlatformFile,
  public val path: String,
  public val fileName: String?,
  public val message: FileMessage?,
  public val error: String?,
)

public data class DiscoveryScreenState(
  public val devices: List<DeviceUi> = emptyList(),
  public val receivingMessages: Map<Int, ReceiveMessageUpdate> = emptyMap(),
  public val pairingDialogState: PairingDialogState? = null,
  public val currentDeviceName: String? = null,
  public val systemDeviceName: String? = null,
  /**
   * In-app notifications surfaced in the top banner stack. Distinct from
   * [receivingMessages] (which is purely about inbound transfers); this is the
   * generic surface for system-level events the user should see — currently
   * just peer-revoked-trust, but the seam exists for future ones.
   */
  public val notifications: List<UiNotification> = emptyList(),
)

public sealed interface UiNotification {
  public val id: Int

  /**
   * A previously-paired peer revoked us. Local trust has already been removed by the
   * time this is surfaced. The banner offers Dismiss (clear it) and Pair (re-pair).
   */
  public data class PeerRevokedTrust(
    public override val id: Int,
    public val deviceId: String,
    public val deviceName: String,
  ) : UiNotification
}

public data class PairingDialogState(
  public val deviceId: String,
  public val deviceName: String,
  public val deviceType: String,
  public val onAccept: () -> Unit,
  public val onReject: () -> Unit,
  public val isError: Boolean = false,
  public val errorMessage: String? = null,
)

/**
 * A transport a device is currently announcing.
 *
 * Held as a record instead of a bare [DeviceConnection.DeviceConnectionType]
 * because swift-export force-casts `List<Enum>` to a Swift array of that enum,
 * and the Obj-C enum objects in the array do not cast (same failure as
 * KLARDROP-KJ). [type] is read as a single enum property, which does bridge.
 */
public data class DeviceConnectionKind(
  public val type: DeviceConnection.DeviceConnectionType,
)

public data class DeviceUi(
  public val deviceId: String,
  public val deviceName: String,
  public val deviceType: DeviceType,
  public val activityState: ActivityState = ActivityState.Idle,
  public val connectionTypes: List<DeviceConnectionKind>,
  public val hasUnreadMessages: Boolean = false,
  public val trustStatus: TrustStatus = TrustStatus.Unknown,
  public val reachability: Reachability = Reachability.Unknown,
  /**
   * Transient pairing-failure message for this device, rendered as red helper text under the
   * row. Single source of truth lives in [DiscoveryController]'s error map (which survives
   * discovery-tick rebuilds of this model); cleared on the next pairing interaction.
   */
  public val pairingError: String? = null,
)

public sealed interface TrustStatus {
  public object Unknown : TrustStatus
  public object Untrusted : TrustStatus  
  public object Trusted : TrustStatus
  public object Pairing : TrustStatus
}

public sealed interface ActivityState {

  public data object Idle : ActivityState

  public data class SentCompleted(public val error: Boolean = false) : ActivityState
//  data class ReceiveCompleted(val error: Boolean = false) : ActivityState

  public data class Sending(public val progressPercentage: Int) : ActivityState

//  data class Receiving(val progressPercentage : Int) : ActivityState

}
