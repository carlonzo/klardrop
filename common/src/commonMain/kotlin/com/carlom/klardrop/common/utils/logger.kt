package com.carlom.klardrop.common.utils

import com.klardrop.common.BugsnagBreadcrumbType
import com.klardrop.common.BugsnagWrapper

fun log(message: String) {
  nativeLogger("Klardrop", message)
  BugsnagWrapper.leaveBreadcrumb(message, BugsnagBreadcrumbType.LOG)
}

fun log(message: String, throwable: Throwable) {
  nativeLoggerException("Klardrop", message, throwable)
  BugsnagWrapper.leaveBreadcrumb(message, type = BugsnagBreadcrumbType.ERROR)
  BugsnagWrapper.notify(throwable)
}

fun log(tag: String, message: String, throwable: Throwable) {
  nativeLoggerException(tag, message, throwable)
  BugsnagWrapper.leaveBreadcrumb("[$tag]: $message", type = BugsnagBreadcrumbType.ERROR)
  BugsnagWrapper.notify(throwable)
}

fun log(tag: String, message: String) {
  nativeLogger(tag, message)
  BugsnagWrapper.leaveBreadcrumb("[$tag]: $message", type = BugsnagBreadcrumbType.LOG)
}

// Local-only variant for failures that are part of the protocol's normal life cycle
// (peer drops, timed-out connects, BLE disconnects). The exception still goes to the
// platform logger and a breadcrumb so it shows up if a *real* crash later happens
// nearby, but we do not pay the Bugsnag noise tax for it.
fun logLocal(tag: String, message: String, throwable: Throwable) {
  nativeLoggerException(tag, message, throwable)
  BugsnagWrapper.leaveBreadcrumb("[$tag]: $message", type = BugsnagBreadcrumbType.LOG)
}

expect fun nativeLogger(tag: String, message: String)
expect fun nativeLoggerException(tag: String, message: String, throwable: Throwable)