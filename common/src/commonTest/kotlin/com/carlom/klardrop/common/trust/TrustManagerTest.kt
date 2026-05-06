package com.carlom.klardrop.common.trust

import FakeLocalPropertiesRepository
import com.carlom.klardrop.common.communication.message.TrustPairingRequest
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.utils.Clock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
    return newManager(deviceId, storage)
  }

  private fun TestScope.newManager(
    deviceId: String,
    storage: InMemoryTrustStorage
  ): Pair<TrustManager, InMemoryTrustStorage> {
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
    assertNull(storage.getDevicePublicKey())

    manager.initialize()

    val storedPrivate = storage.getDevicePrivateKey()
    assertNotNull(storedPrivate, "Device private key must be persisted after initialize()")
    assertTrue(storedPrivate.isNotEmpty(), "Private key bytes should not be empty")

    val storedPublic = storage.getDevicePublicKey()
    assertNotNull(storedPublic, "Device public key must be persisted after initialize()")
    assertTrue(storedPublic.isNotEmpty(), "Public key bytes should not be empty")
  }

  @Test
  fun deviceIdentityPersistsAcrossRestartSoSignedMessagesStillVerify() = runTest {
    // Shared backing store represents Alice's on-disk identity surviving the restart.
    val aliceStorage = InMemoryTrustStorage()
    val (alice1, _) = newManager(aliceId, aliceStorage)
    val (bob, _) = newManager(bobId)

    // First run: pair so Bob caches Alice's public key.
    bob.createPairingAcceptance(alice1.createPairingRequest(bobId).getOrThrow()).getOrThrow()

    val privateBefore = aliceStorage.getDevicePrivateKey()!!.copyOf()
    val publicBefore = aliceStorage.getDevicePublicKey()!!.copyOf()

    // Simulate app restart: brand-new TrustManager pointed at the same storage.
    val (alice2, _) = newManager(aliceId, aliceStorage)
    alice2.initialize()

    val privateAfter = aliceStorage.getDevicePrivateKey()!!
    val publicAfter = aliceStorage.getDevicePublicKey()!!
    assertTrue(privateBefore.contentEquals(privateAfter), "Private key must NOT rotate on restart")
    assertTrue(publicBefore.contentEquals(publicAfter), "Public key must NOT rotate on restart")

    // Bob's cached public key for Alice must still verify Alice's new signatures.
    val signed = alice2.signMessage("post-restart payload".encodeToByteArray())
    assertNotNull(signed, "Signing must succeed after restart")
    assertTrue(bob.verifyMessage(signed), "Bob must still trust Alice's signature after Alice restarts")
  }

  @Test
  fun signWithDeviceKeyRoundtripsAgainstStoredPublicKey() = runTest {
    val (manager, storage) = newManager(aliceId)
    val crypto = TrustCrypto()
    manager.initialize()

    val data = "round-trip data".encodeToByteArray()
    val signature = storage.signWithDeviceKey(data, crypto)
    assertNotNull(signature, "signWithDeviceKey must return bytes when an identity exists")

    val publicKey = storage.getDevicePublicKey()!!
    assertTrue(crypto.verifyECDSA(publicKey, data, signature), "Signature must verify against the stored public key")
  }

  @Test
  fun signMessageWorksWithoutExplicitInitialize() = runTest {
    val (alice, aliceStorage) = newManager(aliceId)
    // Skip explicit initialize() — signMessage must lazily set up the identity.
    val signed = alice.signMessage("hello".encodeToByteArray())
    assertNotNull(signed, "signMessage must lazily initialize and produce a TrustedMessage")
    assertNotNull(aliceStorage.getDevicePrivateKey(), "Lazy initialize must persist the private key")
    assertNotNull(aliceStorage.getDevicePublicKey(), "Lazy initialize must persist the public key")
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

  /**
   * Regression test: signMessage was returning null on a fresh TrustManager because the
   * device's ECDSA keys are only loaded inside initialize(), and nothing on the normal
   * send-message path ever called initialize() — only the pairing flows did. After app
   * restart this caused every TRUSTED_MESSAGE wrap to fall back to "send unsigned", which
   * the receiver's security gate then rejected with `unsigned message from trusted device`.
   * signMessage must lazily initialize so this works without explicit setup.
   */
  @Test
  fun signMessageWorksWithoutExplicitInitialize() = runTest {
    val (manager, storage) = newManager(aliceId)
    assertNull(storage.getDevicePrivateKey(), "precondition: keys not yet loaded")

    val signed = manager.signMessage("hello".encodeToByteArray())
    assertNotNull(signed, "signMessage must lazily initialize and succeed on first call")
    assertEquals(aliceId, signed.senderId)

    assertNotNull(storage.getDevicePrivateKey(), "lazy initialize must persist the device private key")
  }

  /**
   * Regression test: across an app restart (TrustManager re-instantiated against the same
   * persistent storage), the device's signing identity must be preserved. Pre-fix,
   * initialize() saw an existing private key, regenerated a fresh keypair anyway, and
   * overwrote the stored private key. The peer (Bob) still held Alice's *original*
   * public key from pairing, so every signed message after restart failed verification.
   */
  @Test
  fun deviceIdentityPersistsAcrossRestartSoSignedMessagesStillVerify() = runTest {
    val sharedAliceStorage = InMemoryTrustStorage()
    val (bob, _) = newManager(bobId)

    val aliceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(aliceId))
    val aliceBeforeRestart = TrustManager(
      crypto = TrustCrypto(),
      storage = sharedAliceStorage,
      clock = clock,
      currentDeviceProvider = aliceProvider,
    )

    // Pair: Bob now holds Alice's ECDSA public key from the time of pairing.
    val request = aliceBeforeRestart.createPairingRequest(bobId).getOrThrow()
    val response = bob.createPairingAcceptance(request).getOrThrow()
    aliceBeforeRestart.finalizePairing(response)

    // Sanity check: signing works pre-restart.
    val firstSigned = assertNotNull(aliceBeforeRestart.signMessage("pre-restart".encodeToByteArray()))
    assertTrue(bob.verifyMessage(firstSigned), "precondition: verification works before restart")

    // Simulate restart: brand-new TrustManager pointing at the same persistent storage.
    val aliceAfterRestart = TrustManager(
      crypto = TrustCrypto(),
      storage = sharedAliceStorage,
      clock = clock,
      currentDeviceProvider = aliceProvider,
    )

    val secondSigned = assertNotNull(
      aliceAfterRestart.signMessage("post-restart".encodeToByteArray()),
      "signing must still work after a TrustManager restart",
    )
    assertTrue(
      bob.verifyMessage(secondSigned),
      "Bob (using the public key Alice published at pairing time) MUST still verify Alice's signature after restart",
    )
  }

  /**
   * Regression test for the upgrade-from-broken-build migration. Pre-fix, every restart
   * rotated this device's signing keypair (without persisting the public half), so any
   * peer that paired with us cached a public key that no longer matches our current
   * private key. When the user upgrades to the persistence fix, our side will load the
   * legacy private key, find no persisted public key, and have to generate ONE more
   * fresh keypair — which means the peer's cached public key for us is now stale and
   * every TrustedMessage we sign will fail verification on their side. The only safe
   * recovery is to wipe local trust so the user re-pairs cleanly. The peer eventually
   * does the same on their next launch, or unpairs manually after seeing the failures.
   */
  @Test
  fun legacyStorageMigrationClearsLocalTrustToForceRePair() = runTest {
    val storage = InMemoryTrustStorage()
    // Simulate legacy state: only private key persisted (no public), AND a previously
    // paired peer's ECDSA + ECDH keys cached locally.
    storage.storeDevicePrivateKey(byteArrayOf(0x01, 0x02, 0x03))
    storage.storeECDSAKey("peer-id", byteArrayOf(0x09))
    storage.storeTrustedDevice("peer-id", byteArrayOf(0x0A))
    assertTrue(storage.isTrusted("peer-id"), "precondition: peer is trusted")

    val provider = CurrentDeviceProvider(FakeLocalPropertiesRepository(aliceId))
    val manager = TrustManager(
      crypto = TrustCrypto(),
      storage = storage,
      clock = clock,
      currentDeviceProvider = provider,
    )

    manager.initialize()

    assertFalse(
      storage.isTrusted("peer-id"),
      "Legacy migration must wipe local trust because the peer's cached public key for us is now stale",
    )
    assertNotNull(storage.getDevicePrivateKey(), "fresh private key must be persisted")
    assertNotNull(storage.getDevicePublicKey(), "fresh public key must be persisted alongside")
  }

  /**
   * Regression test for option-3 (HMAC over file chunks). Pairing now derives an ECDH
   * shared secret on both sides and persists it; macKeyFor() turns that into a per-pair
   * HMAC key via HKDF-SHA256. Both sides must arrive at the SAME key — that's the whole
   * point of ECDH. Without this round-trip, sender and receiver derive different keys,
   * MAC verification fails 100% of the time and the chunked file path falls back to the
   * (slower) content-hash binding silently.
   */
  @Test
  fun pairingRoundTripPersistsMatchingSharedSecretOnBothSides() = runTest {
    val (alice, _) = newManager(aliceId)
    val (bob, _) = newManager(bobId)

    val request = alice.createPairingRequest(bobId).getOrThrow()
    val response = bob.createPairingAcceptance(request).getOrThrow()
    alice.finalizePairing(response)

    val aliceMacKey = alice.macKeyFor(bobId)
    val bobMacKey = bob.macKeyFor(aliceId)

    assertNotNull(aliceMacKey, "Alice must have a persisted shared secret with Bob after finalize")
    assertNotNull(bobMacKey, "Bob must have a persisted shared secret with Alice after createPairingAcceptance")
    assertContentEquals(
      aliceMacKey, bobMacKey,
      "Both sides must derive the SAME HMAC key from their (matching) ECDH shared secrets",
    )
  }

  /**
   * Regression test: a TrustManager with no persisted shared secret (fresh install or
   * legacy pairing pre-dating the secret persistence) returns null from macKeyFor and
   * the chunk-MAC helpers. Callers fall back to the content-hash path.
   */
  @Test
  fun macKeyForReturnsNullWhenPeerHasNoSharedSecret() = runTest {
    val (alice, _) = newManager(aliceId)
    // Note: no pairing performed.
    assertNull(alice.macKeyFor(bobId))
    assertNull(
      alice.computeChunkMac(bobId, fileMessageId = 1, seq = 0, isLast = false, data = byteArrayOf(1, 2, 3)),
    )
    assertEquals(
      false,
      alice.verifyChunkMac(bobId, fileMessageId = 1, seq = 0, isLast = false, data = byteArrayOf(1, 2, 3), tag = ByteArray(32)),
    )
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
