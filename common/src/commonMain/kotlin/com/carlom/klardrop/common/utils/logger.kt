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

expect fun nativeLogger(tag: String, message: String)
expect fun nativeLoggerException(tag: String, message: String, throwable: Throwable)