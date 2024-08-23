package com.carlom.klardrop.common.utils

import io.sentry.kotlin.multiplatform.Sentry

fun log(message: String) {
  println("[Klardrop]: $message")
  Sentry.captureMessage(message)
}

fun log(message: String, throwable: Throwable) {
  println("[Klardrop]: $message")
  throwable.printStackTrace()

  Sentry.captureMessage(message)
  Sentry.captureException(throwable)
}

fun log(tag: String, message: String, throwable: Throwable) {
  println("[Klardrop]: [$tag]: $message")
  throwable.printStackTrace()

  Sentry.captureMessage("[$tag]: $message")
  Sentry.captureException(throwable)
}

fun log(tag: String, message: String) {
  Sentry.captureMessage("[$tag]: $message")
  println("[Klardrop]: [$tag]: $message")
}