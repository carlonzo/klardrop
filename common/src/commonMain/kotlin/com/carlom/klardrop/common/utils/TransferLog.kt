package com.carlom.klardrop.common.utils

import kotlin.time.TimeSource

private val processStart = TimeSource.Monotonic.markNow()

/**
 * Rolls per-chunk transfer logging up into one line per [windowMs].
 *
 * The framed chunk path used to emit four log lines per 256 KB chunk (sending/sent on the write
 * side, read/deserialized on the read side). At the throughput the bulk path now reaches that is
 * hundreds of lines a second, which costs two ways: the string building is a measurable slice of
 * the transfer's budget, and — since every `log()` also leaves a Sentry breadcrumb — a single
 * transfer flushes the 100-entry breadcrumb ring clean of everything that would explain a crash.
 *
 * Control messages (handshake, ACKs, the FILE header) are rare and still logged individually.
 * Only chunk frames come through here.
 *
 * ponytail: plain non-atomic counters. Concurrent transfers on separate connections can lose an
 * increment or race a flush; these numbers are a diagnostic, not accounting. Use atomics if a
 * report ever needs to be exact.
 */
class TransferLog(
  private val windowMs: Long = 10_000,
  private val nowMs: () -> Long = { processStart.elapsedNow().inWholeMilliseconds },
  private val sink: (String) -> Unit = { log("TransferLog", it) },
) {
  private var windowStart = nowMs()
  private var sentFrames = 0
  private var sentBytes = 0L
  private var readFrames = 0
  private var readBytes = 0L

  fun sent(bytes: Int) {
    sentFrames++
    sentBytes += bytes
    flushIfDue()
  }

  fun received(bytes: Int) {
    readFrames++
    readBytes += bytes
    flushIfDue()
  }

  /**
   * Reports and resets when the window is up. Driven by frame arrivals rather than a timer, so an
   * idle link logs nothing at all — and the tail of a transfer that ends mid-window is covered by
   * the per-transfer total `FileMessageHandler`/`FileReceivePipeline` already log on completion.
   */
  private fun flushIfDue() {
    val now = nowMs()
    val elapsed = now - windowStart
    if (elapsed < windowMs) return
    val kbPerSec = if (elapsed > 0) (sentBytes + readBytes) * 1000 / elapsed / 1024 else 0
    sink(
      "chunks in last ${elapsed}ms: sent $sentFrames (${sentBytes / 1024} KB), " +
        "received $readFrames (${readBytes / 1024} KB), $kbPerSec KB/s",
    )
    windowStart = now
    sentFrames = 0
    sentBytes = 0
    readFrames = 0
    readBytes = 0
  }
}

/** Shared across every connection: one report per window for the whole process, not per peer. */
val transferLog = TransferLog()
