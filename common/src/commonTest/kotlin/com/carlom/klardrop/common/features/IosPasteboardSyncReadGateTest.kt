package com.carlom.klardrop.common.features

import kotlin.test.Test
import kotlin.test.assertEquals

class IosPasteboardSyncReadGateTest {

  @Test
  fun unchangedChangeCountNeverInvokesReadString() {
    val gate = IosPasteboardSyncReadGate()
    var reads = 0
    val readString = {
      reads++
      "hello"
    }

    assertEquals("hello", gate.readForSync(1L, hasStrings = true, readString))
    assertEquals(1, reads)

    assertEquals("hello", gate.readForSync(1L, hasStrings = true, readString))
    assertEquals(1, reads)
  }

  @Test
  fun hasStringsFalseDoesNotReadAndLaterPresenceDoes() {
    val gate = IosPasteboardSyncReadGate()
    var reads = 0
    val readString = {
      reads++
      "hello"
    }

    assertEquals("", gate.readForSync(1L, hasStrings = false, readString))
    assertEquals(0, reads)

    assertEquals("hello", gate.readForSync(2L, hasStrings = true, readString))
    assertEquals(1, reads)
  }

  @Test
  fun emptyStringWithHasStringsLatchesDecline() {
    val gate = IosPasteboardSyncReadGate()
    var reads = 0
    val readString = {
      reads++
      ""
    }

    assertEquals("", gate.readForSync(1L, hasStrings = true, readString))
    assertEquals(1, reads)

    assertEquals("", gate.readForSync(2L, hasStrings = true, readString))
    assertEquals(1, reads)
  }

  @Test
  fun allowKeepsInvokingReadStringOnNewChangeCount() {
    val gate = IosPasteboardSyncReadGate()
    var reads = 0
    val values = ArrayDeque(listOf("hello", "world"))
    val readString = {
      reads++
      values.removeFirst()
    }

    assertEquals("hello", gate.readForSync(1L, hasStrings = true, readString))
    assertEquals(1, reads)

    assertEquals("world", gate.readForSync(2L, hasStrings = true, readString))
    assertEquals(2, reads)
  }

  @Test
  fun clearDeclineResetsLatch() {
    val gate = IosPasteboardSyncReadGate()
    var reads = 0
    val readString = {
      reads++
      if (reads == 1) "" else "hello"
    }

    gate.readForSync(1L, hasStrings = true, readString)
    assertEquals(1, reads)

    gate.clearDecline()
    assertEquals("hello", gate.readForSync(2L, hasStrings = true, readString))
    assertEquals(2, reads)
  }

  @Test
  fun successfulUserReadResetsLatch() {
    val gate = IosPasteboardSyncReadGate()
    var reads = 0

    gate.readForSync(1L, hasStrings = true) {
      reads++
      ""
    }
    assertEquals(1, reads)

    assertEquals("pasted", gate.recordUserRead(2L, "pasted"))

    assertEquals("from sync", gate.readForSync(3L, hasStrings = true) {
      reads++
      "from sync"
    })
    assertEquals(2, reads)
  }
}
