package com.carlom.klardrop.common.ble

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BlePsmTest {
  @Test
  fun roundTrips() {
    for (psm in listOf(0x0080, 0x00FF, 0x1001, 0xFFFF)) assertEquals(psm, BlePsm.decode(BlePsm.encode(psm)))
  }

  @Test
  fun littleEndian() {
    assertContentEquals(byteArrayOf(0x81.toByte(), 0x00), BlePsm.encode(0x0081))
  }

  @Test
  fun decodeRejectsBadInput() {
    assertNull(BlePsm.decode(null))
    assertNull(BlePsm.decode(ByteArray(0)))
    assertNull(BlePsm.decode(ByteArray(3)))
    assertNull(BlePsm.decode(ByteArray(2)))
  }

  @Test
  fun encodeRejectsOutOfRange() {
    assertFailsWith<IllegalArgumentException> { BlePsm.encode(0) }
    assertFailsWith<IllegalArgumentException> { BlePsm.encode(0x10000) }
  }
}
