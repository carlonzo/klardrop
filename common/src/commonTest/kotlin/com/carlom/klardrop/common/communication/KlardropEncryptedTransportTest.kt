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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
}
