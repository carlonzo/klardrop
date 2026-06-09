package com.carlom.klardrop.common.receiver

import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.utils.Coroutines
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression test for the incoming-transfer banner bug: the per-device chat screen showed no
 * accept/reject banner because [DeviceChatViewModel] read [MessageReceiver.onReceiveMessage],
 * which mints a brand-new flow the receive pipeline never writes to. The pipeline drives the
 * flow it got from its OWN [onReceiveMessage] call (a different instance), so only the
 * discovery/home banner (which observes [MessageReceiver.notifier]) ever updated.
 *
 * The fix exposes [MessageReceiver.latestUpdates]: an aggregated, retained per-device view that
 * mirrors the LIVE producer flow. This test proves a consumer reading [latestUpdates] sees the
 * pending authorization the producer set — including a consumer that reads it only AFTER the
 * prompt fired (the "opened the chat screen late" case).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageReceiverImplTest {

  private class NoopVisibleDevices : VisibleDevices {
    override val visibleDevices = MutableStateFlow(emptyMap<String, DiscoveryDevice>())
    override suspend fun onNewDeviceVisible(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection) = Unit
    override fun isDeviceVisible(deviceId: String) = false
    override fun getDevice(deviceId: String): DiscoveryDevice? = null
    override fun cachedNameFor(deviceId: String): String? = null
    override fun touchLastSeen(deviceId: String) = Unit
    override fun onDeviceLost(deviceId: String) = Unit
    override fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection) = Unit
    override fun invalidateKlardropEndpoint(deviceId: String, address: String, port: Int) = Unit
    override fun findDeviceByAddress(address: InetSocketAddress): DiscoveryDevice? = null
  }

  /** Coroutines whose every dispatcher is the single test dispatcher, so the receiver's internal
   * aggregator runs under the test scheduler's virtual clock (fully deterministic). */
  private fun testCoroutines(dispatcher: CoroutineDispatcher): Coroutines = object : Coroutines {
    override val ioDispatcher = dispatcher
    override val mainDispatcher = dispatcher
    override val cpuDispatcher = dispatcher
    override val appScope = CoroutineScope(dispatcher)
    override fun newScope(): CoroutineScope = CoroutineScope(dispatcher)
    override fun newScope(context: CoroutineContext): CoroutineScope = CoroutineScope(dispatcher + context)
  }

  @Test
  fun latestUpdatesSurfacesPendingAuthorizationToLateConsumer() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val receiver = MessageReceiverImpl(testCoroutines(dispatcher), NoopVisibleDevices())
    // Let the internal aggregator subscribe to the notifier before any transfer arrives
    // (mirrors production: the receiver exists from startup, long before transfers).
    runCurrent()

    // Producer side: the receive pipeline obtains a flow and drives it to PendingAuthorization.
    val producerFlow = receiver.onReceiveMessage("dev00001")
    producerFlow.update { it.copy(status = ReceiveMessageStatus.PendingAuthorization { /* accept */ }) }
    advanceUntilIdle()

    // A consumer (the chat screen) reading latestUpdates AFTER the prompt fired must see it.
    val pending = receiver.latestUpdates.value["dev00001"]
    assertTrue(
      pending?.status is ReceiveMessageStatus.PendingAuthorization,
      "latestUpdates must surface the live pending authorization to a late consumer (was: ${pending?.status})",
    )
  }

  @Test
  fun latestUpdatesClearsPendingAfterTransferProgresses() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val receiver = MessageReceiverImpl(testCoroutines(dispatcher), NoopVisibleDevices())
    runCurrent()

    val producerFlow = receiver.onReceiveMessage("dev00001")
    producerFlow.update { it.copy(status = ReceiveMessageStatus.PendingAuthorization { }) }
    advanceUntilIdle()
    assertTrue(receiver.latestUpdates.value["dev00001"]?.status is ReceiveMessageStatus.PendingAuthorization)

    // User accepted → the producer advances the same flow; the aggregated view must follow,
    // so the banner (which filters for PendingAuthorization) disappears.
    producerFlow.update { it.copy(status = ReceiveMessageStatus.Progress(emptyList())) }
    advanceUntilIdle()

    val status = receiver.latestUpdates.value["dev00001"]?.status
    assertTrue(status is ReceiveMessageStatus.Progress, "pending state must clear once the transfer progresses (was: $status)")
    assertNull(
      (status as? ReceiveMessageStatus.PendingAuthorization),
      "must no longer be PendingAuthorization after acceptance",
    )
  }
}
