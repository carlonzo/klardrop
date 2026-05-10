package com.klardrop.common

import com.bugsnag.Bugsnag
import com.bugsnag.Severity
import com.carlom.klardrop.common.utils.isExpectedNetworkNoise

actual object BugsnagWrapper {

  // Lazily constructed. The Bugsnag JVM SDK opens a session on construction, so
  // building it eagerly inside `commonTest` runs (which load this class via the
  // `desktopJvm` target) would upload test failures as production events.
  private var bugsnag: Bugsnag? = null

  fun init(appVersion: String) {
    val instance = bugsnag ?: Bugsnag(BugsnagConfig.apiKey).also { bugsnag = it }
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
}
