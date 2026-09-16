package com.carlom.klardrop.common.utils

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the [LogBuffer.snapshot] limit contract that [com.klardrop.common.CrashReporter.reportUserFeedback]
 * relies on: feedback attaches the most recent N lines, so the tail must be capped *and*
 * most-recent-first-safe (oldest lines dropped, newest kept).
 */
class LogBufferTest {

  @Test
  fun snapshotWithLimitKeepsOnlyTheMostRecentLines() {
    LogBuffer.clear()
    try {
      repeat(10) { LogBuffer.append("line $it") }
      assertEquals(listOf("line 7", "line 8", "line 9"), LogBuffer.snapshot(3))
    } finally {
      LogBuffer.clear()
    }
  }

  @Test
  fun snapshotWithoutLimitReturnsEverything() {
    LogBuffer.clear()
    try {
      repeat(5) { LogBuffer.append("line $it") }
      assertEquals(5, LogBuffer.snapshot().size)
    } finally {
      LogBuffer.clear()
    }
  }
}
