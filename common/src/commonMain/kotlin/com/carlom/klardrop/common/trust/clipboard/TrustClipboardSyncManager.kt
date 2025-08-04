package com.carlom.klardrop.common.trust.clipboard

import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.trust.model.ClipboardEntry
import com.carlom.klardrop.common.trust.protocol.TrustEvent
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.protos.trust.Permission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Manages clipboard synchronization between trusted devices
 */
class TrustClipboardSyncManager(
    private val clipboardManager: ClipboardManager,
    private val trustManager: TrustManager,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TrustClipboardSyncManager"
        const val MIN_SYNC_INTERVAL_MS = 30_000L // 30 seconds minimum
        private const val DEBOUNCE_DELAY_MS = 1000L // 1 second debounce
    }
    
    private val _syncEnabled = MutableStateFlow(false)
    val syncEnabled: StateFlow<Boolean> = _syncEnabled.asStateFlow()
    
    private val _lastSyncedContent = MutableStateFlow<String?>(null)
    private val lastSyncedContent: StateFlow<String?> = _lastSyncedContent.asStateFlow()
    
    private var clipboardMonitorJob: Job? = null
    private var trustEventJob: Job? = null
    
    init {
        // Listen for clipboard updates from other devices
        trustEventJob = scope.launch {
            trustManager.getTrustEvents().collect { event ->
                when (event) {
                    is TrustEvent.ClipboardUpdate -> {
                        handleRemoteClipboardUpdate(event.content, event.fromDevice)
                    }
                    else -> {}
                }
            }
        }
    }
    
    /**
     * Enable or disable clipboard sync
     */
    fun setSyncEnabled(enabled: Boolean) {
        _syncEnabled.value = enabled
        
        if (enabled) {
            startClipboardMonitoring()
        } else {
            stopClipboardMonitoring()
        }
    }
    
    /**
     * Check if clipboard sync is available (have trusted devices with permission)
     */
    suspend fun isClipboardSyncAvailable(): Boolean {
        val trustedDevices = trustManager.trustedDevices.value
        return trustedDevices.any { device ->
            device.permissions.contains(Permission.PERMISSION_CLIPBOARD_SYNC)
        }
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
        if (content == _lastSyncedContent.value) {
            return
        }
        
        // Check if we have a trust group
        val trustGroup = trustManager.currentTrustGroup.value
        if (trustGroup == null) {
            log(TAG, "No trust group, skipping clipboard sync")
            return
        }
        
        // Check if we have trusted devices with clipboard sync permission
        val devicesWithClipboardSync = trustManager.trustedDevices.value.filter { device ->
            device.permissions.contains(Permission.PERMISSION_CLIPBOARD_SYNC)
        }
        
        if (devicesWithClipboardSync.isEmpty()) {
            log(TAG, "No trusted devices with clipboard sync permission")
            return
        }
        
        try {
            // Sync to trusted devices
            log(TAG, "Syncing clipboard content to ${devicesWithClipboardSync.size} devices")
            trustManager.syncClipboard(content)
            
            // Update last synced content
            _lastSyncedContent.value = content
            
        } catch (e: Exception) {
            log(TAG, "Failed to sync clipboard", e)
        }
    }
    
    /**
     * Handle clipboard update from remote device
     */
    private suspend fun handleRemoteClipboardUpdate(content: String, fromDevice: String) {
        if (!_syncEnabled.value) {
            log(TAG, "Clipboard sync disabled, ignoring update from $fromDevice")
            return
        }
        
        // Check if device has permission
        val trustedDevice = trustManager.trustedDevices.value.find { it.deviceId == fromDevice }
        if (trustedDevice == null || !trustedDevice.permissions.contains(Permission.PERMISSION_CLIPBOARD_SYNC)) {
            log(TAG, "Device $fromDevice doesn't have clipboard sync permission")
            return
        }
        
        // Update local clipboard
        try {
            clipboardManager.write(content)
            _lastSyncedContent.value = content
            log(TAG, "Updated clipboard from device: $fromDevice")
            
            // Show notification to user (this would be handled by UI layer)
            // For now, just log
            log(TAG, "Clipboard updated from ${trustedDevice.deviceName}")
            
        } catch (e: Exception) {
            log(TAG, "Failed to update clipboard from remote", e)
        }
    }
    
    /**
     * Manually sync current clipboard content
     */
    suspend fun syncNow() {
        if (!_syncEnabled.value) {
            log(TAG, "Clipboard sync is disabled")
            return
        }
        
        val content = clipboardManager.read()
        if (content.isNotEmpty()) {
            handleLocalClipboardChange(content)
        }
    }
    
    /**
     * Get clipboard sync settings for a specific device
     */
    suspend fun getDeviceClipboardSyncEnabled(deviceId: String): Boolean {
        val device = trustManager.trustedDevices.value.find { it.deviceId == deviceId }
        return device?.permissions?.contains(Permission.PERMISSION_CLIPBOARD_SYNC) ?: false
    }
    
    /**
     * Update clipboard sync permission for a device
     */
    suspend fun setDeviceClipboardSyncEnabled(deviceId: String, enabled: Boolean) {
        val device = trustManager.trustedDevices.value.find { it.deviceId == deviceId } ?: return
        
        val updatedPermissions = if (enabled) {
            device.permissions + Permission.PERMISSION_CLIPBOARD_SYNC
        } else {
            device.permissions - Permission.PERMISSION_CLIPBOARD_SYNC
        }
        
        val updatedDevice = device.copy(permissions = updatedPermissions)
        
        // Update in trust store
        trustManager.trustStore.addTrustedDevice(updatedDevice)
        
        // Broadcast update to other devices
        trustManager.protocolHandler.broadcastMemberUpdate(
            com.carlom.klardrop.protos.trust.UpdateAction.UPDATE_ACTION_UPDATE,
            updatedDevice
        )
    }
}

/**
 * Data class for clipboard sync preferences
 */
data class ClipboardSyncPreferences(
    val enabled: Boolean = false,
    val syncInterval: Long = TrustClipboardSyncManager.MIN_SYNC_INTERVAL_MS,
    val showNotifications: Boolean = true,
    val excludedApps: Set<String> = emptySet()
)