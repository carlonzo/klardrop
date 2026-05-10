package com.klardrop.common

import com.bugsnag.kmp.BreadcrumbType
import com.bugsnag.kmp.Bugsnag
import com.carlom.klardrop.common.utils.isExpectedNetworkNoise


actual object BugsnagWrapper {

  // Bugsnag is started from iosApp.swift (the Cocoa SDK reads its API key from
  // Info.plist there). The previous `init {}` block here started it again as a
  // side-effect of class-load, which fired during tests and produced phantom
  // "production" reports for routine test failures.

  actual fun notify(throwable: Throwable) {
    if (throwable.isExpectedNetworkNoise()) return
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