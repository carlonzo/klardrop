package com.klardrop.common

import com.bugsnag.Bugsnag
import com.bugsnag.Severity
import com.carlom.klardrop.common.utils.isExpectedNetworkNoise

actual object BugsnagWrapper {

  // Lazily constructed. The Bugsnag JVM SDK opens a session on construction, so
  // building it eagerly inside `commonTest` runs (which load this class via the
  // `desktopJvm` target) would upload test failures as production events.
  private var bugsnag: Bugsnag? = null

  // The JVM SDK has no global setUser/addMetadata; values are stashed here and
  // applied to every event via the addOnError callback registered in init().
  @Volatile private var deviceId: String? = null
  @Volatile private var deviceName: String? = null
  @Volatile private var osType: String? = null

  fun init(appVersion: String) {
    val instance = bugsnag ?: Bugsnag(BugsnagConfig.apiKey).also {
      bugsnag = it
      it.addOnError { event ->
        deviceId?.let { id -> event.setUser(id, null, deviceName) }
        event.addMetadata("device", "platform", "desktop")
        osType?.let { os -> event.addMetadata("device", "osType", os) }
        true
      }
    }
    instance.setAppVersion(appVersion)
    // Filtering happens in `notify` below — keeps the API surface uniform with
    // the Android/iOS wrappers and avoids depending on the JVM SDK's Callback
    // type (which has shifted between versions).
  }

  actual fun notify(throwable: Throwable) {
    if (throwable.isExpectedNetworkNoise()) return
    bugsnag?.notify(throwable, Severity.ERROR)
  }

  actual fun leaveBreadcrumb(message: String, type: BugsnagBreadcrumbType) {

  }

  actual fun setUser(deviceId: String, deviceName: String, osType: String) {
    this.deviceId = deviceId
    this.deviceName = deviceName
    this.osType = osType
  }
}
