package com.carlom.klardrop.common.trust

import FakeLocalPropertiesRepository
import com.carlom.klardrop.common.communication.message.TrustPairingRequest
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.utils.Clock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the full TrustManager lifecycle: key initialization, pairing request/response
 * creation, finalization, trust storage, and signed-message sign/verify with replay and
 * skew defenses.
 *
 * The session lookup in TrustManager.finalizePairing keys on response.deviceId, which is
 * always the sender's shortDeviceId (first 8 chars of deviceId). Device IDs in this test
 * are therefore 8 chars so the target-id we pass into createPairingRequest matches the
 * peer's shortDeviceId in their response.
 */
class TrustManagerTest {

  private val clock = Clock()

  private val aliceId = "alice001"
  private val bobId = "bob00002"

  private fun TestScope.newManager(deviceId: String): Pair<TrustManager, InMemoryTrustStorage> {
    val storage = InMemoryTrustStorage()
    val provider = CurrentDeviceProvider(FakeLocalPropertiesRepository(deviceId))
    val manager = TrustManager(
      crypto = TrustCrypto(),
      storage = storage,
      clock = clock,
      currentDeviceProvider = provider
    )
    return manager to storage
  }

  @Test
  fun initializeGeneratesAndPersistsPrivateKey() = runTest {
    val (manager, storage) = newManager(aliceId)
    assertNull(storage.getDevicePrivateKey())

    manager.initialize()

    val stored = storage.getDevicePrivateKey()
    assertNotNull(stored, "Device private key must be persisted after initialize()")
    assertTrue(stored.isNotEmpty(), "Private key bytes should not be empty")
  }

  @Test
  fun createPairingRequestIncludesDeviceIdentityAndPublicKeys() = runTest {
    val (manager, _) = newManager(aliceId)

    val result = manager.createPairingRequest(bobId)

    assertTrue(result.isSuccess)
    val request = result.getOrThrow()
    assertEquals(aliceId, request.deviceId, "Request must carry our shortDeviceId")
    assertTrue(request.ecdhPublicKey.isNotEmpty())
    assertTrue(request.ecdsaPublicKey.isNotEmpty())
    assertTrue(request.timestamp > 0)
  }

  @Test
  fun fullPairingRoundTripEstablishesMutualTrust() = runTest {
    val (alice, aliceStorage) = newManager(aliceId)
    val (bob, bobStorage) = newManager(bobId)

    val request = alice.createPairingRequest(bobId).getOrThrow()

    val response = bob.createPairingAcceptance(request).getOrThrow()
    assertTrue(response.accepted)
    assertTrue(bobStorage.isTrusted(request.deviceId), "Bob should now trust Alice")

    alice.finalizePairing(response)
    assertTrue(aliceStorage.isTrusted(response.deviceId), "Alice should now trust Bob")
  }

  @Test
  fun rejectedPairingDoesNotEstablishTrust() = runTest {
    val (alice, aliceStorage) = newManager(aliceId)
    val (bob, _) = newManager(bobId)

    alice.createPairingRequest(bobId).getOrThrow()
    val rejection = bob.createPairingRejection(aliceId)
    assertFalse(rejection.accepted)

    alice.finalizePairing(rejection)
    assertFalse(aliceStorage.isTrusted(rejection.deviceId), "Trust must not be stored on rejection")
  }

  @Test
  fun pairingRequestWithStaleTimestampIsRejected() = runTest {
    val (bob, _) = newManager(bobId)
    bob.setPairingApprovalCallback(NoopPairingApprovalCallback)

    // 10 minutes old — past the 5-minute MAX_TIME_DIFF window
    val stale = syntheticRequest(timestamp = clock.currentTimeMillis() - 10 * 60_000)

    val decision = bob.processPairingRequest(stale, senderAddress = "192.168.1.1")
    assertNull(decision, "Stale pairing request must not produce a decision")
  }

  @Test
  fun pairingRequestWithFreshTimestampProducesDecisionWhenCallbackRegistered() = runTest {
    val (bob, _) = newManager(bobId)
    bob.setPairingApprovalCallback(NoopPairingApprovalCallback)

    val (alice, _) = newManager(aliceId)
    val request = alice.createPairingRequest(bobId).getOrThrow()

    val decision = bob.processPairingRequest(request, senderAddress = "192.168.1.50")
    assertNotNull(decision)
    assertEquals(request.deviceId, decision.deviceId)
    assertEquals(request.deviceName, decision.deviceName)
  }

