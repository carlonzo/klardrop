package com.klardrop.common

import com.bugsnag.kmp.BreadcrumbType
import com.bugsnag.kmp.Bugsnag


actual object BugsnagWrapper {

  init {
    com.bugsnag.cocoa.Bugsnag.startWithApiKey(BugsnagConfig.apiKey)
  }

  actual fun notify(throwable: Throwable) {
    try {
      // Use native iOS Bugsnag via Objective-C interop
      // This will be handled by the iOS app's Bugsnag configuration
      Bugsnag.notify(throwable)
    } catch (e: Throwable) {
      // Bugsnag not available (e.g., during tests) - just print the exception
      println("iOS Bugsnag notify: ${throwable.message}")
      throwable.printStackTrace()
    }
  }

  actual fun leaveBreadcrumb(message: String, type: BugsnagBreadcrumbType) {
    try {
      // Use native iOS Bugsnag via Objective-C interop
      Bugsnag.leaveBreadcrumb(message, emptyMap(), type.toNativeBreadcrumbType())
    } catch (e: Throwable) {
      // Bugsnag not available (e.g., during tests) - just print the breadcrumb
      println("iOS Bugsnag breadcrumb: $message (type: $type)")
    }
  }

  private fun BugsnagBreadcrumbType.toNativeBreadcrumbType(): BreadcrumbType {
    return when (this) {
      BugsnagBreadcrumbType.ERROR -> BreadcrumbType.ERROR
      BugsnagBreadcrumbType.LOG -> BreadcrumbType.LOG
      BugsnagBreadcrumbType.MANUAL -> BreadcrumbType.MANUAL
      BugsnagBreadcrumbType.NAVIGATION -> BreadcrumbType.NAVIGATION
      BugsnagBreadcrumbType.PROCESS -> BreadcrumbType.PROCESS
      BugsnagBreadcrumbType.REQUEST -> BreadcrumbType.REQUEST
      BugsnagBreadcrumbType.STATE -> BreadcrumbType.STATE
      BugsnagBreadcrumbType.USER -> BreadcrumbType.USER
    }
  }
}