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
  fun ukey2BindingSignatureVerifiesViaPeerManager() = runTest {
    // Alice signs the UKEY2 verification string; Bob, holding Alice's stored ECDSA public key,
    // verifies it — the basis of the channel-identity binding.
    val (alice, aliceStorage) = newManager(aliceId)
    alice.initialize()
    val (bob, bobStorage) = newManager(bobId)
    bob.initialize()
    bobStorage.storeECDSAKey(aliceId, aliceStorage.getDevicePublicKey()!!)

    val verificationString = ByteArray(32) { it.toByte() }
    val signature = alice.signUkey2Binding(verificationString)
    assertNotNull(signature, "signUkey2Binding must return bytes when an identity exists")

    assertTrue(
      bob.verifyUkey2Binding(aliceId, verificationString, signature),
      "Peer's binding signature must verify against the stored ECDSA key",
    )
    // A different verification string must not verify (no replay across sessions).
    assertFalse(bob.verifyUkey2Binding(aliceId, ByteArray(32) { (it + 1).toByte() }, signature))
    // Unknown peer (no stored key) returns false rather than throwing.
    assertFalse(bob.verifyUkey2Binding("nobody00", verificationString, signature))
  }

  @Test
  fun secondInitializeReusesExistingKeypair() = runTest {
    val storage = InMemoryTrustStorage()
    val (manager1, _) = newManager(aliceId, storage)
    manager1.initialize()
    val publicAfterFirst = storage.getDevicePublicKey()!!.copyOf()

    val (manager2, _) = newManager(aliceId, storage)
    manager2.initialize()
    val publicAfterSecond = storage.getDevicePublicKey()!!

    assertTrue(
      publicAfterFirst.contentEquals(publicAfterSecond),
      "Second initialize() must not regenerate the keypair"
    )
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

  @Test
  fun revocationMessageRoundTripsAndIsAppliedByRecipient() = runTest {
    // Alice and Bob pair, then Alice forgets Bob. Bob (still paired) should accept
    // Alice's signed revocation and remove the local trust entry.
    val (alice, _) = newManager(aliceId)
    val (bob, bobStorage) = newManager(bobId)
    val response = bob.createPairingAcceptance(alice.createPairingRequest(bobId).getOrThrow()).getOrThrow()
    alice.finalizePairing(response)
    assertTrue(bobStorage.isTrusted(aliceId), "precondition: Bob trusts Alice")

    val revocation = assertNotNull(alice.createRevocationMessage(bobId, reason = "user_unpaired"))
    assertEquals(aliceId, revocation.senderId)
    assertEquals(bobId, revocation.targetDeviceId)

    assertTrue(bob.verifyRevocationMessage(revocation), "Bob must verify a properly-signed revocation from Alice")
    bob.applyVerifiedRevocation(revocation)
    assertFalse(bobStorage.isTrusted(aliceId), "Bob's local trust entry for Alice must be removed")
  }

  @Test
  fun revocationFromUnknownSenderFailsVerification() = runTest {
    // Bob has never paired with Alice — he doesn't hold her ECDSA key, so a revocation
    // claiming to be from her must fail verification and not silently revoke anything.
    val (alice, _) = newManager(aliceId)
    val (bob, _) = newManager(bobId)

    val revocation = assertNotNull(alice.createRevocationMessage(bobId))
    assertFalse(bob.verifyRevocationMessage(revocation), "Unknown sender must fail verification")
  }

  @Test
  fun revocationWithTamperedTargetIdFailsVerification() = runTest {
    val (alice, _) = newManager(aliceId)
    val (bob, _) = newManager(bobId)
    bob.createPairingAcceptance(alice.createPairingRequest(bobId).getOrThrow()).getOrThrow()

    val original = assertNotNull(alice.createRevocationMessage(bobId))
    val tampered = original.copy(targetDeviceId = "someone_else")
    assertFalse(
      bob.verifyRevocationMessage(tampered),
      "Modifying the signed targetDeviceId field must invalidate the signature",
    )
  }

  @Test
  fun revocationReplayIsRejected() = runTest {
    val (alice, _) = newManager(aliceId)
    val (bob, _) = newManager(bobId)
    bob.createPairingAcceptance(alice.createPairingRequest(bobId).getOrThrow()).getOrThrow()

    val revocation = assertNotNull(alice.createRevocationMessage(bobId))
    assertTrue(bob.verifyRevocationMessage(revocation), "first delivery should succeed")
    assertFalse(bob.verifyRevocationMessage(revocation), "replay of the same nonce must be rejected")
  }

  /**
   * Regression for the reported "trusted devices can't share after pairing" bug.
   *
   * The device's private and public key halves live in separate stores with different
   * lifetimes (on desktop: OS keychain vs. a file under the app dir). When they desync,
   * ensureDeviceKey used to blindly reuse the mismatched pair: it advertised the stored
   * public key at pairing time but signed with the non-matching private key, so the peer
   * rejected EVERY signed message ("signature verification failed") forever — and
   * re-pairing didn't help because the same broken pair kept coming back.
   *
   * Here we seed Alice's storage with mismatched halves (private from one keypair, public
   * from another), then run the normal pair + sign + verify flow. Pre-fix Bob rejects the
   * signature; post-fix ensureDeviceKey detects the mismatch, regenerates a consistent
   * pair, and Bob verifies.
   */
  @Test
  fun desyncedDeviceKeyHalvesAreHealedSoSignedMessagesVerify() = runTest {
    val crypto = TrustCrypto()
    val aliceStorage = InMemoryTrustStorage()

    // Two unrelated keypairs; store the private half of one and the public half of the other.
    val pairA = crypto.generateECDSAKeyPair()
    val pairB = crypto.generateECDSAKeyPair()
    aliceStorage.storeDevicePrivateKey(pairA.privateKey.data)
    aliceStorage.storeDevicePublicKey(pairB.publicKey.data)

    val (alice, _) = newManager(aliceId, aliceStorage)
    val (bob, _) = newManager(bobId)

    // Pairing advertises whatever ensureDeviceKey returns; Bob stores that as Alice's key.
    val request = alice.createPairingRequest(bobId).getOrThrow()
    bob.createPairingAcceptance(request).getOrThrow()

    val signed = assertNotNull(alice.signMessage("trusted text".encodeToByteArray()))
    assertTrue(
      bob.verifyMessage(signed),
      "Bob must verify Alice's signature: the advertised public key MUST match the signing key",
    )
  }

  /**
   * Companion to the test above at the storage layer: ensureDeviceKey must return a public
   * key that actually corresponds to what signWithDeviceKey signs with, even when the stored
   * halves were left mismatched.
   */
  @Test
  fun ensureDeviceKeyReturnsPublicMatchingTheSigningPrivateKey() = runTest {
    val crypto = TrustCrypto()
    val storage = InMemoryTrustStorage()
    storage.storeDevicePrivateKey(crypto.generateECDSAKeyPair().privateKey.data)
    storage.storeDevicePublicKey(crypto.generateECDSAKeyPair().publicKey.data) // mismatched

    val advertisedPublic = storage.ensureDeviceKey(crypto).data

    val data = "probe".encodeToByteArray()
    val signature = assertNotNull(storage.signWithDeviceKey(data, crypto))
    assertTrue(
      crypto.verifyECDSA(advertisedPublic, data, signature),
      "ensureDeviceKey must hand back the public key that pairs with the signing private key",
    )
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
