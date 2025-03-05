package com.carlom.klardrop.common.utils

import platform.Foundation.NSLog

actual fun nativeLogger(tag: String, message: String) {
  NSLog("[Klardrop]: [$tag]: $message")
}

actual fun nativeLoggerException(tag: String, message: String, throwable: Throwable) {
  NSLog("[Klardrop]: ERROR [$tag]: $message")
  NSLog("[Klardrop]: ERROR [$tag]: ${throwable.stackTraceToString()}")
}