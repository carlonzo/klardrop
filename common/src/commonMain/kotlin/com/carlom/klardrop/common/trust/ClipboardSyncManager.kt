package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.message.ClipboardSyncMessage
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/**
 * Manages clipboard synchronization across trusted devices.
 * Monitors local clipboard changes and broadcasts them to trusted devices.
 * Also handles incoming clipboard sync messages from trusted devices.
 */
class ClipboardSyncManager(
  private val clipboardManager: ClipboardManager,
  private val visibleDevices: VisibleDevices,
  private val trustManager: TrustManager,
  private val clock: Clock,
  private val coroutines: Coroutines,
  private val messenger: Messenger
) {

  private val syncScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
  private var isEnabled = true

  /**
   * Start monitoring clipboard changes and sync to trusted devices.
   */
  @OptIn(FlowPreview::class)
  fun startClipboardMonitoring() {
    if (!isEnabled) return

    log("ClipboardSyncManager", "Starting clipboard monitoring")

    clipboardManager.flow
      .filter { content ->
        // Filter out empty content and content we just set
        content.isNotBlank() && content.length <= MAX_CONTENT_LENGTH
      }
      .distinctUntilChanged()
      .debounce(SYNC_DEBOUNCE) // Prevent rapid changes
      .onEach { handleLocalClipboardChange(it) }
      .launchIn(syncScope)

  }

  /**
   * Handle local clipboard changes and broadcast to trusted devices.
   */
  @OptIn(ExperimentalTime::class)
  private suspend fun handleLocalClipboardChange(content: String) {

    log("ClipboardSyncManager", "Local clipboard changed, syncing to trusted devices.")

    val trustedVisibleDevices = visibleDevices.visibleDevices.value.values
      .filter { trustManager.isTrusted(it.deviceInfo.deviceId) }

    // Get all trusted devices
    if (trustedVisibleDevices.isEmpty()) {
      log("ClipboardSyncManager", "No trusted devices found for clipboard sync")
      return
    }

    // Create clipboard sync message
    val clipboardMessage = ClipboardSyncMessage(
      content = content,
      mimeType = "text/plain",
      timestamp = clock.currentTimeMillis(),
      signature = ByteArray(0) // Will be signed by TrustManager when sent
    )

    val simpleSendMessageRequest = clipboardMessage.toSimpleSendRequest()

    // Send to all trusted devices
    trustedVisibleDevices.forEach { trustedDevice ->

      try {
        log("ClipboardSyncManager", "Sending clipboard sync to $trustedDevice")

        sendClipboardSyncMessage(simpleSendMessageRequest, trustedDevice.deviceInfo.deviceId)
      } catch (e: Exception) {
        log("ClipboardSyncManager", "Failed to sync clipboard to ${trustedDevice}: ${e.message}")
      }
    }
  }

  private fun sendClipboardSyncMessage(
    message: SendMessageRequest,
    deviceId: String
  ) {
    syncScope.launch {
      try {
        log("ClipboardSyncManager", "Sending clipboard sync to $deviceId")
        messenger.send(deviceId, message)
          .untilCompleted()
          .firstOrNull()
      } catch (e: Exception) {
        log("ClipboardSyncManager", "Failed to send clipboard sync to $deviceId: ${e.message}")
      }
    }
  }

  /**
   * Handle incoming clipboard sync message from a trusted device.
   * This should be called by a message handler when a ClipboardSyncMessage is received.
   */
  suspend fun handleIncomingClipboardSync(message: ClipboardSyncMessage, senderId: String) {
    if (!isEnabled) {
      log("ClipboardSyncManager", "Clipboard sync is disabled, ignoring message from $senderId")
      return
    }

    // Verify sender is trusted
    if (!trustManager.isTrusted(senderId)) {
      log("ClipboardSyncManager", "Ignoring clipboard sync from untrusted device: $senderId")
      return
    }

    // Validate message content
    if (message.content.isBlank() || message.content.length > MAX_CONTENT_LENGTH) {
      log("ClipboardSyncManager", "Invalid clipboard content from $senderId")
      return
    }

    // Check if content is different from current clipboard
    val currentContent = clipboardManager.read()
    if (message.content == currentContent) {
      log("ClipboardSyncManager", "Clipboard content unchanged, skipping sync")
      return
    }

    // Prevent sync loops by temporarily disabling local monitoring
    val wasEnabled = isEnabled
    isEnabled = false

    try {
      log("ClipboardSyncManager", "Updating clipboard from trusted device $senderId: ${message.content.take(50)}...")

      // Update local clipboard
      clipboardManager.write(message.content)

      log("ClipboardSyncManager", "Clipboard synced successfully from $senderId")

    } catch (e: Exception) {
      log("ClipboardSyncManager", "Failed to update clipboard from $senderId: ${e.message}")
    } finally {
      // Re-enable local monitoring after a short delay to prevent immediate re-sync
      coroutines.appScope.launch {
        kotlinx.coroutines.delay(2000) // 2 second delay
        isEnabled = wasEnabled
      }
    }
  }

  /**
   * Enable or disable clipboard synchronization.
   */
  fun setClipboardSyncEnabled(enabled: Boolean) {
    log("ClipboardSyncManager", "Clipboard sync ${if (enabled) "enabled" else "disabled"}")
    isEnabled = enabled

    if (enabled) {
      startClipboardMonitoring()
    }
  }

  /**
   * Check if clipboard sync is currently enabled.
   */
  fun isClipboardSyncEnabled(): Boolean = isEnabled

  /**
   * Stop clipboard monitoring and cleanup resources.
   */
  fun stop() {
    log("ClipboardSyncManager", "Stopping clipboard sync manager")
    isEnabled = false
    // Scope will be cancelled when parent scope is cancelled
  }

  companion object {
    private const val MAX_CONTENT_LENGTH = 10000 // Limit clipboard content size
    private val SYNC_DEBOUNCE = 500.milliseconds
  }
}