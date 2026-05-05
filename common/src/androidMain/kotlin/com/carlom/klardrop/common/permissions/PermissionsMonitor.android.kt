package com.carlom.klardrop.common.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.time.Duration.Companion.seconds

actual class PermissionsMonitor(private val context: Context) {

  /**
   * Android has no runtime callback for "user changed a permission while we
   * were running" beyond Activity result callbacks (which only fire when *we*
   * requested it). The user can flip a runtime permission from system Settings
   * at any time, so we re-check every [POLL_INTERVAL]; when nothing's
   * outstanding the [distinctUntilChanged] keeps re-emissions free.
   */
  actual fun observe(): Flow<PermissionsState> = flow {
    while (true) {
      emit(snapshot())
      delay(POLL_INTERVAL)
    }
  }
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

  private companion object {
    val POLL_INTERVAL = 2.seconds
  }
}
