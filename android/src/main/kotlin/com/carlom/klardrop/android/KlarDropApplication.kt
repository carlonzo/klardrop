package com.carlom.klardrop.android

import android.app.Application
import android.content.Context
import android.util.Log
import com.carlom.klardrop.android.service.DiscoveryForegroundService
import com.carlom.klardrop.android.share.AndroidTransferAnchor
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.klardrop.common.initCrashReporter
import java.io.File
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
  }
}

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
