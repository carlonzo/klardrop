package com.carlom.klardrop.common.communication

import FakeLocalPropertiesRepository
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.trust.InMemoryTrustStorage
import com.carlom.klardrop.common.trust.TrustCrypto
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.utils.Clock
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Drives the UKEY2 handshake + identity binding produced by [KlardropEncryptedTransport] over a
 * pair of in-memory ktor channels (one per direction), exercising the initiator and responder
 * roles against each other on real threads.
 */
class KlardropEncryptedTransportTest {

  private val clock = Clock()
  private val aliceId = "alice001"
  private val bobId = "bob00002"

  private suspend fun newManager(deviceId: String): Pair<TrustManager, InMemoryTrustStorage> {
    val storage = InMemoryTrustStorage()
    val manager = TrustManager(
      crypto = TrustCrypto(),
      storage = storage,
      clock = clock,
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(deviceId)),
    )
    manager.initialize()
    return manager to storage
  }

  /** Make [storage] consider [peerId] trusted, holding [peerEcdsaKey] for signature verification. */
  private suspend fun trust(storage: InMemoryTrustStorage, peerId: String, peerEcdsaKey: ByteArray) {
    storage.storeTrustedDevice(peerId, ByteArray(1)) // any non-null ECDH entry → isTrusted == true
    storage.storeECDSAKey(peerId, peerEcdsaKey)
  }

  /**
   * Runs both handshake roles concurrently over a duplex pair of channels and returns the two
   * resulting ciphers (initiator first). Runs on Dispatchers.Default so the crypto + channel I/O
   * execute on real threads rather than virtual time.
   */
  private suspend fun handshake(
    aliceManager: TrustManager,
    bobManager: TrustManager,
  ): Pair<FrameCipher.Encrypted, FrameCipher.Encrypted> = withContext(Dispatchers.Default) {
    val aliceToBob = ByteChannel(autoFlush = true) // alice writes, bob reads
    val bobToAlice = ByteChannel(autoFlush = true) // bob writes, alice reads

    val aliceCipher = async {
      KlardropEncryptedTransport.runInitiatorHandshake(
        readChannel = bobToAlice,
        writeChannel = aliceToBob,
        selfDeviceId = aliceId,
        peerDeviceId = bobId,
        trustManager = aliceManager,
      )
    }
    val bobCipher = async {
      KlardropEncryptedTransport.runResponderHandshake(
        readChannel = aliceToBob,
        writeChannel = bobToAlice,
        selfDeviceId = bobId,
        peerDeviceId = aliceId,
        trustManager = bobManager,
      )
    }
    aliceCipher.await() to bobCipher.await()
  }

  @Test
  fun handshakeProducesCiphersThatRoundTripFrames() = runTest {
    val (alice, _) = newManager(aliceId)
    val (bob, _) = newManager(bobId)

    val (aliceCipher, bobCipher) = handshake(alice, bob)

    // The UKEY2 session keys are symmetric: what one side encodes, the other decodes.
    val a2b = "hello from alice".encodeToByteArray()
    assertContentEquals(a2b, bobCipher.decode(aliceCipher.encode(a2b)))

    val b2a = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
    assertContentEquals(b2a, aliceCipher.decode(bobCipher.encode(b2a)))
  }

  @Test
  fun untrustedPeersGetUnauthenticatedEncryption() = runTest {
    // Neither side has paired (no stored ECDSA key for the peer) → trust-on-first-use:
    // the channel is encrypted but not authenticated.
    val (alice, _) = newManager(aliceId)
    val (bob, _) = newManager(bobId)

    val (aliceCipher, bobCipher) = handshake(alice, bob)

    assertFalse(aliceCipher.authenticated)
    assertFalse(bobCipher.authenticated)
  }

  @Test
  fun trustedPeersGetAuthenticatedEncryption() = runTest {
    val (alice, aliceStorage) = newManager(aliceId)
    val (bob, bobStorage) = newManager(bobId)

    // Cross-register each other's real device ECDSA public key, as pairing would.
    trust(aliceStorage, bobId, bobStorage.getDevicePublicKey()!!)
    trust(bobStorage, aliceId, aliceStorage.getDevicePublicKey()!!)

    val (aliceCipher, bobCipher) = handshake(alice, bob)

    assertTrue(aliceCipher.authenticated, "Alice should authenticate Bob's binding signature")
    assertTrue(bobCipher.authenticated, "Bob should authenticate Alice's binding signature")
  }

  @Test
  fun mismatchedBindingKeyAbortsForTrustedPeer() = runTest {
    val (alice, aliceStorage) = newManager(aliceId)
    val (bob, bobStorage) = newManager(bobId)

    // Alice trusts "bob" but holds the WRONG ECDSA key for him (e.g. an active MITM relaying the
    // handshake, or a key-rotation glitch). Bob's real binding signature won't verify → Alice
    // must abort the connection. Use a fresh, unrelated device key as the mismatched key.
    val (_, strangerStorage) = newManager("stranger")
    trust(aliceStorage, bobId, strangerStorage.getDevicePublicKey()!!)
    // Bob trusts alice correctly so only Alice's side fails.
    trust(bobStorage, aliceId, aliceStorage.getDevicePublicKey()!!)

    assertFailsWith<IllegalStateException> {
      handshake(alice, bob)
    }
  }

  // ---- MITM gap documentation + SAS hardening tests ----------------------------------------

  /**
   * DOCUMENTS THE SECURITY GAP: for not-yet-paired (untrusted) peers an active on-path MITM can
   * run two independent UKEY2 handshakes — one toward Alice, one toward Bob. Both sides complete
   * with `authenticated=false` and NO detection occurs. This test pins that current behavior so
   * any future regression is visible.
   *
   * The MITM scenario is simulated by running two completely independent handshakes:
   * Alice↔MITM and MITM↔Bob. Each pair produces its own UKEY2 session with its own
   * verification string. Both sides get `authenticated=false` — exactly what the gap looks like.
   */
  @Test
  fun mitmOnFirstContact_untrustedPeers_bothSidesCompleteUnauthenticated() = runTest {
    val (alice, _) = newManager(aliceId)
    val (bob, _) = newManager(bobId)
    val (mitm, _) = newManager("mitm0000")

    // Leg 1: Alice ↔ MITM (MITM acts as if it is Bob — no key to verify against).
    val (aliceCipher, mitmCipherA) = handshake(alice, mitm)
    // Leg 2: MITM ↔ Bob (MITM acts as if it is Alice — no key to verify against).
    val (mitmCipherB, bobCipher) = handshake(mitm, bob)

    // Current behavior: both sides complete unauthenticated — the MITM is undetected.
    assertFalse(aliceCipher.authenticated, "Alice cannot authenticate the MITM (no stored key) — gap confirmed")
    assertFalse(mitmCipherA.authenticated, "MITM sees Alice as untrusted — gap confirmed")
    assertFalse(mitmCipherB.authenticated, "MITM sees Bob as untrusted — gap confirmed")
    assertFalse(bobCipher.authenticated, "Bob cannot authenticate the MITM (no stored key) — gap confirmed")

    // The MITM's two legs have DIFFERENT verification strings → different SAS values.
    // This is the only detectable signal available today without UI work.
    assertNotEquals(
      aliceCipher.verificationSas,
      bobCipher.verificationSas,
      "MITM legs produce different SAS values: Alice sees '${aliceCipher.verificationSas}', " +
        "Bob sees '${bobCipher.verificationSas}'. An out-of-band SAS comparison would catch this."
    )
  }

  /**
   * Assert that a direct (non-MITM) handshake produces IDENTICAL SAS values on both sides.
   * This is the property that makes out-of-band SAS comparison meaningful: if both peers
   * display the same code, they share the same UKEY2 session → no active MITM.
   */
  @Test
  fun directHandshake_bothSidesProduceIdenticalSas() = runTest {
    val (alice, _) = newManager(aliceId)
    val (bob, _) = newManager(bobId)

    val (aliceCipher, bobCipher) = handshake(alice, bob)

    assertEquals(
      aliceCipher.verificationSas,
      bobCipher.verificationSas,
      "Both sides of a legitimate handshake must display the same SAS — " +
        "alice='${aliceCipher.verificationSas}', bob='${bobCipher.verificationSas}'"
    )
  }

  /**
   * SAS is also consistent for already-trusted (authenticated) peers — it is always present
   * regardless of authentication state, so the UI can always surface it.
   */
  @Test
  fun trustedPeerHandshake_bothSidesProduceIdenticalSas() = runTest {
    val (alice, aliceStorage) = newManager(aliceId)
    val (bob, bobStorage) = newManager(bobId)

    trust(aliceStorage, bobId, bobStorage.getDevicePublicKey()!!)
    trust(bobStorage, aliceId, aliceStorage.getDevicePublicKey()!!)

    val (aliceCipher, bobCipher) = handshake(alice, bob)

    assertTrue(aliceCipher.authenticated)
    assertTrue(bobCipher.authenticated)
    assertEquals(
      aliceCipher.verificationSas,
      bobCipher.verificationSas,
      "Trusted-peer handshake must also produce matching SAS"
    )
  }

  /**
   * SAS derivation is deterministic: the same verification string always yields the same code.
   * This ensures both sides independently compute the same value from their shared UKEY2 state.
   */
  @Test
  fun deriveVerificationSas_isDeterministic() {
    val verificationString = ByteArray(32) { it.toByte() }

    val sas1 = KlardropEncryptedTransport.deriveVerificationSas(verificationString)
    val sas2 = KlardropEncryptedTransport.deriveVerificationSas(verificationString)

    assertEquals(sas1, sas2, "SAS derivation must be deterministic")
    assertTrue(sas1.length == 6 && sas1.all { it.isDigit() }, "SAS must be a 6-digit numeric code, got '$sas1'")
  }

  /**
   * SAS derivation produces DIFFERENT codes for different verification strings.
   * Even a single bit flip in the UKEY2 verification string must change the SAS.
   */
  @Test
  fun deriveVerificationSas_differsForDifferentInputs() {
    val base = ByteArray(32) { 0xAA.toByte() }
    val flipped = base.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }

    val sasBase = KlardropEncryptedTransport.deriveVerificationSas(base)
    val sasFlipped = KlardropEncryptedTransport.deriveVerificationSas(flipped)

    assertNotEquals(sasBase, sasFlipped, "Different verification strings must produce different SAS values")
  }

  /**
   * SAS codes are always exactly 6 decimal digits (zero-padded). This is important for
   * display: a code like "000042" must not be presented as "42".
   */
  @Test
  fun deriveVerificationSas_isAlways6DigitsZeroPadded() {
    // A verification string that XOR-folds to 0 would give code "000000".
    // Construct one: two identical 4-byte blocks XOR to 0, then repeat for 32 bytes.
    // With 8 blocks of 4 bytes, each pair cancels: [A, A, A, A, A, A, A, A] XOR = 0.
    // Actually the simplest approach: a string of all zeros folds to acc=0, code=0.
    val allZero = ByteArray(32) { 0 }
    val sas = KlardropEncryptedTransport.deriveVerificationSas(allZero)
    assertEquals(6, sas.length, "SAS must always be 6 characters, got '$sas'")
    assertTrue(sas.all { it.isDigit() }, "SAS must be all digits, got '$sas'")
    assertEquals("000000", sas, "All-zero input should produce '000000'")
  }
}
