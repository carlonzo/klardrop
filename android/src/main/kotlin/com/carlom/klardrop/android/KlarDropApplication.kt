package com.carlom.klardrop.android

import android.app.Application
import android.content.Context
import com.bugsnag.android.Bugsnag
import com.bugsnag.android.Configuration
import com.carlom.klardrop.android.di.ApplicationComponent
import com.carlom.klardrop.android.di.DaggerApplicationComponent
import com.carlom.klardrop.android.service.DiscoveryForegroundService
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.utils.isExpectedNetworkNoise
import com.klardrop.common.BugsnagConfig
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class KlarDropApplication : Application(), ApplicationComponentProvider {

  private lateinit var component: ApplicationComponent
  override val applicationComponent: ApplicationComponent
    get() = component

  override fun onCreate() {
    super.onCreate()

    val bugsnagConfig = Configuration(BugsnagConfig.apiKey).apply {
      // Only emit events from production builds. Development churn (debug builds,
      // hot reload, manual disconnect tests) was filling the dashboard with peer-
      // hangup noise that masked real production issues.
      enabledReleaseStages = setOf("production")
      addOnError { event ->
        // Last-line drop for expected protocol noise (peer reset, connect refused,
        // BLE handshake disconnect). Returning false discards the event.
        val noise = event.originalError?.isExpectedNetworkNoise() == true
        !noise
      }
    }
    Bugsnag.start(this, bugsnagConfig)

    val applicationInfo = ApplicationInfo()

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