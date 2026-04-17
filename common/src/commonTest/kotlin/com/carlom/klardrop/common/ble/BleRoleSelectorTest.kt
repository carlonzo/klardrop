package com.carlom.klardrop.common.ble

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleRoleSelectorTest {

  @Test
  fun smallerIdInitiates() {
    assertTrue(BleRoleSelector.shouldInitiate("aaaa1234", "bbbb5678"))
  }

  @Test
  fun largerIdWaits() {
    assertFalse(BleRoleSelector.shouldInitiate("zzzz9999", "aaaa0000"))
  }

  @Test
  fun decisionIsSymmetric() {
    val a = "aaaabbbb"
    val b = "zzzz0000"
    assertTrue(BleRoleSelector.shouldInitiate(a, b))
    assertFalse(BleRoleSelector.shouldInitiate(b, a))
  }

  @Test
  fun lexicographicOrderHandlesMixedCase() {
    // Uppercase letters sort before lowercase in ASCII; role selection must reflect that
    // consistently so both sides still agree.
    assertTrue(BleRoleSelector.shouldInitiate("ABCDEFGH", "abcdefgh"))
    assertFalse(BleRoleSelector.shouldInitiate("abcdefgh", "ABCDEFGH"))
  }

  @Test
  fun identicalIdsAreRejected() {
    assertFailsWith<IllegalArgumentException> {
      BleRoleSelector.shouldInitiate("samesame", "samesame")
    }
  }
}
