@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.utils

import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.stderr

actual fun nativeLogger(tag: String, message: String) {
  fprintf(stderr, "[%s]: %s\n", tag, message)
  fflush(stderr)
}

actual fun nativeLoggerException(tag: String, message: String, throwable: Throwable) {
  fprintf(stderr, "[%s]: %s\n", tag, message)
  fprintf(stderr, "%s\n", throwable.stackTraceToString())
  fflush(stderr)
}
