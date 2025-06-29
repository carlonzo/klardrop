package com.klardrop.common

import com.bugsnag.Bugsnag
import com.bugsnag.Severity

actual object BugsnagWrapper {

  private val bugsnag: Bugsnag = Bugsnag(BugsnagConfig.apiKey)

  fun init(appVersion: String) {
    bugsnag.setAppVersion(appVersion)
  }

  actual fun notify(throwable: Throwable) {
    bugsnag.notify(throwable, Severity.ERROR)
  }

  actual fun leaveBreadcrumb(message: String, type: BugsnagBreadcrumbType) {

  }
}