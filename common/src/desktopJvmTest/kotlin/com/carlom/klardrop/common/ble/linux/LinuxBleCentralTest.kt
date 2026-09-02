package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.ble.BleConstants
import com.carlom.klardrop.common.ble.BlePeerEvent
import com.carlom.klardrop.common.ble.BleSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Central-role logic exercised through a fake [BlueZFacade] that simulates Klardrop
 * Device1 objects appearing/disappearing and a remote GATT server — no D-Bus daemon
 * involved. The fake mirrors the real facade's contract: only advertisements with
 * decodable Klardrop ServiceData produce Found events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinuxBleCentralTest {

  /** Fake facade: records scan/connect calls and lets tests drive the BlueZ-side events. */
  private class FakeBlueZFacade(
    /** Value BlueZ would report for GattCharacteristic1.MTU; null = property absent. */
    private val mtuProperty: Int? = 185,
    /** When true, connect() never completes (drives the 10s timeout). */
    private val hangConnect: Boolean = false,
    /** Chunks BlueZ notifies before connect() returns — i.e. before a session exists. */
    private val notifyBeforeReturn: List<ByteArray> = emptyList(),
    /** When true, the peer drops before connect() returns the link. */
    private val disconnectBeforeReturn: Boolean = false,
  ) : BlueZFacade {

    @Volatile var foundListener: ((BlePeerEvent.Found) -> Unit)? = null
    @Volatile var lostListener: ((String) -> Unit)? = null
    var startScanCount = 0
      private set
    var stopScanCount = 0
      private set
    val connectCalls = mutableListOf<String>()
    val txWrites = mutableListOf<List<Byte>>()
    private var notifySink: ((ByteArray) -> Unit)? = null
    private var disconnectSink: (() -> Unit)? = null

    override suspend fun probeCapability() = BlueZCapability(true, listOf("/org/bluez/hci0"))

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
      notifySink = onNotify
      disconnectSink = onDisconnected
      if (hangConnect) awaitCancellation()
      // BlueZ has been notifying on RX and watching Connected since StartNotify — both
      // can fire on a signal thread before the caller has a session to route them to.
      notifyBeforeReturn.forEach(onNotify)
      if (disconnectBeforeReturn) onDisconnected()
      return BlueZPeerLink(
        mtu = negotiatedMtu(mtuProperty),
        writeTx = { value -> synchronized(txWrites) { txWrites.add(value.toList()) } },
      )
    }

    // Test-side triggers standing in for BlueZ D-Bus events. Mirrors the real facade:
    // devices without decodable Klardrop ServiceData are skipped, never emitted.
    fun deviceAppears(
      address: String,
      serviceData: Map<String, ByteArray>,
      rssi: Int = -42,
      localName: String? = null,
    ) {
      val shortId = decodeShortDeviceId(serviceData) ?: return
      foundListener?.invoke(BlePeerEvent.Found(address, shortId, localName, rssi))
    }

    fun deviceDisappears(address: String) {
      lostListener?.invoke(address)
    }

    fun deliverNotify(value: ByteArray) = checkNotNull(notifySink)(value)

    fun peerDisconnected() = checkNotNull(disconnectSink)()
  }

  /**
   * Starts collecting [central.scanForPeers] into [events] and suspends until the fake
   * facade's listeners are registered. The collector runs until the test cancels it.
   */
  private suspend fun CoroutineScope.startScanning(
    facade: FakeBlueZFacade,
    central: LinuxBleCentral,
    events: MutableList<BlePeerEvent>,
  ): Job {
    val collector = launch { central.scanForPeers().toList(events) }
    withTimeout(5_000) { while (facade.foundListener == null) yield() }
    return collector
  }

  /** Suspends until [count] events were emitted (the collector runs concurrently). */
  private suspend fun awaitEvents(events: List<BlePeerEvent>, count: Int) {
    withTimeout(5_000) { while (events.size < count) yield() }
  }

  @Test
  fun scanEmitsFoundWithDecodedShortDeviceId() = runTest {
    val facade = FakeBlueZFacade()
    val central = LinuxBleCentral(facade)

    val events = mutableListOf<BlePeerEvent>()
    val collector = startScanning(facade, central, events)
    facade.deviceAppears(
      address = "AA:BB:CC:DD:EE:FF",
      serviceData = mapOf(BleConstants.SERVICE_UUID to "abcd1234".encodeToByteArray()),
      rssi = -42,
    )
    awaitEvents(events, count = 1)

    assertEquals(
      BlePeerEvent.Found(address = "AA:BB:CC:DD:EE:FF", shortDeviceId = "abcd1234", localName = null, rssi = -42),
      events.single(),
    )
    assertEquals(1, facade.startScanCount)

    collector.cancelAndJoin()
  }

  @Test
  fun deviceRemovedEmitsLost() = runTest {
    val facade = FakeBlueZFacade()
    val central = LinuxBleCentral(facade)

    val events = mutableListOf<BlePeerEvent>()
    val collector = startScanning(facade, central, events)
    facade.deviceAppears("AA:BB:CC:DD:EE:FF", mapOf(BleConstants.SERVICE_UUID to "abcd1234".encodeToByteArray()))
    awaitEvents(events, count = 1)
    facade.deviceDisappears("AA:BB:CC:DD:EE:FF")
    awaitEvents(events, count = 2)

    assertEquals(BlePeerEvent.Lost("AA:BB:CC:DD:EE:FF"), events.last())

    collector.cancelAndJoin()
  }

  @Test
  fun malformedServiceDataSkipsDeviceWithoutCrashing() = runTest {
    val facade = FakeBlueZFacade()
    val central = LinuxBleCentral(facade)

    val events = mutableListOf<BlePeerEvent>()
    val collector = startScanning(facade, central, events)
    // No service data at all, wrong UUID key, empty payload, oversized garbage —
    // none of these may produce a Found event or throw.
    facade.deviceAppears("AA:BB:CC:DD:EE:01", emptyMap())
    facade.deviceAppears("AA:BB:CC:DD:EE:02", mapOf("0000ffff-0000-1000-8000-00805f9b34fb" to "abcd1234".encodeToByteArray()))
    facade.deviceAppears("AA:BB:CC:DD:EE:03", mapOf(BleConstants.SERVICE_UUID to ByteArray(0)))
    facade.deviceAppears("AA:BB:CC:DD:EE:04", mapOf(BleConstants.SERVICE_UUID to ByteArray(20) { it.toByte() }))
    yield()

    assertTrue(events.isEmpty(), "malformed advertisements must be skipped, got $events")

    collector.cancelAndJoin()
  }

  @Test
  fun decodeShortDeviceIdMirrorsAdvertisePayloadEncoding() {
    // Encode side: BleAdvertisePayload puts shortDeviceId.encodeToByteArray() under
    // SERVICE_UUID (see BleAdvertisePayloadTest vectors). Decode side must round-trip.
    val encoded = mapOf(BleConstants.SERVICE_UUID to "abcd1234".encodeToByteArray())
    assertEquals("abcd1234", decodeShortDeviceId(encoded))

    // Malformed vectors: absent key, empty, too long, non-alphanumeric, invalid UTF-8.
    assertNull(decodeShortDeviceId(emptyMap()))
    assertNull(decodeShortDeviceId(mapOf("other" to "abcd1234".encodeToByteArray())))
    assertNull(decodeShortDeviceId(mapOf(BleConstants.SERVICE_UUID to ByteArray(0))))
    assertNull(decodeShortDeviceId(mapOf(BleConstants.SERVICE_UUID to "abcd12345".encodeToByteArray())))
    assertNull(decodeShortDeviceId(mapOf(BleConstants.SERVICE_UUID to "abcd-234".encodeToByteArray())))
    assertNull(decodeShortDeviceId(mapOf(BleConstants.SERVICE_UUID to byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 1, 2, 3, 4, 5))))
  }

  @Test
  fun connectEmitsSessionWithNegotiatedMtuAndBidirectionalIo() = runTest {
    val facade = FakeBlueZFacade(mtuProperty = 185)
    val central = LinuxBleCentral(facade)

    val events = mutableListOf<BlePeerEvent>()
    val collector = startScanning(facade, central, events)
    facade.deviceAppears("AA:BB:CC:DD:EE:FF", mapOf(BleConstants.SERVICE_UUID to "abcd1234".encodeToByteArray()))
    awaitEvents(events, count = 1)

    val session: BleSession = central.connectCentral("AA:BB:CC:DD:EE:FF", "abcd1234")

    assertEquals(listOf("AA:BB:CC:DD:EE:FF"), facade.connectCalls)
    assertEquals("abcd1234", session.deviceId)
    // BleSession.mtu is a payload size: the 185-byte ATT MTU minus the 3-byte ATT header.
    assertEquals(185 - BleConstants.ATT_HEADER_SIZE, session.mtu)
    assertTrue(session.isOpen)

    // Central → peer: sendChunk lands as a WriteValue on the peer's TX characteristic.
    session.sendChunk(byteArrayOf(1, 2, 3))
    assertEquals(listOf(byteArrayOf(1, 2, 3).toList()), facade.txWrites.toList())

    // Peer → central: a GATT notification arrives as receiveChunk.
    facade.deliverNotify(byteArrayOf(9, 8))
    assertEquals(byteArrayOf(9, 8).toList(), session.receiveChunk()!!.toList())

    collector.cancelAndJoin()
  }

  @Test
  fun distroWithoutMtuPropertyFallsBackToDefaultMtu() = runTest {
    // BlueZ < 5.63 does not expose GattCharacteristic1.MTU — the session must keep
    // the conservative ATT default instead of failing or guessing high.
    val facade = FakeBlueZFacade(mtuProperty = null)
    val central = LinuxBleCentral(facade)

    val events = mutableListOf<BlePeerEvent>()
    val collector = startScanning(facade, central, events)
    facade.deviceAppears("AA:BB:CC:DD:EE:FF", mapOf(BleConstants.SERVICE_UUID to "abcd1234".encodeToByteArray()))
    awaitEvents(events, count = 1)

    val session = central.connectCentral("AA:BB:CC:DD:EE:FF", "abcd1234")

    assertEquals(BleConstants.DEFAULT_MTU - BleConstants.ATT_HEADER_SIZE, session.mtu)

    collector.cancelAndJoin()
  }

  @Test
  fun notificationsArrivingBeforeTheSessionExistsAreReplayed() = runTest {
    val facade = FakeBlueZFacade(notifyBeforeReturn = listOf(byteArrayOf(1, 2), byteArrayOf(3)))
    val central = LinuxBleCentral(facade)

    val events = mutableListOf<BlePeerEvent>()
    val collector = startScanning(facade, central, events)
    facade.deviceAppears("AA:BB:CC:DD:EE:FF", mapOf(BleConstants.SERVICE_UUID to "abcd1234".encodeToByteArray()))
    awaitEvents(events, count = 1)

    val session = central.connectCentral("AA:BB:CC:DD:EE:FF", "abcd1234")

    // Buffered while the session did not exist yet, replayed in arrival order.
    assertEquals(listOf<Byte>(1, 2), session.receiveChunk()!!.toList())
    assertEquals(listOf<Byte>(3), session.receiveChunk()!!.toList())
    assertTrue(session.isOpen)

    collector.cancelAndJoin()
  }

  @Test
  fun peerDroppingBeforeTheSessionExistsStillClosesIt() = runTest {
    val facade = FakeBlueZFacade(disconnectBeforeReturn = true)
    val central = LinuxBleCentral(facade)

    val events = mutableListOf<BlePeerEvent>()
    val collector = startScanning(facade, central, events)
    facade.deviceAppears("AA:BB:CC:DD:EE:FF", mapOf(BleConstants.SERVICE_UUID to "abcd1234".encodeToByteArray()))
    awaitEvents(events, count = 1)

    val session = central.connectCentral("AA:BB:CC:DD:EE:FF", "abcd1234")

    // The disconnect must not be lost just because it beat the session into existence.
    assertFalse(session.isOpen)
    assertNull(session.receiveChunk())

    collector.cancelAndJoin()
  }

  @Test
  fun connectTimesOutAfter10sWithIllegalState() = runTest {
    val facade = FakeBlueZFacade(hangConnect = true)
    val central = LinuxBleCentral(facade)

    val events = mutableListOf<BlePeerEvent>()
    val collector = startScanning(facade, central, events)
    facade.deviceAppears("AA:BB:CC:DD:EE:FF", mapOf(BleConstants.SERVICE_UUID to "abcd1234".encodeToByteArray()))
    awaitEvents(events, count = 1)

    val ex = assertFailsWith<IllegalStateException> {
      central.connectCentral("AA:BB:CC:DD:EE:FF", "abcd1234")
    }
    assertTrue(ex.message!!.contains("timed out"), "message: ${ex.message}")

    collector.cancelAndJoin()
  }

  @Test
  fun connectToUnscannedAddressThrowsIllegalState() = runTest {
    val facade = FakeBlueZFacade()
    val central = LinuxBleCentral(facade)

    val ex = assertFailsWith<IllegalStateException> {
      central.connectCentral("FF:EE:DD:CC:BB:AA", "abcd1234")
    }
    assertTrue(ex.message!!.contains("scan"), "message: ${ex.message}")
    assertTrue(facade.connectCalls.isEmpty())
  }

  @Test
  fun remoteDisconnectClosesSession() = runTest {
    val facade = FakeBlueZFacade()
    val central = LinuxBleCentral(facade)

    val events = mutableListOf<BlePeerEvent>()
    val collector = startScanning(facade, central, events)
    facade.deviceAppears("AA:BB:CC:DD:EE:FF", mapOf(BleConstants.SERVICE_UUID to "abcd1234".encodeToByteArray()))
    awaitEvents(events, count = 1)

    val session = central.connectCentral("AA:BB:CC:DD:EE:FF", "abcd1234")
    assertTrue(session.isOpen)

    facade.peerDisconnected()

    assertFalse(session.isOpen)
    assertNull(session.receiveChunk())

    collector.cancelAndJoin()
  }

  @Test
  fun scanCancellationStopsScanAndClearsListeners() = runTest {
    val facade = FakeBlueZFacade()
    val central = LinuxBleCentral(facade)

    val events = mutableListOf<BlePeerEvent>()
    val collector = startScanning(facade, central, events)
    collector.cancelAndJoin()

    assertEquals(1, facade.stopScanCount)
    assertNull(facade.foundListener)
    assertNull(facade.lostListener)
  }
}
