package com.carlom.klardrop.common.features

import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Manages clipboard synchronization with size limits
 */
class ClipboardSyncManager(
    private val clipboardManager: ClipboardManager,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ClipboardSyncManager"
        private const val MIN_SYNC_INTERVAL_MS = 30_000L // 30 seconds minimum
        private const val DEBOUNCE_DELAY_MS = 1000L // 1 second debounce
        private const val MAX_AUTO_SYNC_SIZE_CHARS = 1000 // 1000 chars limit for automatic sync
    }
    
    private var clipboardMonitorJob: Job? = null
    private var lastSyncedContent: String? = null
    
    /**
     * Check if clipboard sync is enabled
     */
    val syncEnabled: Boolean
        get() = clipboardMonitorJob != null
    
    /**
     * Enable or disable clipboard sync
     */
    fun setSyncEnabled(enabled: Boolean) {
        if (enabled) {
            startClipboardMonitoring()
        } else {
            stopClipboardMonitoring()
        }
    }
    
    /**
     * Check if clipboard sync is available
     */
    fun isClipboardSyncAvailable(): Boolean {
        // For now, always return true. In the future, this would check for trusted devices
        return true
    }
    
    /**
     * Start monitoring local clipboard for changes
     */
    private fun startClipboardMonitoring() {
        clipboardMonitorJob?.cancel()
        
        clipboardMonitorJob = scope.launch {
            // Monitor clipboard changes with debounce
            clipboardManager.flow
                .debounce(DEBOUNCE_DELAY_MS)
                .collect { content ->
                    handleLocalClipboardChange(content)
                }
        }
        
        log(TAG, "Started clipboard monitoring")
    }
    
    /**
     * Stop monitoring clipboard
     */
    private fun stopClipboardMonitoring() {
        clipboardMonitorJob?.cancel()
        clipboardMonitorJob = null
        log(TAG, "Stopped clipboard monitoring")
    }
    
    /**
     * Handle local clipboard change
     */
    private suspend fun handleLocalClipboardChange(content: String) {
        // Check if content is different from last synced
        if (content == lastSyncedContent) {
            return
        }
        
        // Check payload size - only auto-sync if under 1000 chars
        if (content.length > MAX_AUTO_SYNC_SIZE_CHARS) {
            log(TAG, "Clipboard content too large for auto-sync (${content.length} chars > $MAX_AUTO_SYNC_SIZE_CHARS chars), skipping")
            return
        }
        
        try {
            // Sync to devices (placeholder for now)
            log(TAG, "Syncing clipboard content (${content.length} chars)")
            syncClipboardToDevices(content)
            
            // Update last synced content
            lastSyncedContent = content
            
        } catch (e: Exception) {
            log(TAG, "Failed to sync clipboard", e)
        }
    }
    
    /**
     * Manually sync current clipboard content
     */
    suspend fun syncNow() {
        if (!syncEnabled) {
            log(TAG, "Clipboard sync is disabled")
            return
        }
        
        val content = clipboardManager.read()
        if (content.isNotEmpty()) {
            if (content.length > MAX_AUTO_SYNC_SIZE_CHARS) {
                log(TAG, "Warning: Manual sync of large clipboard content (${content.length} chars > $MAX_AUTO_SYNC_SIZE_CHARS chars)")
                // For manual sync, we allow larger content but with a warning
                manualSyncContent(content)
            } else {
                handleLocalClipboardChange(content)
            }
        }
    }
    
    /**
     * Manually sync content without size restrictions (for user-initiated sync)
     */
    private suspend fun manualSyncContent(content: String) {
        try {
            // Sync to devices
            log(TAG, "Manually syncing clipboard content (${content.length} chars)")
            syncClipboardToDevices(content)
            
            // Update last synced content
            lastSyncedContent = content
            
        } catch (e: Exception) {
            log(TAG, "Failed to manually sync clipboard", e)
        }
    }
    
    /**
     * Sync clipboard content to other devices
     * This is a placeholder implementation that will be enhanced when trust system is integrated
     */
    private suspend fun syncClipboardToDevices(content: String) {
        // TODO: Implement actual sync to trusted devices
        // For now, just log that we would sync
        log(TAG, "Would sync clipboard content to trusted devices: ${content.take(50)}${if (content.length > 50) "..." else ""}")
    }
    
    /**
     * Handle clipboard update from remote device
     */
    suspend fun handleRemoteClipboardUpdate(content: String, fromDevice: String) {
        if (!syncEnabled) {
            log(TAG, "Clipboard sync disabled, ignoring update from $fromDevice")
            return
        }
        
        // Update local clipboard
        try {
            clipboardManager.write(content)
            lastSyncedContent = content
            log(TAG, "Updated clipboard from device: $fromDevice")
            
        } catch (e: Exception) {
            log(TAG, "Failed to update clipboard from remote", e)
        }
    }
}

/**
 * Data class for clipboard sync preferences
 */
data class ClipboardSyncPreferences(
    val enabled: Boolean = false,
    val syncInterval: Long = 30_000L, // 30 seconds
    val showNotifications: Boolean = true,
    val excludedApps: Set<String> = emptySet()
)