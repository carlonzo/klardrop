package com.carlom.klardrop.common.trust

import FakeLocalPropertiesRepository
import TestCoroutines
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.utils.Clock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies the wrap/unwrap round-trip that the TrustedMessageHandler relies on.
 * The wrapper guards the integrity of every trusted message end-to-end:
 *  - Wraps only when the peer is trusted
 *  - Round-trip preserves payload bytes
 *  - Tampering (payload, signature) is detected
 *  - Modified senderId fails verification because the public key lookup changes
 */
class TrustMessageWrapperTest {

  private class TestFixture {
    // 8-char IDs so targetDeviceId matches the peer's shortDeviceId (used as session key).
    val aliceId = "alice001"
    val bobId = "bob00002"

    val coroutines = TestCoroutines()
    val serializer = MessageSerializer(ProtoBuf, coroutines)
    val aliceStorage = InMemoryTrustStorage()
    val bobStorage = InMemoryTrustStorage()

    val alice = TrustManager(
      crypto = TrustCrypto(),
      storage = aliceStorage,
      clock = Clock(),
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(aliceId))
    )
    val bob = TrustManager(
      crypto = TrustCrypto(),
      storage = bobStorage,
      clock = Clock(),
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(bobId))
    )

    val aliceWrapper = TrustMessageWrapper(alice, serializer)
    val bobWrapper = TrustMessageWrapper(bob, serializer)

    suspend fun pairAliceWithBob() {
      val aliceRequest = alice.createPairingRequest(bobId).getOrThrow()
      val bobResponse = bob.createPairingAcceptance(aliceRequest).getOrThrow()
      alice.finalizePairing(bobResponse)

      val bobRequest = bob.createPairingRequest(aliceId).getOrThrow()
      val aliceResponse = alice.createPairingAcceptance(bobRequest).getOrThrow()
      bob.finalizePairing(aliceResponse)
    }
  }

  @Test
  fun wrappingUntrustedDeviceReturnsNull() = runTest {
    val fx = TestFixture()
    val message = TextMessage("Title", "body")

    val wrapped = fx.aliceWrapper.wrapMessage(message, targetDeviceId = "someone-unknown")
    assertNull(wrapped, "Unknown devices must not produce a trusted envelope")
  }

  @Test
  fun wrapThenUnwrapPreservesOriginalMessage() = runTest {
    val fx = TestFixture()
    fx.pairAliceWithBob()

    val original = TextMessage("Greeting", "hello bob")

    val trusted = fx.aliceWrapper.wrapMessage(original, targetDeviceId = fx.bobId)
    assertNotNull(trusted, "Trusted peer should produce a wrapped message")

    val unwrapped = fx.bobWrapper.unwrapMessage(trusted)
    assertNotNull(unwrapped)
    val text = assertIs<TextMessage>(unwrapped)
    assertEquals(original.text, text.text)
    assertEquals(original.title, text.title)
  }

  @Test
  fun tamperedPayloadIsRejectedOnUnwrap() = runTest {
    val fx = TestFixture()
    fx.pairAliceWithBob()

    val trusted = fx.aliceWrapper.wrapMessage(TextMessage("T", "untouched"), fx.bobId)
    assertNotNull(trusted)

    val tamperedPayload = trusted.payload.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
    val tampered = trusted.copy(payload = tamperedPayload)

    assertNull(fx.bobWrapper.unwrapMessage(tampered), "Payload tampering must fail verification")
  }

  @Test
  fun tamperedSignatureIsRejectedOnUnwrap() = runTest {
    val fx = TestFixture()
    fx.pairAliceWithBob()

    val trusted = fx.aliceWrapper.wrapMessage(TextMessage("T", "original"), fx.bobId)
    assertNotNull(trusted)

    val mangledSig = trusted.signature.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
    val tampered = trusted.copy(signature = mangledSig)

    assertNull(fx.bobWrapper.unwrapMessage(tampered), "Signature tampering must fail verification")
  }

  @Test
  fun forgedSenderIdFailsVerification() = runTest {
    val fx = TestFixture()
    fx.pairAliceWithBob()

    val trusted = fx.aliceWrapper.wrapMessage(TextMessage("T", "original"), fx.bobId)
    assertNotNull(trusted)

    // Replace senderId with someone whose ECDSA key Bob doesn't have -> verification must fail.
    val forged = trusted.copy(senderId = "charlie-")

    assertNull(fx.bobWrapper.unwrapMessage(forged))
  }
}
