package com.carlom.klardrop.common.mdns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Repro for Bugsnag macOS SIGABRT / IndexOutOfBoundsException ("index: 3, size: 3").
 *
 * DNS-SD TXT records are length-prefixed. The old [txtByteToMap] trusted the length byte
 * and called [ByteArray.copyOfRange] without checking remaining size. When a record claimed
 * 3 data bytes but the buffer only had 2 left (total size 3 including the length byte),
 * Kotlin/Native accessed index 3 on a size-3 array → exact production message, then CEH abort.
 */
class TxtByteToMapTest {

  @Test
  fun wellFormedKeyValue_parses() {
    val wire = encodeTxt("dn" to "hello", "d" to "18")
    val map = txtByteToMap(wire)
    assertEquals("hello", map["dn"])
    assertEquals("18", map["d"])
  }

  @Test
  fun truncatedRecord_claiming3BytesWithOnly2Remaining_doesNotThrow() {
    // Length byte 3, only 2 payload bytes available (total array size 3).
    // Old code: copyOfRange(1, 4) → IndexOutOfBoundsException index:3 size:3 on K/N.
    val truncated = byteArrayOf(3, 'a'.code.toByte(), 'b'.code.toByte())
    val map = txtByteToMap(truncated)
    assertTrue(map.isEmpty(), "truncated record must be skipped, not crash: $map")
  }

  @Test
  fun lengthPastEndOfBuffer_doesNotThrow() {
    // Claims 10 bytes of data but buffer ends immediately after length.
    val truncated = byteArrayOf(10)
    assertTrue(txtByteToMap(truncated).isEmpty())
  }

  @Test
  fun signedHighLengthByte_treatedAsUnsigned_doesNotThrow() {
    // 0x80 as signed Byte is -128; must be read as 128 unsigned and fail-soft when short.
    val truncated = byteArrayOf(0x80.toByte(), 1, 2, 3)
    assertTrue(txtByteToMap(truncated).isEmpty())
  }

  @Test
  fun emptyBuffer_returnsEmptyMap() {
    assertTrue(txtByteToMap(ByteArray(0)).isEmpty())
  }

  @Test
  fun entryWithoutEquals_isSkipped() {
    val wire = encodeTxtRaw("flagonly")
    assertTrue(txtByteToMap(wire).isEmpty())
  }

  @Test
  fun multiByteUtf8_roundTripUsesByteLength() {
    // Device names with non-ASCII must size the length prefix by UTF-8 bytes.
    val name = "café"
    val wire = encodeTxt("dn" to name)
    assertEquals(name, txtByteToMap(wire)["dn"])
  }

  /** Encode DNS-SD TXT the same way desktop Bonjour does (UTF-8 byte length). */
  private fun encodeTxt(vararg pairs: Pair<String, String>): ByteArray {
    val chunks = pairs.map { (k, v) ->
      val record = "$k=$v".encodeToByteArray()
      require(record.size <= 255)
      byteArrayOf(record.size.toByte()) + record
    }
    return chunks.fold(ByteArray(0)) { acc, c -> acc + c }
  }

  private fun encodeTxtRaw(payload: String): ByteArray {
    val record = payload.encodeToByteArray()
    return byteArrayOf(record.size.toByte()) + record
  }
}
