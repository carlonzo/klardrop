package com.klardrop.common

import cocoapods.Bugsnag.Bugsnag
import com.carlom.klardrop.common.utils.isExpectedNetworkNoise
import platform.Foundation.NSException

// macOS uses the Bugsnag Cocoa SDK directly via cinterop (the bugsnag-kmp library
// has no macOS target). Bugsnag is started from Swift (MacApp.swift), exactly
// like iOS; this wrapper just forwards manual notifies/breadcrumbs/user info to
// the same native singleton.
actual object BugsnagWrapper {

  actual fun notify(throwable: Throwable) {
    if (throwable.isExpectedNetworkNoise()) return
    // The Cocoa notify() takes an NSException. Kotlin frames are lost here — only
    // type + message survive. Uncaught Kotlin crashes are caught by the Cocoa
    // handler with a native stacktrace. NSExceptionKt would improve fidelity.
    val exception = NSException.exceptionWithName(
      name = throwable::class.qualifiedName ?: "KotlinException",
      reason = throwable.message ?: throwable.toString(),
      userInfo = null,
    )
    Bugsnag.notify(exception)
  }

  actual fun leaveBreadcrumb(message: String, type: BugsnagBreadcrumbType) {
    Bugsnag.leaveBreadcrumbWithMessage(message)
  }

  actual fun setUser(deviceId: String, deviceName: String, osType: String) {
    Bugsnag.setUser(deviceId, withEmail = null, andName = deviceName)
    Bugsnag.addMetadata("macos", withKey = "platform", toSection = "device")
    Bugsnag.addMetadata(osType, withKey = "osType", toSection = "device")
  }
}
