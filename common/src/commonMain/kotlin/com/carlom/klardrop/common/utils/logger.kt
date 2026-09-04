package com.carlom.klardrop.common.utils

import com.klardrop.common.BreadcrumbType
import com.klardrop.common.CrashReporter

fun log(message: String) {
  nativeLogger("Klardrop", message)
  LogBuffer.append("[Klardrop]: $message")
  CrashReporter.leaveBreadcrumb(message, BreadcrumbType.LOG)
}

fun log(message: String, throwable: Throwable) {
  nativeLoggerException("Klardrop", message, throwable)
  LogBuffer.append("[Klardrop]: $message (${throwable.message})")
  CrashReporter.leaveBreadcrumb(message, type = BreadcrumbType.ERROR)
  CrashReporter.notify(throwable)
}

fun log(tag: String, message: String, throwable: Throwable) {
  nativeLoggerException(tag, message, throwable)
  LogBuffer.append("[$tag]: $message (${throwable.message})")
  CrashReporter.leaveBreadcrumb("[$tag]: $message", type = BreadcrumbType.ERROR)
  CrashReporter.notify(throwable)
}

fun log(tag: String, message: String) {
  nativeLogger(tag, message)
  LogBuffer.append("[$tag]: $message")
  CrashReporter.leaveBreadcrumb("[$tag]: $message", type = BreadcrumbType.LOG)
}

// Local-only variant for failures that are part of the protocol's normal life cycle
// (peer drops, timed-out connects, BLE disconnects). The exception still goes to the
// platform logger and a breadcrumb so it shows up if a *real* crash later happens
// nearby, but we do not pay the crash-reporter noise tax for it.
fun logLocal(tag: String, message: String, throwable: Throwable) {
  nativeLoggerException(tag, message, throwable)
  CrashReporter.leaveBreadcrumb("[$tag]: $message", type = BreadcrumbType.LOG)
}

expect fun nativeLogger(tag: String, message: String)
expect fun nativeLoggerException(tag: String, message: String, throwable: Throwable)

/**
 * In-process ring of recent [log] lines so the debug control API can dump them
 * without scraping logcat / stdout. Best-effort: concurrent appends may drop a
 * line rather than block the caller.
 */
object LogBuffer {
  private const val CAPACITY = 2000

  @Volatile
  private var lines: List<String> = emptyList()

  fun append(line: String) {
    val current = lines
    val next = if (current.size < CAPACITY) current + line else current.drop(1) + line
    lines = next
  }

  fun snapshot(limit: Int = CAPACITY): List<String> {
    val current = lines
    if (limit >= current.size) return current
    return current.takeLast(limit)
  }

  fun clear() {
    lines = emptyList()
  }
}