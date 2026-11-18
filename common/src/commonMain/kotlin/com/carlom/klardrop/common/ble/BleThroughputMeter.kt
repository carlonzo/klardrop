package com.carlom.klardrop.common.ble

import com.carlom.klardrop.common.utils.log
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

/**
 * Logs one line per transfer burst: a run of [record] calls each < [gapMs] after the previous.
 * Duration is first-record to last-record, so it excludes the first chunk's own transfer time.
 * Bursts under [minBurstBytes] are not logged. Not thread-safe; use from one pump.
 */
internal class BleThroughputMeter(
  private val label: String,
  private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
  private val gapMs: Long = 1_000,
  private val minBurstBytes: Long = 64 * 1024,
  private val sink: (String) -> Unit = { log(TAG, it) },
) {
  private var start: ComparableTimeMark? = null
  private var last: ComparableTimeMark? = null
  private var bytes = 0L

  fun record(bytes: Int) {
    val now = timeSource.markNow()
    val prev = last
    if (prev != null && now - prev >= gapMs.milliseconds) flush()
    if (start == null) start = now
    last = now
    this.bytes += bytes
  }

  /** Finalize the current burst. Idempotent. */
  fun close() = flush()

  private fun flush() {
    val s = start
    val l = last
    if (s != null && l != null && bytes >= minBurstBytes) {
      val ms = (l - s).inWholeMilliseconds.coerceAtLeast(1)
      sink("$label: $bytes B in $ms ms = ${bytes * 1000 / ms} B/s")
    }
    start = null
    last = null
    bytes = 0
  }

  private companion object {
    const val TAG = "BleThroughput"
  }
}
