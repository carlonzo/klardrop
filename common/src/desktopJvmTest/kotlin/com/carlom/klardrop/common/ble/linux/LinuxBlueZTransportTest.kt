package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.ble.BlePeerEvent
import com.carlom.klardrop.common.ble.BleSession
import com.carlom.klardrop.common.discovery.CurrentDevice
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The BleTransport-shaped composition ([LinuxBleAdvertiser] + [LinuxBleCentral] +
 * [LinuxBlePeripheral] over one [BlueZFacade]) exercised through a fake facade —
 * no D-Bus daemon involved. Pins the wiring contract `BleTransport.desktopJvm`
 * delegates to on Linux: probe-gated support, both roles live over the same facade.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinuxBlueZTransportTest {

  private val device = CurrentDevice("abcd1234xx", "Test Box", DeviceType.DESKTOP, OsType.LINUX)

  /** Fake facade: records every role's calls and lets tests drive the BlueZ-side events. */
  private class FakeBlueZFacade(private val supported: Boolean = true) : BlueZFacade {

    @Volatile var foundListener: ((BlePeerEvent.Found) -> Unit)? = null
    @Volatile var lostListener: ((String) -> Unit)? = null
    @Volatile var writeListener: ((String, ByteArray) -> Unit)? = null
    @Volatile var subscriptionListener: ((String, Boolean) -> Unit)? = null

    var startScanCount = 0
      private set
    var stopScanCount = 0
      private set
    var startAdvertisingCount = 0
      private set
    var stopAdvertisingCount = 0
      private set
    var exportCount = 0
      private set
    val connectCalls = mutableListOf<String>()
    val txWrites = mutableListOf<List<Byte>>()
    val notified = mutableListOf<Pair<String, List<Byte>>>()
    val exportCompleted = CompletableDeferred<Unit>()
    private val notifySinks = ConcurrentHashMap<String, (ByteArray) -> Unit>()

    override suspend fun probeCapability(): BlueZCapability =
      if (supported) BlueZCapability(true, listOf("/org/bluez/hci0")) else BlueZCapability(false, emptyList())

    override suspend fun startAdvertising(currentDevice: CurrentDevice) {
      startAdvertisingCount++
    }

    override suspend fun stopAdvertising() {
      stopAdvertisingCount++
    }

    override suspend fun startScan() {
      startScanCount++
    }

    override suspend fun stopScan() {
      stopScanCount++
    }

    override fun onPeerFound(listener: ((BlePeerEvent.Found) -> Unit)?) {
      foundListener = listener
    }

    override fun onPeerLost(listener: ((String) -> Unit)?) {
      lostListener = listener
    }

    override suspend fun connect(
      address: String,
      onNotify: (ByteArray) -> Unit,
      onDisconnected: () -> Unit,
    ): BlueZPeerLink {
      connectCalls += address
      notifySinks[address] = onNotify
      return BlueZPeerLink(
        mtu = 185,
        writeTx = { value -> synchronized(txWrites) { txWrites.add(value.toList()) } },
      )
    }

    override suspend fun exportApplication() {
      exportCount++
      exportCompleted.complete(Unit)
    }

    override suspend fun notifyValue(centralId: String, value: ByteArray) {
      synchronized(notified) { notified.add(centralId to value.toList()) }
    }

    override fun onCharacteristicWrite(listener: ((String, ByteArray) -> Unit)?) {
      writeListener = listener
    }

    override fun onCentralSubscription(listener: ((String, Boolean) -> Unit)?) {
      subscriptionListener = listener
    }

    // Test-side triggers standing in for BlueZ D-Bus events.
    fun peerFound(address: String) =
      foundListener?.invoke(BlePeerEvent.Found(address, "abcd1234", null, -42))

    fun peerLost(address: String) = lostListener?.invoke(address)

    fun centralSubscribes(centralId: String) = subscriptionListener?.invoke(centralId, true)

    fun centralWrites(centralId: String, value: ByteArray) = writeListener?.invoke(centralId, value)
  }

  /** Starts collecting [transport.scanForPeers] until the facade listeners are registered. */
  private suspend fun CoroutineScope.startScanning(
    facade: FakeBlueZFacade,
    transport: LinuxBlueZTransport,
    events: MutableList<BlePeerEvent>,
  ): Job {
    val collector = launch { transport.scanForPeers().toList(events) }
    withTimeout(5_000) { while (facade.foundListener == null) yield() }
    return collector
  }

  /** Suspends until [count] events were emitted (the collector runs concurrently). */
  private suspend fun awaitEvents(events: List<BlePeerEvent>, count: Int) {
    withTimeout(5_000) { while (events.size < count) yield() }
  }

  @Test
  fun isSupportedReflectsCapabilityProbe() = runTest {
    assertTrue(LinuxBlueZTransport(FakeBlueZFacade(supported = true)).isSupported())
    assertFalse(LinuxBlueZTransport(FakeBlueZFacade(supported = false)).isSupported())
  }

  @Test
  fun scanForPeersForwardsFacadePeerEvents() = runTest {
    val facade = FakeBlueZFacade()
    val transport = LinuxBlueZTransport(facade)

    val events = mutableListOf<BlePeerEvent>()
    val collector = startScanning(facade, transport, events)
    facade.peerFound("AA:BB:CC:DD:EE:FF")
    awaitEvents(events, count = 1)
    facade.peerLost("AA:BB:CC:DD:EE:FF")
    awaitEvents(events, count = 2)

    assertEquals(
      listOf(
        BlePeerEvent.Found(address = "AA:BB:CC:DD:EE:FF", shortDeviceId = "abcd1234", localName = null, rssi = -42),
        BlePeerEvent.Lost("AA:BB:CC:DD:EE:FF"),
      ),
      events,
    )
    assertEquals(1, facade.startScanCount)

    collector.cancelAndJoin()
    assertEquals(1, facade.stopScanCount)
  }

  @Test
  fun connectCentralDelegatesToCentralAndReturnsSession() = runTest {
    val facade = FakeBlueZFacade()
    val transport = LinuxBlueZTransport(facade)

    val events = mutableListOf<BlePeerEvent>()
    val collector = startScanning(facade, transport, events)
    facade.peerFound("AA:BB:CC:DD:EE:FF")
    awaitEvents(events, count = 1)

    val session: BleSession = transport.connectCentral("AA:BB:CC:DD:EE:FF", "abcd1234")

    assertEquals(listOf("AA:BB:CC:DD:EE:FF"), facade.connectCalls)
    assertEquals("abcd1234", session.deviceId)
    assertEquals(185, session.mtu)
    assertTrue(session.isOpen)

    // Central → peer: sendChunk lands as a writeTx on the facade link.
    session.sendChunk(byteArrayOf(1, 2, 3))
    assertEquals(listOf(byteArrayOf(1, 2, 3).toList()), facade.txWrites.toList())

    collector.cancelAndJoin()
  }

  @Test
  fun serveGattForwardsPeripheralSessions() = runTest {
    val facade = FakeBlueZFacade()
    val transport = LinuxBlueZTransport(facade)

    val sessions = mutableListOf<BleSession>()
    val collector = launch { transport.serveGatt().toList(sessions) }
    facade.exportCompleted.await()
    facade.centralSubscribes("/org/bluez/hci0/dev_A")
    withTimeout(5_000) { while (sessions.isEmpty()) yield() }

    val session = sessions.single()
    assertEquals("/org/bluez/hci0/dev_A", session.deviceId)
    assertTrue(session.isOpen)

    // Peripheral → central: sendChunk lands as a notifyValue on the facade.
    session.sendChunk(byteArrayOf(7))
    assertEquals(listOf("/org/bluez/hci0/dev_A" to byteArrayOf(7).toList()), facade.notified.toList())

    // Central → peripheral: a TX write arrives as receiveChunk.
    facade.centralWrites("/org/bluez/hci0/dev_A", byteArrayOf(8))
    assertEquals(byteArrayOf(8).toList(), session.receiveChunk()!!.toList())

    collector.cancelAndJoin()
  }

  @Test
  fun startAndStopAdvertisingDelegateToAdvertiser() = runTest {
    val facade = FakeBlueZFacade()
    val transport = LinuxBlueZTransport(facade)

    transport.startAdvertising(device)
    transport.startAdvertising(device) // advertiser idempotence: double start never re-registers
    assertEquals(1, facade.startAdvertisingCount)

    transport.stopAdvertising()
    transport.stopAdvertising() // stop without active advertising is a no-op
    assertEquals(1, facade.stopAdvertisingCount)
  }
}