package com.carlom.klardrop.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransferStatsTest {

  @Test
  fun `no stats until the window is old enough`() {
    assertNull(transferStatsOrNull(bytes = 10_000, total = 100_000, windowStartBytes = 0, elapsedMs = 1_999))
  }

  @Test
  fun `rate and eta are measured from the window start rather than from zero`() {
    // 4 MB moved in the 4s since the window anchored at 1 MB → 1 MB/s, 6 MB left → 6s.
    val stats = transferStatsOrNull(
      bytes = 5L * 1024 * 1024,
      total = 11L * 1024 * 1024,
      windowStartBytes = 1L * 1024 * 1024,
      elapsedMs = 4_000,
    )!!
    assertEquals(1024L * 1024, stats.bytesPerSecond)
    assertEquals(6L, stats.etaSeconds)
  }

  @Test
  fun `stalled transfer reports no eta instead of dividing by zero`() {
    val stats = transferStatsOrNull(bytes = 500, total = 1_000, windowStartBytes = 500, elapsedMs = 10_000)!!
    assertEquals(0L, stats.bytesPerSecond)
    assertNull(stats.etaSeconds)
  }
}
