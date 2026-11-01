package com.carlom.klardrop.common.utils

import com.klardrop.common.BreadcrumbType
import com.klardrop.common.CrashReporter
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public fun log(message: String) {
  nativeLogger("Klardrop", message)
  LogBuffer.append("[Klardrop]: $message")
  CrashReporter.leaveBreadcrumb(message, BreadcrumbType.LOG)
}

public fun log(message: String, throwable: Throwable) {
  nativeLoggerException("Klardrop", message, throwable)
  LogBuffer.append("[Klardrop]: $message (${throwable.message})")
  CrashReporter.leaveBreadcrumb(message, type = BreadcrumbType.ERROR)
  CrashReporter.notify(throwable)
}

public fun log(tag: String, message: String, throwable: Throwable, fatal: Boolean = false) {
  nativeLoggerException(tag, message, throwable)
  LogBuffer.append("[$tag]: $message (${throwable.message})")
  CrashReporter.leaveBreadcrumb("[$tag]: $message", type = BreadcrumbType.ERROR)
  CrashReporter.notify(throwable, fatal)
}

public fun log(tag: String, message: String) {
  nativeLogger(tag, message)
  LogBuffer.append("[$tag]: $message")
  CrashReporter.leaveBreadcrumb("[$tag]: $message", type = BreadcrumbType.LOG)
}

// Local-only variant for failures that are part of the protocol's normal life cycle
// (peer drops, timed-out connects, BLE disconnects). The exception still goes to the
// platform logger and a breadcrumb so it shows up if a *real* crash later happens
// nearby, but we do not pay the crash-reporter noise tax for it.
internal fun logLocal(tag: String, message: String, throwable: Throwable) {
  nativeLoggerException(tag, message, throwable)
  CrashReporter.leaveBreadcrumb("[$tag]: $message", type = BreadcrumbType.LOG)
}

internal expect fun nativeLogger(tag: String, message: String)
internal expect fun nativeLoggerException(tag: String, message: String, throwable: Throwable)

/**
 * In-process ring of recent [log] lines so the debug control API can dump them
 * without scraping logcat / stdout. Lock-free: concurrent appends retry instead
 * of dropping a line, so crash reports carry every line that was logged.
 */
@OptIn(ExperimentalAtomicApi::class)
public object LogBuffer {
  private const val CAPACITY = 2000

  private val lines = AtomicReference<List<String>>(emptyList())

  public fun append(line: String) {
    while (true) {
      val current = lines.load()
      val next = if (current.size < CAPACITY) current + line else current.drop(1) + line
      if (lines.compareAndSet(current, next)) return
    }
  }

  public fun snapshot(limit: Int = CAPACITY): List<String> {
    val current = lines.load()
    if (limit >= current.size) return current
    return current.takeLast(limit)
  }

  public fun clear() {
    lines.store(emptyList())
  }
}