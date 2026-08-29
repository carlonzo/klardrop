package com.carlom.klardrop.common.trust

import FakeLocalPropertiesRepository
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.TrustPairingRequest
import com.carlom.klardrop.common.communication.message.TrustRevocationMessage
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises PairingProtocolCoordinator's contract with Messenger:
 * - Successful sends notify TrustManager and surface success.
 * - Send failures trigger TrustManager cleanup (no dangling pairing sessions).
 * - Pairing completion events propagate to the onPairingCompleted callback.
 *
 * Device IDs are 8 chars so TrustManager.finalizePairing's session lookup
 * (keyed on shortDeviceId = deviceId.take(8)) matches across both peers.
 */
class PairingProtocolCoordinatorTest {

  private val aliceId = "alice001"
  private val bobId = "bob00002"

  private fun newTrustManager(deviceId: String) = TrustManager(
    crypto = TrustCrypto(),
    storage = InMemoryTrustStorage(),
    clock = Clock(),
    currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(deviceId))
  )

  private fun newTrustManagerWith(
    deviceId: String,
    storage: InMemoryTrustStorage,
  ) = TrustManager(
    crypto = TrustCrypto(),
    storage = storage,
    clock = Clock(),
    currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(deviceId))
  )

  @Test
  fun initiatePairingSendsRequestViaMessengerOnSuccess() = runTest {
    val trustManager = newTrustManager(aliceId)
    val messenger = RecordingMessenger(response = MessengerSendProgress.Completed)

    val coordinator = PairingProtocolCoordinator(trustManager, messenger)
    val result = coordinator.initiatePairing(bobId)

    assertTrue(result.isSuccess)
    assertEquals(1, messenger.sentRequests.size)
    val (deviceId, request) = messenger.sentRequests.single()
    assertEquals(bobId, deviceId)
    assertTrue(request.message is TrustPairingRequest)
  }

  @Test
  fun initiatePairingFailsWhenMessengerReportsError() = runTest {
    val trustManager = newTrustManager(aliceId)
    val messenger = RecordingMessenger(response = MessengerSendProgress.Error("network down"))

    val coordinator = PairingProtocolCoordinator(trustManager, messenger)
    val result = coordinator.initiatePairing(bobId)

    assertTrue(result.isFailure, "Messenger error must surface as Result.failure")
    val message = result.exceptionOrNull()?.message.orEmpty()
    assertTrue(
      message.contains("Failed to send pairing request"),
      "Failure should identify pairing-send path, got: $message"
    )
  }

  @Test
  fun pairingFailedEmittedWithConnectFailedReasonWhenAllDialsFail() = runTest {
    // Endpoints existed but every dial failed: Messenger reports the terminal Error with a
    // connect-failed(<cause class>) reason, and TrustManager must surface it as
    // PairingFailed so the UI can tell the user the device was unreachable.
    val trustManager = newTrustManager(aliceId)
    val messenger = RecordingMessenger(
      response = MessengerSendProgress.Error(
        message = "Transfer failed: Could not connect to bob00002",
        reason = "connect-failed(ConnectException)",
      )
    )
    val coordinator = PairingProtocolCoordinator(trustManager, messenger)

    val failed = CompletableDeferred<PairingEvent.PairingFailed>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      trustManager.pairingEvents.collect { if (it is PairingEvent.PairingFailed) failed.complete(it) }
    }

    coordinator.initiatePairing(bobId)

    val event = failed.await()
    assertEquals(bobId, event.deviceId)
    assertEquals("connect-failed(ConnectException)", event.reason)
  }

  @Test
  fun pairingSucceededEmittedOnFinalize() = runTest {
    val alice = newTrustManager(aliceId)
    val messenger = RecordingMessenger(response = MessengerSendProgress.Completed)
    val coordinator = PairingProtocolCoordinator(alice, messenger)

    val succeeded = CompletableDeferred<PairingEvent.PairingSucceeded>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      alice.pairingEvents.collect { if (it is PairingEvent.PairingSucceeded) succeeded.complete(it) }
    }

    val bob = newTrustManager(bobId)
    val request = alice.createPairingRequest(bobId).getOrThrow()
    val response = bob.createPairingAcceptance(request).getOrThrow()
    alice.finalizePairing(response)

    assertEquals(bobId, succeeded.await().deviceId)
  }

  @Test
  fun onPairingCompletedCallbackFiresWhenTrustManagerEmitsCompletion() = runTest {
    val alice = newTrustManager(aliceId)
    val messenger = RecordingMessenger(response = MessengerSendProgress.Completed)
    val coordinator = PairingProtocolCoordinator(alice, messenger)

    val signal = CompletableDeferred<Pair<String, Boolean>>()
    coordinator.onPairingCompleted = { deviceId, _, success ->
      signal.complete(deviceId to success)
    }

    // Drive the flow: Alice creates a request, Bob accepts and responds back,
    // Alice finalizes -> emits PairingCompleted(success=true) on the shared flow
    // that the coordinator is subscribed to.
    val bob = newTrustManager(bobId)
    val request = alice.createPairingRequest(bobId).getOrThrow()
    val response = bob.createPairingAcceptance(request).getOrThrow()
    alice.finalizePairing(response)

    // If the coordinator's SharedFlow subscription is wired correctly, the callback
    // fires from Dispatchers.Default within milliseconds. runTest's default 60s timeout
    // is our backstop if the contract regresses.
    val (deviceId, success) = signal.await()
    assertEquals(response.deviceId, deviceId)
    assertEquals(true, success)
  }

  @Test
  fun unpairSendsRevocationAndRemovesLocalTrust() = runTest {
    // Pair Alice ↔ Bob so Alice has Bob in her trust store. After unpair():
    //  1. A revocation message was sent to Bob via Messenger.
    //  2. Alice's local trust entry for Bob is gone.
    val aliceStorage = InMemoryTrustStorage()
    val alice = newTrustManagerWith(aliceId, aliceStorage)
    val bob = newTrustManager(bobId)
    val response = bob.createPairingAcceptance(alice.createPairingRequest(bobId).getOrThrow()).getOrThrow()
    alice.finalizePairing(response)
    assertTrue(aliceStorage.isTrusted(bobId), "precondition: Alice trusts Bob")

    val messenger = RecordingMessenger(response = MessengerSendProgress.Completed)
    val coordinator = PairingProtocolCoordinator(alice, messenger)
    coordinator.unpair(bobId, reason = "user_unpaired")

    val revocationSends = messenger.sentRequests.filter { it.request.message is TrustRevocationMessage }
    assertEquals(1, revocationSends.size, "Expected exactly one revocation send")
    val revocation = revocationSends.single().request.message as TrustRevocationMessage
    assertEquals(aliceId, revocation.senderId)
    assertEquals(bobId, revocation.targetDeviceId)
    assertEquals("user_unpaired", revocation.reason)

    assertFalse(aliceStorage.isTrusted(bobId), "Local trust must be removed after unpair()")
  }

  @Test
  fun unpairRemovesLocalTrustEvenWhenMessengerErrors() = runTest {
    // Send failures (peer offline, transport down) are tolerated — local removal
    // must still happen so the user's intent is honoured.
    val aliceStorage = InMemoryTrustStorage()
    val alice = newTrustManagerWith(aliceId, aliceStorage)
    val bob = newTrustManager(bobId)
    val response = bob.createPairingAcceptance(alice.createPairingRequest(bobId).getOrThrow()).getOrThrow()
    alice.finalizePairing(response)

    val messenger = RecordingMessenger(response = MessengerSendProgress.Error("peer offline"))
    val coordinator = PairingProtocolCoordinator(alice, messenger)
    coordinator.unpair(bobId)

    assertFalse(aliceStorage.isTrusted(bobId), "Send failure must NOT block local removal")
  }

  @Test
  fun rejectedPairingStillSurfacesThroughOnPairingCompleted() = runTest {
    val alice = newTrustManager(aliceId)
    val messenger = RecordingMessenger(response = MessengerSendProgress.Completed)
    val coordinator = PairingProtocolCoordinator(alice, messenger)

    val signal = CompletableDeferred<Boolean>()
    coordinator.onPairingCompleted = { _, _, success -> signal.complete(success) }

    val bob = newTrustManager(bobId)
    alice.createPairingRequest(bobId).getOrThrow()
    val rejection = bob.createPairingRejection(aliceId)
    alice.finalizePairing(rejection)

    val success = signal.await()
    assertFalse(success, "Rejection must produce success=false in callback")
  }
}

private class RecordingMessenger(
  private val response: MessengerSendProgress
) : Messenger {

  data class Sent(val deviceId: String, val request: SendMessageRequest)

  val sentRequests: MutableList<Sent> = mutableListOf()

  override fun send(deviceId: String, messageRequest: SendMessageRequest): Flow<MessengerSendProgress> {
    sentRequests.add(Sent(deviceId, messageRequest))
    return flow {
      emit(MessengerSendProgress.Pending)
      emit(response)
    }
  }

  override fun receive(): Flow<Pair<String, Flow<ReceiveMessageUpdate>>> = flowOf()
}
