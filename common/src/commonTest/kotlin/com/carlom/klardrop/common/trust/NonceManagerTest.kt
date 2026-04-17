package com.carlom.klardrop.common.trust

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NonceManagerTest {

  @Test
  fun firstNonceForSenderIsAccepted() = runTest {
    val manager = NonceManager()
    assertTrue(manager.isNonceValid("device-a", nonce(1)))
  }

  @Test
  fun replayedNonceFromSameSenderIsRejected() = runTest {
    val manager = NonceManager()
    val nonce = nonce(42)

    assertTrue(manager.isNonceValid("device-a", nonce))
    assertFalse(manager.isNonceValid("device-a", nonce), "Replay of the same nonce must be rejected")
  }

  @Test
  fun sameNonceFromDifferentSendersIsAccepted() = runTest {
    // Nonces are scoped per sender — two devices generating the same random value
    // must not be treated as a replay.
    val manager = NonceManager()
    val shared = nonce(99)

    assertTrue(manager.isNonceValid("device-a", shared))
    assertTrue(manager.isNonceValid("device-b", shared))
  }

  @Test
  fun differentNoncesFromSameSenderAreAllAccepted() = runTest {
    val manager = NonceManager()
    repeat(50) { i ->
      assertTrue(manager.isNonceValid("device-a", nonce(i)), "Nonce #$i should be accepted")
    }
  }

  @Test
  fun replayAfterManyDifferentNoncesIsStillRejected() = runTest {
    val manager = NonceManager()
    val target = nonce(7)
    assertTrue(manager.isNonceValid("device-a", target))

    repeat(25) { i -> manager.isNonceValid("device-a", nonce(1000 + i)) }

    assertFalse(manager.isNonceValid("device-a", target), "Original nonce should still be tracked as replay")
  }

  @Test
  fun emptyNonceBytesBehaveDeterministically() = runTest {
    // Regression guard: hex-encoding of an empty array must not collide across sessions.
    val manager = NonceManager()
    assertTrue(manager.isNonceValid("device-a", ByteArray(0)))
    assertFalse(manager.isNonceValid("device-a", ByteArray(0)), "Empty-byte nonce replay must be rejected")
  }

  private fun nonce(seed: Int): ByteArray = ByteArray(16) { (seed + it).toByte() }
}
