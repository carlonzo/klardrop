package com.carlom.klardrop.common.connectivity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import com.carlom.klardrop.common.notifications.ForegroundState
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

actual class ConnectivityRestrictionMonitor(
  context: Context,
  private val foregroundState: ForegroundState,
) {

  private val appContext = context.applicationContext
  private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
  private val connectivityManager =
    appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  // Pinged by [refresh] to force a re-snapshot without waiting for an OS event.
  // Buffered + drop-oldest so emitting from a non-suspending caller never blocks.
  private val refreshTrigger = MutableSharedFlow<Unit>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )

  /**
   * Events that change restriction state: Power Save mode toggling, the
   * device-idle whitelist changing (the exemption dialog's Allow, or an
   * adb/dumpsys grant — the action string is the system's, stable across the
   * API levels we ship even though it has no public constant), and the active
   * network changing (metered <-> unmetered). Plus a foreground transition and
   * [refresh], mirroring PermissionsMonitor.
   */
  actual fun observe(): Flow<ConnectivityRestrictions> = merge(
    foregroundState.isForeground.filter { it }.map { },
    refreshTrigger,
    callbackFlow {
      val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
          trySend(Unit)
        }
      }
      val filter = IntentFilter().apply {
        addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        addAction(ACTION_POWER_SAVE_WHITELIST_CHANGED)
      }
      appContext.registerReceiver(receiver, filter)
      val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          trySend(Unit)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
          trySend(Unit)
        }

        override fun onLost(network: Network) {
          trySend(Unit)
        }
      }
      connectivityManager.registerDefaultNetworkCallback(networkCallback)
      trySend(Unit)
      awaitClose {
        appContext.unregisterReceiver(receiver)
        connectivityManager.unregisterNetworkCallback(networkCallback)
      }
    },
  )
    .map { snapshot() }
    .onStart { emit(snapshot()) }
    .distinctUntilChanged()
    .flowOn(Dispatchers.Default)

  actual fun refresh() {
    refreshTrigger.tryEmit(Unit)
  }

  private fun snapshot(): ConnectivityRestrictions {
    val exempt = powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
    val restrictions = ConnectivityRestrictions.derive(
      powerSaveMode = powerManager.isPowerSaveMode,
      batteryOptimizationExempt = exempt,
      activeNetworkMetered = isActiveNetworkMetered(),
      userDeniedOnMetered = userDeniedOnMetered(),
    )
    log(
      "ConnectivityRestriction",
      "battery-saver=${restrictions.batterySaverBlocking} battery-optimization-exempt=$exempt " +
        "metered-denied=${restrictions.meteredNetworkDenied}",
    )
    return restrictions
  }

  private fun isActiveNetworkMetered(): Boolean {
    val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
      ?: return false // no active network — nothing metered to be denied on
    return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
  }

  private fun userDeniedOnMetered(): Boolean {
    // ponytail: the per-app metered deny (netd's metered_deny_user chain from the T1
    // finding) has no public reader; Data Saver's restrict-background status is the
    // closest public per-app "denied on metered nets" signal. Upgrade path: a
    // socket-probe heuristic or hidden NetworkPolicyManager access.
    // RESTRICT_BACKGROUND_STATUS_ENABLED was named ..._RESTRICTED before API 36 —
    // same value, so the comparison holds on every API level we ship.
    return connectivityManager.restrictBackgroundStatus ==
      ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
  }

  private companion object {
    // PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED — hidden constant, sent by the system.
    const val ACTION_POWER_SAVE_WHITELIST_CHANGED = "android.os.action.POWER_SAVE_WHITELIST_CHANGED"
  }
}
