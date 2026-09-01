package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.FakeMessagesRouter
import com.carlom.klardrop.common.ble.BleChannelBridge
import com.carlom.klardrop.common.ble.BleConstants
import com.carlom.klardrop.common.ble.BlePeerEvent
import com.carlom.klardrop.common.ble.BleSession
import com.carlom.klardrop.common.communication.Connection
import com.carlom.klardrop.common.communication.ConnectionMessenger
import com.carlom.klardrop.common.communication.ConnectionsPoolImpl
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.readMessage
import com.carlom.klardrop.common.communication.sendMessage
import com.carlom.klardrop.common.discovery.CurrentDevice
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end Linux BLE transport test over a fake [BlueZFacade] that plays the radio:
 * the central's TX writes land on the peripheral's write callback, the peripheral's
 * notifications land on the central's notify sink. Drives the production
 * [LinuxBlueZTransport] through the full flow — advertise → peer found → central
 * connect → handshake exchange over the [BleChannelBridge]s (same order
 * `BleServerListener` / `Client.establishBleConnection` speak) → both sides end with a
 * pooled `Connection.Ble`. No D-Bus daemon, no adapter, no radio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinuxBleIntegrationTest {

  private val testDispatcher: TestDispatcher = StandardTestDispatcher()
  private val coroutines: Coroutines = object : Coroutines {
    override val ioDispatcher = testDispatcher
    override val mainDispatcher = testDispatcher
    override val cpuDispatcher = testDispatcher
    override val appScope = CoroutineScope(testDispatcher)
    override fun newScope() = CoroutineScope(testDispatcher)
    override fun newScope(context: kotlin.coroutines.CoroutineContext) =
      CoroutineScope(context)
  }
  private val serializer = MessageSerializer(ProtoBuf, coroutines)

  /**
   * One fake facade simulating both ends of the air: peripheral-side callbacks
   * (writes, subscriptions) and central-side callbacks (found, notify) are stored, and
   * the test triggers/routes them the way BlueZ would.
   */
  private class FakeBlueZRadio : BlueZFacade {
    companion object {
      const val PEER_ADDRESS = "AA:BB:CC:DD:EE:FF"
      const val CENTRAL_ID = "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF"
    }

    // Peripheral side
    @Volatile var writeListener: ((String, ByteArray) -> Unit)? = null
    @Volatile var subscriptionListener: ((String, Boolean) -> Unit)? = null
    val exportCompleted = CompletableDeferred<Unit>()
    var advertisement: CurrentDevice? = null
      private set
    var unregisterCount = 0
      private set

    // Central side
    @Volatile var foundListener: ((BlePeerEvent.Found) -> Unit)? = null
    @Volatile var lostListener: ((String) -> Unit)? = null
    var startScanCount = 0
      private set
    val connectCalls = mutableListOf<String>()
    private var notifySink: ((ByteArray) -> Unit)? = null

    override suspend fun probeCapability() = BlueZCapability(true, listOf("/org/bluez/hci0"))

    // mtu stays the interface default (DEFAULT_MTU minus the ATT header) — the
    // conservative no-MTU-property BlueZ case, so both bridges chunk at 20 bytes.

    override suspend fun exportApplication() {
      exportCompleted.complete(Unit)
    }

    override suspend fun unregisterApplication() {
      unregisterCount++
    }

    override suspend fun startAdvertising(currentDevice: CurrentDevice) {
      advertisement = currentDevice
    }

    override suspend fun startScan() {
      startScanCount++
    }

    override fun onCharacteristicWrite(listener: ((String, ByteArray) -> Unit)?) {
      writeListener = listener
    }

    override fun onCentralSubscription(listener: ((String, Boolean) -> Unit)?) {
      subscriptionListener = listener
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
      return BlueZPeerLink(
        mtu = BleConstants.DEFAULT_MTU - BleConstants.ATT_HEADER_SIZE,
        // Over the air: a central TX write arrives as a characteristic write on the peripheral.
        writeTx = { value -> writeListener?.invoke(CENTRAL_ID, value) },
      )
    }

    override suspend fun notifyValue(centralId: String, value: ByteArray) {
      // Over the air: a peripheral notify arrives as a GATT notification on the central.
      checkNotNull(notifySink)(value)
    }

    // Test-side triggers standing in for BlueZ D-Bus events.
    fun remoteAdvertises(shortDeviceId: String) {
      foundListener?.invoke(BlePeerEvent.Found(PEER_ADDRESS, shortDeviceId, localName = null, rssi = -40))
    }

    fun centralSubscribesToRx() = subscriptionListener?.invoke(CENTRAL_ID, true)
  }

  @Test
  fun advertiseScanConnectHandshakePoolsBleConnectionBothSides() =
    runTest(testDispatcher, timeout = 15.seconds) {
      val radio = FakeBlueZRadio()
      val transport = LinuxBlueZTransport(radio)

      assertTrue(transport.isSupported())

      // ── Peripheral side: host the GATT application and advertise ──
      val serverSessions = mutableListOf<BleSession>()
      val serveJob = launch { transport.serveGatt().toList(serverSessions) }
      radio.exportCompleted.await()

      val self = CurrentDevice(
        deviceId = "server12345",
        deviceName = "Linux Box",
        deviceType = DeviceType.DESKTOP,
        osType = OsType.LINUX,
      )
      transport.startAdvertising(self)
      assertEquals(self, radio.advertisement)

      // ── Central side: scan → Klardrop peer found with decoded short id ──
      val events = mutableListOf<BlePeerEvent>()
      val scanJob = launch { transport.scanForPeers().toList(events) }
      withTimeout(5.seconds) { while (radio.foundListener == null) yield() }
      radio.remoteAdvertises(shortDeviceId = "client678")
      withTimeout(5.seconds) { while (events.isEmpty()) yield() }
      val found = events.filterIsInstance<BlePeerEvent.Found>().single()
      assertEquals("client678", found.shortDeviceId)
      assertEquals(1, radio.startScanCount)

      // ── Central connects; BlueZ delivers StartNotify on RX → peripheral session ──
      val clientSession = transport.connectCentral(FakeBlueZRadio.PEER_ADDRESS, "client678")
      assertEquals(listOf(FakeBlueZRadio.PEER_ADDRESS), radio.connectCalls)
      radio.centralSubscribesToRx()
      withTimeout(5.seconds) { while (serverSessions.isEmpty()) yield() }
      val serverSession = serverSessions.single()

      assertEquals(BleConstants.DEFAULT_MTU - BleConstants.ATT_HEADER_SIZE, clientSession.mtu)
      assertEquals(BleConstants.DEFAULT_MTU - BleConstants.ATT_HEADER_SIZE, serverSession.mtu)
      assertTrue(clientSession.isOpen)
      assertTrue(serverSession.isOpen)

      // ── Bridges + handshake, in the exact order BleServerListener/Client speak ──
      coroutineScope {
        val serverBridge = BleChannelBridge(serverSession, this).start()
        val clientBridge = BleChannelBridge(clientSession, this).start()

        // Central speaks first with its rich handshake.
        val serverGot = async { serverBridge.readChannel.readMessage(serializer) }
        clientBridge.writeChannel.sendMessage(
          HandshakeMessage(
            deviceId = "client678",
            deviceName = "Pocket Phone",
            osType = OsType.ANDROID,
            deviceType = DeviceType.MOBILE,
            supportsEncryption = true,
          ),
          serializer,
        )
        val clientHandshake = serverGot.await() as HandshakeMessage
        assertEquals("client678", clientHandshake.deviceId)
        assertEquals("Pocket Phone", clientHandshake.deviceName)
        assertTrue(clientHandshake.supportsEncryption)

        // Server replies with its handshake so the central's read unblocks.
        val clientGot = async { clientBridge.readChannel.readMessage(serializer) }
        serverBridge.writeChannel.sendMessage(
          HandshakeMessage(
            deviceId = "server12",
            deviceName = "Linux Box",
            osType = OsType.LINUX,
            deviceType = DeviceType.DESKTOP,
            supportsEncryption = true,
          ),
          serializer,
        )
        val serverHandshake = clientGot.await() as HandshakeMessage
        assertEquals("server12", serverHandshake.deviceId)
        assertTrue(serverHandshake.supportsEncryption)

        // ── Both sides pool a Connection.Ble, as the production listeners do ──
        val serverPool = ConnectionsPoolImpl()
        val clientPool = ConnectionsPoolImpl()
        serverPool.updateConnection(
          "client678",
          ConnectionMessenger(
            coroutines = coroutines,
            connection = Connection.Ble(serverSession, "client678"),
            messagesRouter = FakeMessagesRouter(),
            readChannel = serverBridge.readChannel,
            writeChannel = serverBridge.writeChannel,
            messageSerializer = serializer,
          ),
        )
        clientPool.updateConnection(
          "server12",
          ConnectionMessenger(
            coroutines = coroutines,
            connection = Connection.Ble(clientSession, "server12"),
            messagesRouter = FakeMessagesRouter(),
            readChannel = clientBridge.readChannel,
            writeChannel = clientBridge.writeChannel,
            messageSerializer = serializer,
          ),
        )
        assertTrue(serverPool.getConnection("client678")!!.isBleTransport)
        assertTrue(clientPool.getConnection("server12")!!.isBleTransport)

        // Drop the link both ways so the bridge pumps end and the scope completes.
        serverSession.close()
        clientSession.close()
      }

      // ── Teardown mirrors production flow cancellation ──
      scanJob.cancelAndJoin()
      serveJob.cancelAndJoin()
      assertEquals(1, radio.unregisterCount, "cancelling serveGatt must unregister the GATT application")
    }
}
