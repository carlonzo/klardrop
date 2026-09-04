package com.carlom.klardrop.common.utils

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
