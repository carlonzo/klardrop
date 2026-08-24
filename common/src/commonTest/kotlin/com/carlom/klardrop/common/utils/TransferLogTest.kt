package com.carlom.klardrop.common.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransferLogTest {

  private class Fixture(windowMs: Long = 10_000) {
    var now = 0L
    val lines = mutableListOf<String>()
    val log = TransferLog(windowMs = windowMs, nowMs = { now }, sink = { lines += it })
  }

  @Test
  fun `stays silent inside the window`() {
    val f = Fixture()
    repeat(1000) { f.log.sent(256 * 1024) }
    f.now = 9_999
    f.log.received(256 * 1024)
    assertEquals(emptyList(), f.lines)
  }

  @Test
  fun `reports once per window and then resets`() {
    val f = Fixture()
    repeat(4) { f.log.sent(256 * 1024) }
    f.log.received(512 * 1024)
    f.now = 10_000
    f.log.sent(256 * 1024) // 5th sent frame trips the flush

    assertEquals(1, f.lines.size)
    val line = f.lines.single()
    assertTrue("sent 5 (1280 KB)" in line, line)
    assertTrue("received 1 (512 KB)" in line, line)
    assertTrue("179 KB/s" in line, line) // 1792 KB over 10s

    // Window reset: the counters do not carry over.
    f.now = 20_000
    f.log.received(1024)
    assertEquals(2, f.lines.size)
    assertTrue("sent 0 (0 KB)" in f.lines[1], f.lines[1])
    assertTrue("received 1 (1 KB)" in f.lines[1], f.lines[1])
  }

  @Test
  fun `an idle link logs nothing`() {
    val f = Fixture()
    f.now = 1_000_000
    assertEquals(emptyList(), f.lines)
  }
}
