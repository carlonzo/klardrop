package com.carlom.klardrop.common.utils

actual fun nativeLogger(tag: String, message: String) {
  println("[Klardrop]: [$tag]: $message")
}

actual fun nativeLoggerException(tag: String, message: String, throwable: Throwable) {
  println("[Klardrop]: [$tag]: $message ${throwable.message}")
  throwable.printStackTrace()
}