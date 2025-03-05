package com.carlom.klardrop.common.utils

import io.sentry.kotlin.multiplatform.Sentry

fun log(message: String) {
  nativeLogger("Klardrop", message)
  Sentry.captureMessage(message)
}

fun log(message: String, throwable: Throwable) {
  nativeLoggerException("Klardrop", message, throwable)

  Sentry.captureMessage(message)
  Sentry.captureException(throwable)
}

fun log(tag: String, message: String, throwable: Throwable) {
  nativeLoggerException(tag, message, throwable)

  Sentry.captureMessage("[$tag]: $message")
  Sentry.captureException(throwable)
}

fun log(tag: String, message: String) {
  nativeLogger(tag, message)
  Sentry.captureMessage("[$tag]: $message")
}

expect fun nativeLogger(tag: String, message: String)
expect fun nativeLoggerException(tag: String, message: String, throwable: Throwable)