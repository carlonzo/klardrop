package com.carlom.klardrop.common.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.carlom.klardrop.common.notifications.ForegroundState
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

actual class PermissionsMonitor(
  private val context: Context,
  private val foregroundState: ForegroundState,
) {

  // Pinged by [refresh] to force a re-snapshot without a foreground transition.
  // Buffered + drop-oldest so emitting from a non-suspending caller never blocks
  // and a fresh request always supersedes a stale pending one.
  private val refreshTrigger = MutableSharedFlow<Unit>(
    extraBufferCapacity = 1,
    onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
  )

  /**
   * Runtime permissions change either via system Settings (which leaves the
   * app, so a foreground transition re-snapshots) or via the in-app runtime
   * prompt (which only pauses the Activity, producing no transition — that path
   * is covered by [refresh]). The initial emission comes from [onStart]; the
   * [distinctUntilChanged] keeps redundant snapshots from churning the UI.
   */
  actual fun observe(): Flow<PermissionsState> = merge(
    foregroundState.isForeground.filter { it }.map { },
    refreshTrigger,
  )
    .map { snapshot() }
    .onStart { emit(snapshot()) }
    .distinctUntilChanged()
    .flowOn(Dispatchers.Default)

  actual fun refresh() {
    refreshTrigger.tryEmit(Unit)
  }

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
