package com.carlom.klardrop.common.mdns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Repro for Bugsnag macOS SIGABRT / IndexOutOfBoundsException ("index: 3, size: 3").
 *
 * DNS-SD TXT records are length-prefixed. The old [txtByteToMap] trusted the length byte
 * and called [ByteArray.copyOfRange] without checking remaining size. When a record claimed
 * 3 data bytes but the buffer only had 2 left (total size 3 including the length byte),
 * Kotlin/Native accessed index 3 on a size-3 array → exact production message, then CEH abort.
 *
 * These tests:
 *  1. Prove the **old** algorithm throws on the production-shaped buffer.
 *  2. Prove the **fixed** [txtByteToMap] does not throw and returns an empty map.
 */
class TxtByteToMapTest {

  /**
   * Exact buffer that triggered production: length prefix 3, only 2 payload bytes present.
   * Total size = 3. Old [copyOfRange](1, 4) reads past the end.
   */
  private val productionOobBuffer = byteArrayOf(
    3, // claims 3 data bytes
    'a'.code.toByte(),
    'b'.code.toByte(),
    // missing 3rd data byte
  )

  @Test
  fun oldParser_throwsIndexOutOfBounds_onProductionBuffer() {
    // Inline the pre-fix algorithm so this test stays a permanent regression pin even
    // after the production code is fixed (we cannot call the deleted implementation).
    fun oldGetTxt(array: ByteArray, firstIndex: Int): ByteArray {
      val dataLength = array[firstIndex].toInt() // signed — same as the bug
      return array.copyOfRange(firstIndex + 1, firstIndex + dataLength + 1)
    }

    fun oldTxtByteToMap(array: ByteArray): Map<String, String> {
      val list = mutableListOf<ByteArray>()
      var index = 0
      while (index < array.size) {
        val txt = oldGetTxt(array, index)
        list.add(txt)
        index += txt.size + 1
      }
      return list
        .filter { it.indexOf('='.code.toByte()) != -1 }
        .associate {
          val split = it.indexOf('='.code.toByte())
          it.copyOfRange(0, split).decodeToString() to
            it.copyOfRange(split + 1, it.size).decodeToString()
        }
    }

    assertEquals(3, productionOobBuffer.size, "fixture must be size 3 (production shape)")

    val error = assertFailsWith<IndexOutOfBoundsException> {
      oldTxtByteToMap(productionOobBuffer)
    }
    // JVM: "toIndex (4) is greater than size (3)."
    // K/N (production Bugsnag): "index: 3, size: 3"
    // Either form proves the same root cause — read past end on a size-3 buffer.
    val msg = error.message.orEmpty()
    assertTrue(
      msg.contains("size (3)") || msg.contains("size: 3") || msg.contains("length 3"),
      "expected bounds error mentioning size 3, got: $msg",
    )
  }

  @Test
  fun fixedParser_doesNotThrow_onProductionBuffer() {
    val map = txtByteToMap(productionOobBuffer)
    assertTrue(map.isEmpty(), "truncated record must be skipped, not crash: $map")
  }

  @Test
  fun wellFormedKeyValue_parses() {
    val wire = encodeTxt("dn" to "hello", "d" to "18")
    val map = txtByteToMap(wire)
    assertEquals("hello", map["dn"])
    assertEquals("18", map["d"])
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

  @Test
  fun wellFormedAfterTruncated_stillParsesPrefixOnly() {
    // Good record then a truncated tail: fixed parser keeps the good one and stops.
    val good = encodeTxt("dn" to "ok")
    val truncatedTail = byteArrayOf(5, 1, 2) // claims 5, only 2 left
    val map = txtByteToMap(good + truncatedTail)
    assertEquals("ok", map["dn"])
    assertEquals(1, map.size)
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
