package com.carlom.klardrop.common.ble

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BleFramingTest {

  @Test
  fun chunkSmallPayloadFitsInSingleChunk() {
    val payload = byteArrayOf(1, 2, 3, 4, 5)
    val chunks = BleFraming.chunk(payload, mtu = 32)

    assertEquals(1, chunks.size)
    // length prefix (4 bytes: 0,0,0,5) + payload
    assertContentEquals(byteArrayOf(0, 0, 0, 5, 1, 2, 3, 4, 5), chunks.single())
  }

  @Test
  fun chunkSplitsLargePayloadAcrossMtuBoundaries() {
    val payload = ByteArray(100) { it.toByte() }
    val chunks = BleFraming.chunk(payload, mtu = 20)

    // 4-byte prefix + 100 bytes = 104 total → ceil(104/20) = 6 chunks, last is 4 bytes.
    assertEquals(6, chunks.size)
    assertEquals(20, chunks[0].size)
    assertEquals(20, chunks[1].size)
    assertEquals(20, chunks[2].size)
    assertEquals(20, chunks[3].size)
    assertEquals(20, chunks[4].size)
    assertEquals(4, chunks[5].size)

    val flat = chunks.fold(ByteArray(0)) { acc, c -> acc + c }
    assertContentEquals(BleFraming.withLengthPrefix(payload), flat)
  }

  @Test
  fun chunkRejectsNonPositiveMtu() {
    assertFailsWith<IllegalArgumentException> { BleFraming.chunk(ByteArray(1), mtu = 0) }
    assertFailsWith<IllegalArgumentException> { BleFraming.chunk(ByteArray(1), mtu = -5) }
  }

  @Test
  fun reassemblerReturnsEmptyListUntilFullFrameArrives() {
    val reassembler = BleReassembler()
    val payload = ByteArray(50) { (it + 7).toByte() }
    val chunks = BleFraming.chunk(payload, mtu = 8)

    val allFrames = mutableListOf<ByteArray>()
    chunks.forEachIndexed { idx, chunk ->
      val frames = reassembler.onChunk(chunk)
      if (idx < chunks.lastIndex) assertTrue(frames.isEmpty(), "frame completed early at chunk $idx")
      allFrames += frames
    }

    assertEquals(1, allFrames.size)
    assertContentEquals(payload, allFrames.single())
    assertEquals(0, reassembler.bufferedBytes)
  }

  @Test
  fun reassemblerHandlesMultipleFramesInOneChunk() {
    val reassembler = BleReassembler()
    val a = byteArrayOf(10, 11, 12)
    val b = byteArrayOf(20, 21)
    val combined = BleFraming.withLengthPrefix(a) + BleFraming.withLengthPrefix(b)

    val frames = reassembler.onChunk(combined)

    assertEquals(2, frames.size)
    assertContentEquals(a, frames[0])
    assertContentEquals(b, frames[1])
  }

  @Test
  fun reassemblerHandlesArbitrarySplitsAcrossFrames() {
    val reassembler = BleReassembler()
    val payloads = listOf(
      byteArrayOf(1),
      ByteArray(200) { (it * 3).toByte() },
      byteArrayOf(2, 3, 4),
      ByteArray(1000) { ((it xor 0x55) and 0xFF).toByte() },
    )
    val wire = payloads.fold(ByteArray(0)) { acc, p -> acc + BleFraming.withLengthPrefix(p) }

    // Split the wire bytes into randomly-sized chunks that cross frame boundaries.
    val random = Random(seed = 0xC0FFEE)
    val chunks = mutableListOf<ByteArray>()
    var offset = 0
    while (offset < wire.size) {
      val size = random.nextInt(1, 25).coerceAtMost(wire.size - offset)
      chunks += wire.copyOfRange(offset, offset + size)
      offset += size
    }

    val received = mutableListOf<ByteArray>()
    chunks.forEach { received += reassembler.onChunk(it) }

    assertEquals(payloads.size, received.size)
    payloads.forEachIndexed { i, expected ->
      assertContentEquals(expected, received[i], "frame $i mismatched")
    }
    assertEquals(0, reassembler.bufferedBytes)
  }

  @Test
  fun reassemblerIgnoresEmptyChunk() {
    val reassembler = BleReassembler()
    assertTrue(reassembler.onChunk(ByteArray(0)).isEmpty())
    // Subsequent feed with a complete frame still works.
    val payload = byteArrayOf(9, 8, 7)
    val frames = reassembler.onChunk(BleFraming.withLengthPrefix(payload))
    assertEquals(1, frames.size)
    assertContentEquals(payload, frames.single())
  }

  @Test
  fun reassemblerHandlesZeroLengthFrame() {
    val reassembler = BleReassembler()
    // Length prefix of 0 with no body → valid empty frame.
    val frames = reassembler.onChunk(byteArrayOf(0, 0, 0, 0))
    assertEquals(1, frames.size)
    assertEquals(0, frames.single().size)
  }

  @Test
  fun reassemblerClearsBufferBetweenCompletedFrames() {
    val reassembler = BleReassembler()
    val payload = ByteArray(300) { it.toByte() }
    val chunks = BleFraming.chunk(payload, mtu = 64)

    chunks.dropLast(1).forEach { reassembler.onChunk(it) }
    assertTrue(reassembler.bufferedBytes > 0, "expected partial frame buffered")

    val frames = reassembler.onChunk(chunks.last())
    assertEquals(1, frames.size)
    assertEquals(0, reassembler.bufferedBytes)
  }
}
