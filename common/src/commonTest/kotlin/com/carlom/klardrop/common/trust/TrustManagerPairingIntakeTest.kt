package com.carlom.klardrop.common.trust

import FakeLocalPropertiesRepository
import com.carlom.klardrop.common.communication.message.TrustPairingRequest
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.utils.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * Receiver-side pairing intake robustness (plan todo 6):
 * - a request emitted before anyone subscribed is replayed to new subscribers,
 * - a request that arrived while no approval callback was registered is delivered once
 *   the callback registers (server starts before the UI composes),
 * - a clock-skewed request is rejected (security invariant) but no longer silently:
 *   it emits PairingFailed("clock-skew") and never produces a dialog event.
 */
class TrustManagerPairingIntakeTest {

  private val clock = Clock()
  private val bobId = "bob00002"
  private val aliceId = "alice001"

  private fun newManager() = TrustManager(
    crypto = TrustCrypto(),
    storage = InMemoryTrustStorage(),
    clock = clock,
    currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(bobId)),
  )

  private fun freshRequest(timestamp: Long = clock.currentTimeMillis()) = TrustPairingRequest(
    deviceId = aliceId,
    deviceName = "Alice",
    ecdhPublicKey = ByteArray(0),
    ecdsaPublicKey = ByteArray(0),
    timestamp = timestamp,
    deviceType = "DESKTOP",
    appVersion = "1.0.0",
  )

  /**
   * (i) replay-before-subscribe: the coordinator subscribes to pairingEvents when it is
   * constructed; a request handled before that (or before any collector exists) must not
   * be lost — a subscriber joining later still receives it.
   */
  @Test
  fun pairingRequestEmittedBeforeSubscriptionIsReplayedToNewSubscriber() = runTest(timeout = 10.seconds) {
    val bob = newManager()
    bob.setPairingApprovalCallback(NoopIntakeCallback)

    // Emitted BEFORE any subscriber exists.
    bob.handleIncomingPairingRequest(freshRequest(), senderAddress = "192.168.1.50")

    val received = CompletableDeferred<PairingEvent.PairingRequestReceived>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      bob.pairingEvents.collect { if (it is PairingEvent.PairingRequestReceived) received.complete(it) }
    }

    assertEquals(aliceId, received.await().request.deviceId)
  }

  /**
   * (iii) late-callback-delivery: the request arrived while pairingApprovalCallback was
   * still null (UI not yet composed). Once the callback registers, the retained request
   * must be delivered to it — not silently dropped.
   */
  @Test
  fun requestArrivingBeforeCallbackIsDeliveredWhenCallbackIsSet() = runTest {
    val bob = newManager()
    val received = mutableListOf<PairingEvent.PairingRequestReceived>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      bob.pairingEvents.collect { if (it is PairingEvent.PairingRequestReceived) received.add(it) }
    }

    // No callback registered yet: the request can only be retained.
    bob.handleIncomingPairingRequest(freshRequest(), senderAddress = "192.168.1.50")
    advanceUntilIdle()
    assertFalse(
      received.any { it.decision != null },
      "precondition: without a callback no decision can be produced",
    )

    val recording = RecordingPairingApprovalCallback()
    bob.setPairingApprovalCallback(recording)
    advanceUntilIdle()

    val delivered = received.last()
    val decision = assertNotNull(delivered.decision, "Retained request must be re-emitted with a decision")
    decision.showApprovalDialog(onAccept = {}, onReject = {})
    assertEquals(aliceId, recording.lastDeviceId, "Late-registered callback must receive the retained request")
  }

  /**
   * (iv) skew-rejected-no-dialog: a timestamp outside the ±5min window is still rejected
   * (security invariant), but the rejection is no longer silent — a PairingFailed with the
   * "clock-skew" reason is emitted and no dialog event is ever produced.
   */
  @Test
  fun skewedTimestampIsRejectedLoggedAndNeverShowsADialog() = runTest(timeout = 10.seconds) {
    val bob = newManager()
    bob.setPairingApprovalCallback(NoopIntakeCallback)

    val failed = CompletableDeferred<PairingEvent.PairingFailed>()
    val dialogShown = CompletableDeferred<Unit>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      bob.pairingEvents.collect {
        if (it is PairingEvent.PairingFailed) failed.complete(it)
        if (it is PairingEvent.PairingRequestReceived) dialogShown.complete(Unit)
      }
    }

    // 10 minutes old — past the 5-minute MAX_TIME_DIFF window.
    bob.handleIncomingPairingRequest(
      freshRequest(timestamp = clock.currentTimeMillis() - 10 * 60_000),
      senderAddress = "192.168.1.1",
    )
    advanceUntilIdle()

    val event = failed.await()
    assertEquals(aliceId, event.deviceId)
    assertEquals("clock-skew", event.reason)
    assertFalse(dialogShown.isCompleted, "A skewed request must never produce a dialog event")
  }
}

private object NoopIntakeCallback : PairingApprovalCallback {
  override fun onPairingRequested(
    deviceId: String,
    deviceName: String,
    deviceType: String,
    onAccept: suspend () -> Unit,
    onReject: suspend () -> Unit,
  ) = Unit
}

private class RecordingPairingApprovalCallback : PairingApprovalCallback {
  var lastDeviceId: String? = null

  override fun onPairingRequested(
    deviceId: String,
    deviceName: String,
    deviceType: String,
    onAccept: suspend () -> Unit,
    onReject: suspend () -> Unit,
  ) {
    lastDeviceId = deviceId
  }
}
