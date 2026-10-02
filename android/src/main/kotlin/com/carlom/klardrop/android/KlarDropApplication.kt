package com.carlom.klardrop.android

import android.app.Application
import android.content.Context
import android.util.Log
import com.carlom.klardrop.android.service.DiscoveryForegroundService
import com.carlom.klardrop.android.share.ActiveTransfers
import com.carlom.klardrop.android.share.AndroidTransferAnchor
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.Klardrop.DiscoveryMode
import com.klardrop.common.initCrashReporter
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class KlarDropApplication : Application() {

  lateinit var klardrop: Klardrop
    private set

  override fun onCreate() {
    super.onCreate()

    // `ApplicationInfo.isDebug` is a desktop/CLI concept on other platforms — it comes
    // from the `--debug` command-line flag. On Android read the debuggable flag off the
    // installed package instead, which is exactly what bugsnag-android derived its
    // "development" release stage from.
    val isDebuggable =
      (getApplicationInfo().flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    val debugConfig = if (isDebuggable) loadAndroidDebugConfig(filesDir) else AndroidDebugConfig()
    val applicationInfo = ApplicationInfo(
      isDebug = isDebuggable,
      enableKlardropServer = debugConfig.enableKlardrop,
      enableNearbyServer = debugConfig.enableNearby,
      enableBle = debugConfig.enableBle,
      controlPort = if (isDebuggable) debugConfig.controlPort else null,
    )

    // Only emit events from production builds. Development churn (debug builds, hot
    // reload, manual disconnect tests) was filling the dashboard with peer-hangup noise
    // that masked real production issues. Expected protocol noise (peer reset, connect
    // refused, BLE handshake disconnect) is dropped by CrashReporter.notify itself, so
    // there is no per-platform onError hook to keep in sync any more.
    initCrashReporter(
      context = this,
      appVersion = applicationInfo.appVersion,
      isProduction = !isDebuggable,
    )

    klardrop = Klardrop(
      applicationInfo = applicationInfo,
      internalPlatformDependency = InternalPlatformDependencies(this, applicationInfo),
      transferAnchor = AndroidTransferAnchor(this),
    )
    // Start with the radios off: this process is often spawned with no UI (a notification action,
    // the transfer service). The collector below turns discovery on once an Activity is visible.
    klardrop.setDiscoveryMode(DiscoveryMode.OFF)
    klardrop.init()

    val commonComponent = klardrop.commonComponent
    commonComponent.coroutines().appScope.launch {
      commonComponent.localPropertiesRepository().properties
        .map { it.backgroundDiscoveryEnabled }
        .distinctUntilChanged()
        .collect { enabled ->
          if (enabled) {
            runCatching { DiscoveryForegroundService.start(this@KlarDropApplication) }
          } else {
            DiscoveryForegroundService.stop(this@KlarDropApplication)
          }
        }
    }

    // Full-speed discovery while the user can see the app; low-power while they've opted into
    // staying discoverable (that foreground service is what keeps us running in the background);
    // otherwise everything off, including the idle peer connections and their heartbeats.
    commonComponent.coroutines().appScope.launch {
      combine(
        commonComponent.foregroundState().isForeground,
        commonComponent.localPropertiesRepository().properties.map { it.backgroundDiscoveryEnabled },
      ) { foreground, background ->
        when {
          foreground -> DiscoveryMode.FOREGROUND
          background -> DiscoveryMode.BACKGROUND
          else -> DiscoveryMode.OFF
        }
      }
        .distinctUntilChanged()
        .collectLatest { mode ->
          // Leaving the foreground: wait out rotations and quick app switches (the started-Activity
          // count briefly hits 0) before tearing down. collectLatest cancels this if we come back.
          if (mode != DiscoveryMode.FOREGROUND) delay(BACKGROUND_GRACE)
          klardrop.setDiscoveryMode(mode)
          if (mode == DiscoveryMode.OFF) {
            ActiveTransfers.state.first { it.isEmpty() }
            klardrop.closeIdleConnections()
          }
        }
    }
  }
}

private val BACKGROUND_GRACE = 5.seconds

fun Context.appKlardrop(): Klardrop =
  (applicationContext as KlarDropApplication).klardrop

/** Written by `scripts/klardrop-ctl` via `adb shell run-as`. Missing file → all transports on. */
private data class AndroidDebugConfig(
  val controlPort: Int = 8766,
  val enableKlardrop: Boolean = true,
  val enableNearby: Boolean = true,
  val enableBle: Boolean = true,
)

private fun loadAndroidDebugConfig(filesDir: File): AndroidDebugConfig {
  val file = File(filesDir, "klardrop-debug.json")
  if (!file.isFile) return AndroidDebugConfig()
  return try {
    val text = file.readText()
    AndroidDebugConfig(
      controlPort = Regex("\"controlPort\"\\s*:\\s*(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 8766,
      enableKlardrop = boolField(text, "enableKlardrop") ?: true,
      enableNearby = boolField(text, "enableNearby") ?: true,
      enableBle = boolField(text, "enableBle") ?: true,
    )
  } catch (e: Exception) {
    Log.w("KlarDropApplication", "Failed to parse ${file.absolutePath}: ${e.message}")
    AndroidDebugConfig()
  }
}

private fun boolField(json: String, key: String): Boolean? =
  Regex("\"$key\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
