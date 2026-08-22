package com.carlom.klardrop.common.communication

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class BulkCipherTest {

  /**
   * Two contexts keyed like the two ends of a session: our encodeKey is the peer's decodeKey.
   *
   * [seed] must differ per test. Counters — and therefore GCM nonces — restart at 0 for every new
   * session, so tests sharing key material would re-encrypt under the same (key, nonce) and the
   * JDK provider rightly refuses. Real sessions can't collide: the keys are ephemeral per handshake.
   */
  private suspend fun pair(seed: Int): Pair<BulkCipher, BulkCipher> {
    val a = Random(seed).nextBytes(32)
    val b = Random(seed + 1000).nextBytes(32)
    return BulkCipher.fromSessionKeys(encodeKey = a, decodeKey = b) to
      BulkCipher.fromSessionKeys(encodeKey = b, decodeKey = a)
  }

  private fun frame(sealed: BulkCipher.SealedChunk) = sealed.header + sealed.ciphertext

  @Test
  fun `chunk survives a round trip with its metadata`() = runTest {
    val (sender, receiver) = pair(34)
    val body = Random(1).nextBytes(4096)

    val opened = receiver.open(frame(sender.seal(fileMessageId = 42, seq = 3, isLast = true, body = body)))

    assertContentEquals(body, opened.data)
    assertEquals(42, opened.fileMessageId)
    assertEquals(3, opened.seq)
    assertTrue(opened.isLast)
  }

  @Test
  fun `a short final chunk sends only the bytes actually read`() = runTest {
    val (sender, receiver) = pair(51)
    val buffer = Random(2).nextBytes(4096) // reused read buffer, only partly filled

    val opened = receiver.open(frame(sender.seal(1, 0, true, buffer, bodyLength = 100)))

    assertEquals(100, opened.data.size)
    assertContentEquals(buffer.copyOf(100), opened.data)
  }

  @Test
  fun `frames decrypt in order without reusing a nonce`() = runTest {
    val (sender, receiver) = pair(68)
    val frames = (0 until 4).map { frame(sender.seal(1, it, it == 3, byteArrayOf(it.toByte()))) }

    // Distinct counters mean distinct nonces — the property that keeps GCM safe here.
    assertEquals(4, frames.map { it.copyOf(8).toList() }.toSet().size)
    frames.forEachIndexed { i, f -> assertEquals(i, receiver.open(f).seq) }
  }

  @Test
  fun `tampering with the body fails the tag`() = runTest {
    val (sender, receiver) = pair(85)
    val f = frame(sender.seal(1, 0, false, Random(3).nextBytes(512)))
    f[f.size - 1] = (f[f.size - 1] + 1).toByte()

    assertFailsWith<Throwable> { receiver.open(f) }
  }

  @Test
  fun `tampering with the authenticated metadata fails the tag`() = runTest {
    val (sender, receiver) = pair(102)
    val f = frame(sender.seal(1, 0, isLast = false, body = Random(4).nextBytes(512)))
    f[16] = 1 // flip isLast, which rides in the AAD

    assertFailsWith<Throwable> { receiver.open(f) }
  }

  @Test
  fun `a key from another session cannot open our frames`() = runTest {
    val (sender, _) = pair(119)
    val stranger = BulkCipher.fromSessionKeys(Random(9).nextBytes(32), Random(10).nextBytes(32))

    assertFailsWith<Throwable> { stranger.open(frame(sender.seal(1, 0, false, Random(5).nextBytes(512)))) }
  }
}
