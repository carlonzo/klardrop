package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.message.ClipboardSyncMessage
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Manages clipboard synchronization across trusted devices.
 * Monitors local clipboard changes and broadcasts them to trusted devices.
 * Also handles incoming clipboard sync messages from trusted devices.
 */
class ClipboardSyncManager(
    private val clipboardManager: ClipboardManager,
    private val trustManager: TrustManager,
    private val clock: Clock,
    private val coroutines: Coroutines
) {
    
    // Messenger will be set after dependency injection is complete
    private var messenger: Messenger? = null
    
    companion object {
        private const val MIN_CONTENT_LENGTH = 1
        private const val MAX_CONTENT_LENGTH = 10000 // Limit clipboard content size
        private const val SYNC_DEBOUNCE_MS = 1000L // Prevent rapid-fire updates
    }
    
    private val syncScope = coroutines.newScope(coroutines.ioDispatcher)
    private var isEnabled = true
    private var lastSyncTime = 0L
    private var lastSyncContent = ""
    
    /**
     * Set the messenger instance after dependency injection is complete.
     */
    fun setMessenger(messenger: Messenger) {
        this.messenger = messenger
        log("ClipboardSyncManager", "Messenger set, clipboard sync is ready")
    }
    
    /**
     * Start monitoring clipboard changes and sync to trusted devices.
     */
    fun startClipboardMonitoring() {
        if (!isEnabled) return
        
        log("ClipboardSyncManager", "Starting clipboard monitoring")
        
        syncScope.launch {
            clipboardManager.flow
                .distinctUntilChanged()
                .filter { content -> 
                    // Filter out empty content and content we just set
                    content.isNotBlank() && 
                    content.length in MIN_CONTENT_LENGTH..MAX_CONTENT_LENGTH &&
                    content != lastSyncContent
                }
                .collect { content ->
                    handleLocalClipboardChange(content)
                }
        }
    }
    
    /**
     * Handle local clipboard changes and broadcast to trusted devices.
     */
    private suspend fun handleLocalClipboardChange(content: String) {
        val currentTime = clock.currentTimeMillis()
        
        // Debounce rapid changes
        if (currentTime - lastSyncTime < SYNC_DEBOUNCE_MS) {
            log("ClipboardSyncManager", "Debouncing clipboard change")
            return
        }
        
        lastSyncTime = currentTime
        lastSyncContent = content
        
        log("ClipboardSyncManager", "Local clipboard changed, syncing to trusted devices: ${content.take(50)}...")
        
        // Get all trusted devices
        val trustedDevices = trustManager.getTrustedDevices()
        if (trustedDevices.isEmpty()) {
            log("ClipboardSyncManager", "No trusted devices found for clipboard sync")
            return
        }
        
        // Create clipboard sync message
        val clipboardMessage = ClipboardSyncMessage(
            content = content,
            mimeType = "text/plain",
            timestamp = currentTime,
            signature = ByteArray(0) // Will be signed by TrustManager when sent
        )
        
        // Send to all trusted devices
        val currentMessenger = messenger
        if (currentMessenger == null) {
            log("ClipboardSyncManager", "Messenger not set, cannot sync clipboard")
            return
        }
        
        trustedDevices.forEach { trustedDevice ->
            try {
                log("ClipboardSyncManager", "Sending clipboard sync to ${trustedDevice.deviceId}")
                currentMessenger.send(trustedDevice.deviceId, clipboardMessage.toSimpleSendRequest())
            } catch (e: Exception) {
                log("ClipboardSyncManager", "Failed to sync clipboard to ${trustedDevice.deviceId}: ${e.message}")
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
            lastSyncContent = message.content
            lastSyncTime = clock.currentTimeMillis()
            
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
}