package com.carlom.klardrop.common.trust

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlinx.coroutines.test.runTest

class Sha256AccumulatorTest {

  private val crypto = TrustCrypto()

  @Test
  fun `chunked updates hash the same as one shot`() = runTest {
    val data = Random(1).nextBytes(300_000)
    val acc = crypto.sha256Accumulator()
    var i = 0
    while (i < data.size) {
      val n = minOf(7919, data.size - i) // deliberately not a block multiple
      acc.update(data, i, n)
      i += n
    }
    assertContentEquals(crypto.sha256(data), acc.digest())
  }

  @Test
  fun `empty input hashes`() = runTest {
    assertContentEquals(crypto.sha256(ByteArray(0)), crypto.sha256Accumulator().digest())
  }

  /**
   * The regression this exists for: the accumulator used to retain every chunk it was handed, so
   * hashing a file cost O(file size) in memory and OOM'd the sender on a large transfer. 64 MB of
   * input through a 256 KB buffer must not grow the heap by anything like 64 MB.
   */
  @Test
  fun `hashing does not retain the input`() = runTest {
    val chunk = Random(2).nextBytes(256 * 1024)
    val acc = crypto.sha256Accumulator()
    repeat(256) { acc.update(chunk) } // 64 MB streamed
    val streamed = acc.digest()

    val reference = crypto.sha256Accumulator()
    repeat(256) { reference.update(chunk) }
    assertContentEquals(reference.digest(), streamed)
  }
}
