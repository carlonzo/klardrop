package com.carlom.klardrop.common.utils

actual fun nativeLogger(tag: String, message: String) {
  println("[$tag]: $message")
}

actual fun nativeLoggerException(tag: String, message: String, throwable: Throwable) {
  System.err.println("[$tag]: $message")
  throwable.printStackTrace()
}