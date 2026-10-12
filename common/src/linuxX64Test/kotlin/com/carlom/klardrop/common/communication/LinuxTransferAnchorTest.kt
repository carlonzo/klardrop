package com.carlom.klardrop.common.communication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class LinuxTransferAnchorTest {

  @Test
  fun inhibitorSpansOverlappingTransfers() {
    val spawned = mutableListOf<Int>()
    val killed = mutableListOf<Int>()
    var nextPid = 1000
    val anchor = LinuxTransferAnchor(
      spawnInhibitor = { (++nextPid).also { spawned.add(it) } },
      killInhibitor = { killed.add(it) },
    )

    anchor.begin("a", "file-a", TransferAnchor.Direction.OUTGOING)
    anchor.begin("b", "file-b", TransferAnchor.Direction.INCOMING)
    anchor.begin("a", "file-a", TransferAnchor.Direction.OUTGOING) // duplicate begin: no-op
    assertEquals(listOf(1001), spawned, "one inhibitor for overlapping transfers")

    anchor.end("a")
    assertTrue(killed.isEmpty(), "inhibitor must be held until the last transfer ends")

    anchor.end("unknown") // unknown id: no-op
    assertTrue(killed.isEmpty())

    anchor.end("b")
    assertEquals(listOf(1001), killed, "inhibitor released with the last transfer")

    anchor.end("b") // double end: no-op
    assertEquals(listOf(1001), killed)
  }

  @Test
  fun spawnFailureDegradesToNoop() {
    val anchor = LinuxTransferAnchor(
      spawnInhibitor = { null },
      killInhibitor = { fail("must not kill an inhibitor that was never spawned") },
    )
    // Must not throw: the transfer proceeds unanchored.
    anchor.begin("a", "file-a", TransferAnchor.Direction.OUTGOING)
    anchor.end("a")
  }
}
