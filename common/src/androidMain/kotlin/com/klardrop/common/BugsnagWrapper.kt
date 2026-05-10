package com.klardrop.common

import com.bugsnag.android.Bugsnag
import com.carlom.klardrop.common.utils.isExpectedNetworkNoise

actual object BugsnagWrapper {

  actual fun notify(throwable: Throwable) {
    if (throwable.isExpectedNetworkNoise()) return
    try {
      Bugsnag.notify(throwable)
    } catch (e: IllegalStateException) {

    }
  }

  actual fun leaveBreadcrumb(message: String, type: BugsnagBreadcrumbType) {
    try {
      Bugsnag.leaveBreadcrumb(message, emptyMap(), type.toAndroidBreadcrumbType())
    } catch (e: IllegalStateException) {
      // Bugsnag not initialized
    }
  }
}

private fun BugsnagBreadcrumbType.toAndroidBreadcrumbType(): com.bugsnag.android.BreadcrumbType {
  return when (this) {
    BugsnagBreadcrumbType.ERROR -> com.bugsnag.android.BreadcrumbType.ERROR
    BugsnagBreadcrumbType.LOG -> com.bugsnag.android.BreadcrumbType.LOG
    BugsnagBreadcrumbType.MANUAL -> com.bugsnag.android.BreadcrumbType.MANUAL
    BugsnagBreadcrumbType.NAVIGATION -> com.bugsnag.android.BreadcrumbType.NAVIGATION
    BugsnagBreadcrumbType.PROCESS -> com.bugsnag.android.BreadcrumbType.PROCESS
    BugsnagBreadcrumbType.REQUEST -> com.bugsnag.android.BreadcrumbType.REQUEST
    BugsnagBreadcrumbType.STATE -> com.bugsnag.android.BreadcrumbType.STATE
    BugsnagBreadcrumbType.USER -> com.bugsnag.android.BreadcrumbType.USER
  }
}