  @Test
  fun pairingRequestWithoutCallbackReturnsNullDecision() = runTest {
    val (bob, _) = newManager(bobId)

    val (alice, _) = newManager(aliceId)
    val request = alice.createPairingRequest(bobId).getOrThrow()

    val decision = bob.processPairingRequest(request, senderAddress = "192.168.1.50")
    assertNull(decision, "Without an approval callback no decision object can be produced")
  }

  @Test
  fun signedMessageVerifiesForRecipientThatTrustsSender() = runTest {
    val (alice, _) = newManager(aliceId)
    val (bob, bobStorage) = newManager(bobId)
    alice.initialize()

    val aliceRequest = alice.createPairingRequest(bobId).getOrThrow()
    bob.createPairingAcceptance(aliceRequest).getOrThrow()

    val signed = alice.signMessage("secret payload".encodeToByteArray())
    assertNotNull(signed, "Signing must succeed after initialization")
    assertEquals(aliceId, signed.senderId)

    assertTrue(bob.verifyMessage(signed), "Bob must accept Alice's authentic signed message")
    assertNotNull(bobStorage.getECDSAKey(aliceId))
  }

  @Test
  fun signedMessageFromUnknownSenderIsRejected() = runTest {
    val (alice, _) = newManager(aliceId)
    val (bob, _) = newManager(bobId)
    alice.initialize()

    val signed = alice.signMessage("payload".encodeToByteArray())
    assertNotNull(signed)
    assertFalse(bob.verifyMessage(signed), "Verification must fail when sender's key is not stored")
  }

  @Test
  fun replayedSignedMessageIsRejected() = runTest {
    val (alice, _) = newManager(aliceId)
    val (bob, _) = newManager(bobId)
    alice.initialize()
    bob.createPairingAcceptance(alice.createPairingRequest(bobId).getOrThrow()).getOrThrow()

    val signed = alice.signMessage("one-shot payload".encodeToByteArray())
    assertNotNull(signed)

    assertTrue(bob.verifyMessage(signed), "First delivery should succeed")
    assertFalse(bob.verifyMessage(signed), "Replay of the same nonce must be rejected")
  }

  @Test
  fun signedMessageWithTamperedPayloadFailsVerification() = runTest {
    val (alice, _) = newManager(aliceId)
    val (bob, _) = newManager(bobId)
    alice.initialize()
    bob.createPairingAcceptance(alice.createPairingRequest(bobId).getOrThrow()).getOrThrow()

    val signed = alice.signMessage("original".encodeToByteArray())
    assertNotNull(signed)
    val tampered = signed.copy(payload = "modified".encodeToByteArray())

    assertFalse(bob.verifyMessage(tampered), "Modifying the payload must invalidate the signature")
  }

  @Test
  fun removeTrustRevokesPreviouslyPairedDevice() = runTest {
    val (alice, aliceStorage) = newManager(aliceId)
    val (bob, _) = newManager(bobId)

    val response = bob.createPairingAcceptance(alice.createPairingRequest(bobId).getOrThrow()).getOrThrow()
    alice.finalizePairing(response)
    assertTrue(aliceStorage.isTrusted(response.deviceId))

    alice.removeTrust(response.deviceId)
    assertFalse(alice.isTrusted(response.deviceId))
  }

  private fun syntheticRequest(timestamp: Long) = TrustPairingRequest(
    deviceId = "attacker",
    deviceName = "Attacker",
    ecdhPublicKey = ByteArray(0),
    ecdsaPublicKey = ByteArray(0),
    timestamp = timestamp,
    deviceType = "MOBILE",
    appVersion = "1.0.0"
  )
}

private object NoopPairingApprovalCallback : PairingApprovalCallback {
  override fun onPairingRequested(
    deviceId: String,
    deviceName: String,
    deviceType: String,
    onAccept: suspend () -> Unit,
    onReject: suspend () -> Unit
  ) {
    // no-op
  }
}
