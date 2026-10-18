package com.carlom.klardrop.common.utils

internal actual fun nativeLogger(tag: String, message: String) {
  println("[$tag]: $message")
}

internal actual fun nativeLoggerException(tag: String, message: String, throwable: Throwable) {
  System.err.println("[$tag]: $message")
  throwable.printStackTrace()
}