package com.carlom.klardrop.android

import android.app.Application
import android.content.Context
import com.carlom.klardrop.android.di.ApplicationComponent
import com.carlom.klardrop.android.di.DaggerApplicationComponent
import com.carlom.klardrop.android.service.DiscoveryForegroundService
import com.carlom.klardrop.common.ApplicationInfo
import com.klardrop.common.initCrashReporter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class KlarDropApplication : Application(), ApplicationComponentProvider {

  private lateinit var component: ApplicationComponent
  override val applicationComponent: ApplicationComponent
    get() = component

  override fun onCreate() {
    super.onCreate()

    val applicationInfo = ApplicationInfo()

    // Only emit events from production builds. Development churn (debug builds, hot
    // reload, manual disconnect tests) was filling the dashboard with peer-hangup noise
    // that masked real production issues. Expected protocol noise (peer reset, connect
    // refused, BLE handshake disconnect) is dropped by CrashReporter.notify itself, so
    // there is no per-platform onError hook to keep in sync any more.
    // `ApplicationInfo.isDebug` is a desktop/CLI concept — it comes from the `--debug`
    // command-line flag and is always false here, so it cannot be used to tell a debug
    // build apart. Read the debuggable flag off the installed package instead, which is
    // exactly what bugsnag-android derived its "development" release stage from.
    val isDebuggable =
      (getApplicationInfo().flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    initCrashReporter(
      context = this,
      appVersion = applicationInfo.appVersion,
      isProduction = !isDebuggable,
    )

    component = DaggerApplicationComponent.factory().create(this, applicationInfo)
    val klardrop = component.klardrop()
    klardrop.init()

    // Drive the opt-in "stay discoverable" foreground service off the persisted preference: start
    // it when the user enables background discovery, stop it when they disable it. startForeground
    // can be blocked if we're in the background (Android 12+), so guard it — MainActivity re-ensures
    // the service on next foreground launch.
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

interface ApplicationComponentProvider {
  val applicationComponent: ApplicationComponent
}

fun Context.applicationComponent(): ApplicationComponent {
  return (applicationContext as ApplicationComponentProvider).applicationComponent
}