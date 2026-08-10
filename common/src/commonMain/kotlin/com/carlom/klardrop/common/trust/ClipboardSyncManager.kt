package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.message.ClipboardSyncMessage
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.communication.untilCompleted
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.features.ClipboardAccess
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/**
 * Manages clipboard synchronization across trusted devices.
 * Monitors local clipboard changes and broadcasts them to trusted devices.
 * Also handles incoming clipboard sync messages from trusted devices.
 *
 * TRUST CONTRACT — the clipboard moves silently, with no user prompt on either end, so
 * pairing is the *only* thing that authorizes it. Both directions are therefore gated on
 * [TrustManager.isTrusted], and both gates fail CLOSED: a trust lookup that throws is
 * treated as "not trusted" rather than letting the content through. Untrusted devices are
 * never sent our clipboard, and their clipboard is never written into ours — a peer we
 * merely discovered on the LAN, or one we accepted a single file from, gets nothing.
 * [com.carlom.klardrop.common.communication.router.MessagesRouter] enforces the same rule
 * one layer down so an unpaired peer's CLIPBOARD_SYNC frame never even reaches this class.
 */
class ClipboardSyncManager(
  private val clipboardManager: ClipboardAccess,
  private val visibleDevices: VisibleDevices,
  private val trustManager: TrustManager,
  private val clock: Clock,
  private val coroutines: Coroutines,
  private val messenger: Lazy<Messenger>
) {

  private val syncScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)

  // Read from the clipboard poller's coroutine and written from the receive path, which
  // run on different threads on JVM/Native — volatile so the echo-suppression window below
  // is actually observed by the monitoring collector instead of a stale cached copy.
  @Volatile
  private var isEnabled = true

  /**
   * The single collector of [ClipboardAccess.flow]. Held so [setClipboardSyncEnabled] can
   * re-enable without stacking a second collector on top of the first — every duplicate
   * would broadcast the same clipboard change again.
   */
  private var monitoringJob: Job? = null

  /**
   * Start monitoring clipboard changes and sync to trusted devices.
   */
  @OptIn(FlowPreview::class)
  fun startClipboardMonitoring() {
    if (!isEnabled) return
    if (monitoringJob?.isActive == true) {
      log("ClipboardSyncManager", "Clipboard monitoring already running")
      return
    }

    log("ClipboardSyncManager", "Starting clipboard monitoring")

    monitoringJob = clipboardManager.flow
      .filter { content ->
        // Filter out empty content and content we just set.
        //
        // The isEnabled check is what makes the echo-suppression window in
        // [handleIncomingClipboardSync] work: without it, clipboard content we just
        // *received* is picked up by the poller and immediately broadcast back out.
        isEnabled && content.isNotBlank() && content.length <= MAX_CONTENT_LENGTH
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

    if (!isEnabled) return

    val trustedVisibleDevices = visibleDevices.visibleDevices.value.values
      .filter { isTrusted(it.deviceInfo.deviceId) }

    // Only paired devices ever see our clipboard. With none paired this is a no-op —
    // discovering a device on the network is not consent to receive what we copy.
    if (trustedVisibleDevices.isEmpty()) {
      log("ClipboardSyncManager", "No trusted devices found for clipboard sync")
      return
    }

    log("ClipboardSyncManager", "Local clipboard changed, syncing to ${trustedVisibleDevices.size} trusted device(s)")

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
        // Re-check at send time: the broadcast above snapshots the visible devices, and
        // an unpair can land between that snapshot and this send.
        if (!isTrusted(deviceId)) {
          log("ClipboardSyncManager", "Skipping clipboard sync to $deviceId - no longer trusted")
          return@launch
        }
        log("ClipboardSyncManager", "Sending clipboard sync to $deviceId")
        messenger.value.send(deviceId, message)
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

    // Verify sender is paired. Writing the clipboard is silent and immediate, so an
    // unpaired sender — including one whose id we couldn't resolve — gets dropped here.
    if (senderId.isBlank() || !isTrusted(senderId)) {
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
        kotlinx.coroutines.delay(ECHO_SUPPRESSION_WINDOW)
        isEnabled = wasEnabled
      }
    }
  }

  /**
   * Trust lookup that fails closed: any error reading trust storage (locked keystore,
   * corrupt file) means we do NOT treat the peer as paired, so clipboard content stays put.
   */
  private suspend fun isTrusted(deviceId: String): Boolean = try {
    trustManager.isTrusted(deviceId)
  } catch (e: Exception) {
    log("ClipboardSyncManager", "Trust lookup failed for $deviceId; treating as untrusted: ${e.message}")
    false
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
    monitoringJob?.cancel()
    monitoringJob = null
    // Scope will be cancelled when parent scope is cancelled
  }

  companion object {
    private const val MAX_CONTENT_LENGTH = 10000 // Limit clipboard content size
    private val SYNC_DEBOUNCE = 500.milliseconds

    /**
     * How long local monitoring stays muted after a received clipboard lands. Long enough
     * for the 500ms poller + [SYNC_DEBOUNCE] to see the new content and discard it, so the
     * value we just received isn't echoed straight back to the sender.
     */
    private val ECHO_SUPPRESSION_WINDOW = 2000.milliseconds
  }
}
