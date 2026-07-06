package com.carlom.klardrop.common.communication

import TestCoroutines
import com.carlom.klardrop.common.FakeMessagesRouter
import com.carlom.klardrop.common.communication.message.HandshakeMessage
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
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * Repro/regression for F9: [KlardropEncryptedTransport.runInitiatorHandshake] used to run with no
 * `withTimeout` around it (`Client.kt`, originally around line 270). The UKEY2 initiator sends its
 * first message and then BLOCKS reading the peer's reply
 * (`client.parseHandshakeMessage(readChannel.readByteArrayMessage())` in
 * [KlardropEncryptedTransport.runInitiatorHandshake]); a peer that completes the plaintext
 * greeting exchange but then stalls mid-UKEY2 used to hang the whole `connectTo` attempt until the
 * outer 15s `CONNECTION_WAIT_TIMEOUT` in `Messenger`, rather than failing this one dial quickly.
 *
 * This test stands up a raw fake peer that: accepts the TCP connection, completes the plaintext
 * Klardrop greeting exchange (exactly like [Server.handleKlardropConnection] does), and then goes
 * silent forever — never sending UKEY2 Message 2. It drives the PRODUCTION [ClientImpl.connectTo]
 * against that peer and asserts the dial fails within a bound close to
 * [UKEY2_HANDSHAKE_TIMEOUT_MS], not after the outer test timeout.
 */
class ClientUkey2HandshakeTimeoutTest {

  private class SingleKlardropPeer(
    deviceId: String,
    address: String,
    port: Int,
  ) : VisibleDevices {
    private val device = DiscoveryDevice(
      deviceInfo = DeviceInfo(
        deviceId = deviceId,
        name = "UKEY2-Stalling Peer",
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

  @Test
  fun connectToFailsBoundedWhenPeerStallsDuringUkey2() = runBlocking(Dispatchers.IO) {
    val selectorManager = SelectorManager(Dispatchers.IO)
    val serverSocket = aSocket(selectorManager).tcp().bind("127.0.0.1", 0)
    val port = (serverSocket.localAddress as InetSocketAddress).port

    val coroutines = TestCoroutines()
    val serializer = MessageSerializer(ProtoBuf, coroutines)
    val serverId = "ukey2srv"

    val acceptJob = launch(Dispatchers.IO) {
      val socket = serverSocket.accept()
      val readChannel = socket.openReadChannel()
      val writeChannel = socket.openWriteChannel(autoFlush = true)
      // Complete the plaintext Klardrop greeting exchange, exactly like
      // Server.handleKlardropConnection...
      readChannel.readMessage(serializer) as HandshakeMessage
      writeChannel.sendMessage(
        HandshakeMessage(deviceId = serverId, supportsEncryption = true),
        serializer,
      )
      // ...then go silent forever: never send UKEY2 Message 2. Without the fix, the client's
      // runInitiatorHandshake blocks on this read indefinitely.
      awaitCancellation()
    }

    val visibleDevices = SingleKlardropPeer(serverId, "127.0.0.1", port)
    val currentDeviceProvider = CurrentDeviceProvider(FixedIdPropertiesRepository("ukey2cli"))
    val trustManager = TrustManager(TrustCrypto(), InMemoryTrustStorage(), Clock(), currentDeviceProvider)

    val client = ClientImpl(
      connectionsPool = ConnectionsPoolImpl(),
      coroutines = coroutines,
      messagesRouter = FakeMessagesRouter(),
      serializer = serializer,
      visibleDevices = visibleDevices,
      currentDeviceProvider = currentDeviceProvider,
      trustManager = trustManager,
      bleTransport = null,
    )

    delay(200)

    val mark = TimeSource.Monotonic.markNow()
    val outcome = try {
      withTimeout(UKEY2_HANDSHAKE_TIMEOUT_MS + 5_000L) { client.connectTo(serverId) }
    } catch (e: TimeoutCancellationException) {
      acceptJob.cancel()
      serverSocket.close()
      selectorManager.close()
      fail(
        "connectTo did not return within ${UKEY2_HANDSHAKE_TIMEOUT_MS + 5_000L}ms — a peer that " +
          "completes the greeting exchange and then stalls mid-UKEY2 must not hang the whole " +
          "connection attempt. Wrap KlardropEncryptedTransport.runInitiatorHandshake in " +
          "withTimeout(UKEY2_HANDSHAKE_TIMEOUT_MS).",
      )
    }
    val elapsedMs = mark.elapsedNow().inWholeMilliseconds

    acceptJob.cancel()
    serverSocket.close()
    selectorManager.close()

    assertEquals(
      ConnectOutcome.Failed,
      outcome,
      "A peer that stalls mid-UKEY2 must resolve to Failed once the bounded handshake times out",
    )
    assertTrue(
      elapsedMs < UKEY2_HANDSHAKE_TIMEOUT_MS + 4_000L,
      "connectTo took ${elapsedMs}ms; expected ~UKEY2_HANDSHAKE_TIMEOUT_MS (${UKEY2_HANDSHAKE_TIMEOUT_MS}ms) " +
        "+ slack — the UKEY2 handshake phase against a stalled peer should be bounded.",
    )
    Unit
  }
}
