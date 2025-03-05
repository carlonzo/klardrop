package com.carlom.klardrop.common.utils

import android.util.Log

actual fun nativeLogger(tag: String, message: String) {
  Log.d(tag, message)
}

actual fun nativeLoggerException(tag: String, message: String, throwable: Throwable) {
  Log.e(tag, message, throwable)
}