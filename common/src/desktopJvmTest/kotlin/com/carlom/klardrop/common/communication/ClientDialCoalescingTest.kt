package com.carlom.klardrop.common.communication

import TestCoroutines
import com.carlom.klardrop.common.FakeMessagesRouter
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.KlardropProperties
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.trust.InMemoryTrustStorage
import com.carlom.klardrop.common.trust.TrustCrypto
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Repro/regression for F8: [ClientImpl.connectTo] checks [ConnectionsPool.isAvailable] once and
 * then dials (`Client.kt`, originally around line 114-119). [EagerReachabilityConnector] (a probe)
 * and [com.carlom.klardrop.common.communication.Messenger.send] (a user action) can both call
 * `connectTo(sameDeviceId)` concurrently; both pass the `isAvailable` check (neither is connected
 * yet) and both dial independently. Both client-side messengers are `initiatedByUs = true`, so
 * [ConnectionsPoolImpl.updateConnection]'s "same direction => reconnect" branch treats the SECOND
 * completed handshake as a reconnect and closes the FIRST — already pooled and reported as
 * [ConnectOutcome.Connected] — socket out from under whoever is using it.
 *
 * The fix is per-device dial coalescing in [ClientImpl]: a second concurrent `connectTo` for the
 * same device awaits the first (in-flight) attempt's outcome instead of dialing again.
 *
 * This test drives two concurrent `connectTo(sameDeviceId)` calls on ONE [ClientImpl] instance
 * against a single real (production [Server]) peer, and counts how many times the SERVER'S
 * [ConnectionsPool.updateConnection] fires. Without coalescing, the client dials TWICE, so the
 * server accepts two full handshakes and calls `updateConnection` twice (closing the first).
 * With coalescing, exactly ONE dial happens, so `updateConnection` fires exactly once.
 */
class ClientDialCoalescingTest {

  /** Exposes exactly one peer whose only Klardrop endpoint is the given address/port. */
  private class SingleKlardropPeer(
    private val deviceId: String,
    address: String,
    port: Int,
  ) : VisibleDevices {
    private val device = DiscoveryDevice(
      deviceInfo = DeviceInfo(
        deviceId = deviceId,
        name = "Coalescing Test Peer",
        deviceType = DeviceType.DESKTOP,
        osType = OsType.LINUX,
      ),
      deviceConnections = listOf(DeviceConnection.KlardropConnection(address, port)),
      lastSeenTimestamp = 0L,
    )
    private val flow = MutableStateFlow(mapOf(deviceId to device))
    override val visibleDevices: StateFlow<Map<String, DiscoveryDevice>> = flow

    override suspend fun onNewDeviceVisible(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection) = Unit
    override fun isDeviceVisible(deviceId: String) = flow.value.containsKey(deviceId)
    override fun getDevice(deviceId: String) = flow.value[deviceId]
    override fun cachedNameFor(deviceId: String) = null
    override fun touchLastSeen(deviceId: String) = Unit
    override fun onDeviceLost(deviceId: String) { flow.value = emptyMap() }
    override fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection) = Unit
    override fun invalidateKlardropEndpoint(deviceId: String, address: String, port: Int) = Unit
    override fun findDeviceByAddress(address: InetSocketAddress): DiscoveryDevice? = null
  }

  private class FixedIdPropertiesRepository(deviceId: String) : LocalPropertiesRepository {
    override val properties = MutableStateFlow(KlardropProperties(deviceId))
    override suspend fun getProperty() = properties.value
    override suspend fun save(properties: KlardropProperties) { this.properties.value = properties }
    override suspend fun saveCustomDeviceName(customDeviceName: String?) {
      properties.value = properties.value.copy(customDeviceName = customDeviceName)
    }
    override suspend fun saveBackgroundDiscoveryEnabled(enabled: Boolean) {
      properties.value = properties.value.copy(backgroundDiscoveryEnabled = enabled)
    }
  }

  /** Delegates to a real [ConnectionsPoolImpl] but counts [updateConnection] calls. */
  private class CountingConnectionsPool(
    private val delegate: ConnectionsPool = ConnectionsPoolImpl(),
  ) : ConnectionsPool by delegate {
    val updateConnectionCalls = AtomicInteger(0)
    override suspend fun updateConnection(deviceId: String, connectionMessenger: ConnectionMessenger) {
      updateConnectionCalls.incrementAndGet()
      delegate.updateConnection(deviceId, connectionMessenger)
    }
  }

  @Test
  fun concurrentConnectToForSameDeviceCoalescesIntoOneDial() = runBlocking(Dispatchers.IO) {
    val clientId = "coalcli1"
    val serverId = "coalsrv1"

    val serverPool = CountingConnectionsPool()
    val server = createTestServer(
      connectionsPool = serverPool,
      localPropertiesRepository = FixedIdPropertiesRepository(serverId),
    )
    val serverConfig = server.startServer()

    val clientPool = ConnectionsPoolImpl()
    val visibleDevices = SingleKlardropPeer(serverId, "127.0.0.1", serverConfig.port)
    val currentDeviceProvider = CurrentDeviceProvider(FixedIdPropertiesRepository(clientId))
    val trustManager = TrustManager(TrustCrypto(), InMemoryTrustStorage(), Clock(), currentDeviceProvider)
    val coroutines = TestCoroutines()

    val client = ClientImpl(
      connectionsPool = clientPool,
      coroutines = coroutines,
      messagesRouter = FakeMessagesRouter(),
      serializer = MessageSerializer(ProtoBuf, coroutines),
      visibleDevices = visibleDevices,
      currentDeviceProvider = currentDeviceProvider,
      trustManager = trustManager,
      bleTransport = null,
    )

    // ClientImpl mirrors visibleDevices via stateIn(Eagerly); give the sharing coroutine a
    // moment to copy the already-populated StateFlow value before we dial.
    delay(200)

    try {
      // Two concurrent connectTo() calls for the SAME device on the SAME ClientImpl instance —
      // exactly the ERC-probe-vs-Messenger.send race described in F8.
      val first = async(Dispatchers.IO) { client.connectTo(serverId) }
      val second = async(Dispatchers.IO) { client.connectTo(serverId) }
      val (outcomeA, outcomeB) = awaitAll(first, second)

      assertEquals(ConnectOutcome.Connected, outcomeA, "First concurrent connectTo() must report Connected")
      assertEquals(ConnectOutcome.Connected, outcomeB, "Second concurrent connectTo() must report Connected")

      assertEquals(
        1,
        serverPool.updateConnectionCalls.get(),
        "Coalescing should result in exactly ONE TCP dial reaching the server — " +
          "got ${serverPool.updateConnectionCalls.get()} accepted handshakes. Without per-device " +
          "dial coalescing, both concurrent connectTo() calls dial independently and the server " +
          "sees two full handshakes (the second of which closes the first's socket).",
      )

      val pooled = clientPool.getConnection(serverId)
      assertNotNull(pooled, "The client's connectionsPool must hold a pooled connection for $serverId")
      assertFalse(pooled.isClosed(), "The pooled connection must not be closed")
    } finally {
      // Both sides' acceptIncomingMessages() read loops spin on FakeMessagesRouter's no-op
      // onMessageIncoming (it never actually reads, so the loop never blocks) — left running,
      // they busy-log forever and can OOM the test JVM. Closing the sockets makes each loop's
      // `!readChannel.isClosedForRead` guard trip so it exits.
      clientPool.closeAllConnections()
      serverPool.closeAllConnections()
      server.stopServer()
    }
  }
}
