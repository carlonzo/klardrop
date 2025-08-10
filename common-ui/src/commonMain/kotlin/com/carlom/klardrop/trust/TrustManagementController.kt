package com.carlom.klardrop.trust

import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.trust.ClipboardSyncManager
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * Controller for the trust management screen.
 * Manages trusted devices list, clipboard sync settings, and trust operations.
 */
class TrustManagementController(
    private val coroutines: Coroutines,
    private val trustManager: TrustManager,
    private val clipboardSyncManager: ClipboardSyncManager
) {
    
    constructor(commonComponent: CommonComponent) : this(
        commonComponent.coroutines(),
        commonComponent.trustManager(),
        commonComponent.clipboardSyncManager()
    )
    
    private val controllerScope = coroutines.newScope(coroutines.mainDispatcher + SupervisorJob())
    
    private val _state = MutableStateFlow(TrustManagementState())
    val state: StateFlow<TrustManagementState> = _state.asStateFlow()
    
    init {
        loadTrustedDevices()
        loadClipboardSyncStatus()
    }
    
    /**
     * Load the list of trusted devices.
     */
    fun loadTrustedDevices() {
        controllerScope.launch {
            try {
                val trustedDevices = trustManager.getTrustedDevices()
                val deviceUIList = trustedDevices.map { device ->
                    TrustedDeviceUI(
                        deviceId = device.deviceId,
                        deviceName = device.deviceId, // TODO: Get actual device name from storage
                        deviceType = "Unknown", // TODO: Get actual device type from storage
                        trustedSince = formatTrustDate(System.currentTimeMillis()) // TODO: Get actual trust date
                    )
                }
                
                _state.update { currentState ->
                    currentState.copy(
                        trustedDevices = deviceUIList,
                        isLoading = false
                    )
                }
                
                log("TrustManagementController", "Loaded ${trustedDevices.size} trusted devices")
                
            } catch (e: Exception) {
                log("TrustManagementController", "Failed to load trusted devices: ${e.message}")
                _state.update { currentState ->
                    currentState.copy(
                        error = "Failed to load trusted devices",
                        isLoading = false
                    )
                }
            }
        }
    }
    
    /**
     * Load current clipboard sync settings.
     */
    private fun loadClipboardSyncStatus() {
        val isEnabled = clipboardSyncManager.isClipboardSyncEnabled()
        _state.update { currentState ->
            currentState.copy(isClipboardSyncEnabled = isEnabled)
        }
        log("TrustManagementController", "Clipboard sync enabled: $isEnabled")
    }
    
    /**
     * Toggle clipboard synchronization on/off.
     */
    fun toggleClipboardSync(enabled: Boolean) {
        controllerScope.launch {
            try {
                clipboardSyncManager.setClipboardSyncEnabled(enabled)
                _state.update { currentState ->
                    currentState.copy(isClipboardSyncEnabled = enabled)
                }
                
                log("TrustManagementController", "Clipboard sync ${if (enabled) "enabled" else "disabled"}")
                
            } catch (e: Exception) {
                log("TrustManagementController", "Failed to toggle clipboard sync: ${e.message}")
                _state.update { currentState ->
                    currentState.copy(error = "Failed to update clipboard sync setting")
                }
            }
        }
    }
    
    /**
     * Remove trust relationship with a device.
     */
    fun removeTrust(deviceId: String) {
        controllerScope.launch {
            try {
                log("TrustManagementController", "Removing trust for device: $deviceId")
                trustManager.removeTrust(deviceId)
                
                // Refresh the trusted devices list
                loadTrustedDevices()
                
                log("TrustManagementController", "Trust removed successfully for device: $deviceId")
                
            } catch (e: Exception) {
                log("TrustManagementController", "Failed to remove trust for device $deviceId: ${e.message}")
                _state.update { currentState ->
                    currentState.copy(error = "Failed to remove trust for device")
                }
            }
        }
    }
    
    /**
     * Clear any error state.
     */
    fun clearError() {
        _state.update { currentState ->
            currentState.copy(error = null)
        }
    }
    
    /**
     * Refresh all data.
     */
    fun refresh() {
        _state.update { currentState ->
            currentState.copy(isLoading = true, error = null)
        }
        loadTrustedDevices()
        loadClipboardSyncStatus()
    }
    
    /**
     * Clean up resources when the controller is no longer needed.
     */
    fun dispose() {
        controllerScope.cancel()
        log("TrustManagementController", "Controller disposed")
    }
    
    /**
     * Format timestamp to readable date string.
     */
    private fun formatTrustDate(timestamp: Long): String {
        return try {
            // Simple date formatting - could be enhanced with proper date library
            "Recently"
        } catch (e: Exception) {
            "Unknown"
        }
    }
}

/**
 * State for the trust management screen.
 */
data class TrustManagementState(
    val trustedDevices: List<TrustedDeviceUI> = emptyList(),
    val isClipboardSyncEnabled: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null
)