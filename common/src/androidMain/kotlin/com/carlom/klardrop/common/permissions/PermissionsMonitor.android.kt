package com.carlom.klardrop.common.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.carlom.klardrop.common.notifications.ForegroundState
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

actual class PermissionsMonitor(
  private val context: Context,
  private val foregroundState: ForegroundState,
) {

  /**
   * The only way runtime permissions change while the process is alive is via
   * system Settings, which requires leaving the app — so re-snapshotting on
   * each foreground transition is sufficient, and avoids the log spam of a
   * periodic poll. The initial emission comes from the StateFlow's replay of
   * its current value the first time the activity is started.
   */
  actual fun observe(): Flow<PermissionsState> = foregroundState.isForeground
    .filter { it }
    .map { snapshot() }
    .onStart { emit(snapshot()) }
    .distinctUntilChanged()
    .flowOn(Dispatchers.Default)

  private fun snapshot(): PermissionsState {
    val capabilities = mutableMapOf<Capability, CapabilityStatus>()

    capabilities[Capability.BLUETOOTH] = bluetoothStatus()
    capabilities[Capability.NOTIFICATIONS] = notificationsStatus()
    capabilities[Capability.LOCATION] = locationStatus()
    // mDNS via NsdManager doesn't require a runtime permission on the SDK
    // versions we ship — manifest-level multicast access is enough. If we ever
    // start using Wi-Fi Aware / direct peer discovery this flips to
    // NEARBY_WIFI_DEVICES on T+.
    capabilities[Capability.LOCAL_NETWORK] = CapabilityStatus.NotApplicable
    capabilities[Capability.NEARBY_WIFI_DEVICES] = CapabilityStatus.NotApplicable

    log("PermissionsMonitor", "snapshot: $capabilities")
    return PermissionsState(capabilities = capabilities)
  }

  private fun bluetoothStatus(): CapabilityStatus {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val perms = listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
      )
      if (perms.all { hasPermission(it) }) CapabilityStatus.Granted
      else CapabilityStatus.Unknown
    } else {
      // Pre-S BLE relies on FINE_LOCATION at runtime; that's surfaced under
      // [Capability.LOCATION] separately, so report install-time BLUETOOTH /
      // BLUETOOTH_ADMIN as Granted (manifest-declared, no runtime gate).
      CapabilityStatus.Granted
    }
  }

  private fun notificationsStatus(): CapabilityStatus {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) CapabilityStatus.Granted
      else CapabilityStatus.Unknown
    } else {
      // Pre-T notifications are unconditionally allowed unless the user
      // disables them in Settings — which we can't query without bouncing
      // through NotificationManagerCompat. Treat as not actionable.
      CapabilityStatus.NotApplicable
    }
  }

  private fun locationStatus(): CapabilityStatus {
    return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) CapabilityStatus.Granted
      else CapabilityStatus.Unknown
    } else {
      // S+ uses dedicated BLUETOOTH_* runtime permissions instead.
      CapabilityStatus.NotApplicable
    }
  }

  private fun hasPermission(perm: String): Boolean {
    return context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
  }
}
