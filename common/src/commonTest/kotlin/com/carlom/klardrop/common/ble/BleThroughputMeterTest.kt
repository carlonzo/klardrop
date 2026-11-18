package com.carlom.klardrop.common.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class BleThroughputMeterTest {
  private val time = TestTimeSource()
  private val lines = mutableListOf<String>()
  private val meter = BleThroughputMeter("L", time, sink = { lines += it })

  private fun burst(chunks: Int, size: Int = 16 * 1024, step: Int = 10) {
    repeat(chunks) { i ->
      if (i > 0) time += step.milliseconds
      meter.record(size)
    }
  }

  @Test
  fun singleBurst() {
    burst(10)
    meter.close()
    assertEquals(listOf("L: 163840 B in 90 ms = 1820444 B/s"), lines)
  }

  @Test
  fun gapSplitsBursts() {
    burst(10)
    time += 2.seconds
    burst(10)
    meter.close()
    assertEquals(
      listOf("L: 163840 B in 90 ms = 1820444 B/s", "L: 163840 B in 90 ms = 1820444 B/s"),
      lines,
    )
  }

  @Test
  fun gapOfExactlyGapMsSplits() {
    burst(5, size = 64 * 1024)
    time += 1.seconds
    meter.record(64 * 1024)
    meter.close()
    assertEquals(2, lines.size)
  }

  @Test
  fun smallBurstNotLogged() {
    burst(2)
    meter.close()
    assertEquals(0, lines.size)
  }

  @Test
  fun closeIsIdempotent() {
    burst(10)
    meter.close()
    meter.close()
    assertEquals(1, lines.size)
  }
}
