package com.carlom.klardrop.common.discovery

import FakeLocalPropertiesRepository
import TestCoroutines
import com.carlom.klardrop.common.communication.ConnectionsPool
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.persistence.KnownEndpoint
import com.carlom.klardrop.common.persistence.KnownEndpointsRepository
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import io.ktor.network.sockets.InetSocketAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TailnetDiscoveryTest {

  private val own = "100.64.0.1"
  private val defaultPort = 47470
  private val now = 1_000_000_000L

  private class InMemoryEndpoints(vararg entries: KnownEndpoint) : KnownEndpointsRepository {
    val map = entries.associateBy { it.deviceId }.toMutableMap()
    override suspend fun endpoints() = map.toMap()
    override suspend fun record(deviceId: String, port: Int, tailnetAddresses: List<String>) {
      map[deviceId] = KnownEndpoint(deviceId, port, tailnetAddresses)
    }
    override suspend fun fallbackListenPort() = 0
    override suspend fun saveFallbackListenPort(port: Int) = Unit
  }

  private class Pool(val connected: Set<String> = emptySet()) : ConnectionsPool {
    override val reachability: StateFlow<Map<String, Reachability>> = MutableStateFlow(emptyMap())
    override suspend fun isAvailable(deviceId: String) = deviceId in connected
    override suspend fun closeAllConnections() = Unit
    override suspend fun closeConnection(deviceId: String) = Unit
    override fun markProbing(deviceId: String) = Unit
    override fun markUnreachable(deviceId: String) = Unit
  }

  private class Visible(initial: List<DiscoveryDevice> = emptyList()) : VisibleDevices {
    private val flow = MutableStateFlow(initial.associateBy { it.deviceInfo.deviceId })
    val added = mutableListOf<Pair<DeviceInfo, DeviceConnection>>()
    override val visibleDevices: StateFlow<Map<String, DiscoveryDevice>> = flow
    override suspend fun onNewDeviceVisible(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection) {
      added += deviceInfo to deviceConnection
    }
    override fun isDeviceVisible(deviceId: String) = deviceId in flow.value
    override fun getDevice(deviceId: String) = flow.value[deviceId]
    override fun cachedNameFor(deviceId: String): String? = null
    override fun touchLastSeen(deviceId: String) = Unit
    override fun onDeviceLost(deviceId: String) = Unit
    override fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection) = Unit
    override fun findDeviceByAddress(address: InetSocketAddress): DiscoveryDevice? = null
    override fun invalidateKlardropEndpoint(deviceId: String, address: String, port: Int) = Unit
  }

  private fun peer(id: String, listenPort: Int) = HandshakeMessage(
    deviceId = id, deviceName = "Phone $id", osType = OsType.ANDROID, deviceType = DeviceType.MOBILE,
    supportsEncryption = true, listenPort = listenPort,
  )

  /** Peers that answer, keyed by "address:port". Records every probe attempt. */
  private class Probes(private val answers: Map<String, HandshakeMessage>) {
    val attempts = mutableListOf<String>()
    suspend fun probe(address: String, port: Int): HandshakeMessage? {
      attempts += "$address:$port"
      return answers["$address:$port"]
    }
  }

  private fun discovery(
    probes: Probes,
    visible: Visible = Visible(),
    endpoints: KnownEndpointsRepository = InMemoryEndpoints(),
    pool: Pool = Pool(),
    ownAddresses: List<String> = listOf(own),
    online: List<String>? = null,
    trusted: Set<String>? = null,
  ) = TailnetDiscovery(
    coroutines = TestCoroutines(),
    visibleDevices = visible,
    connectionsPool = pool,
    currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("selfid01")),
    knownEndpoints = endpoints,
    defaultPort = defaultPort,
    probe = probes::probe,
    isTrusted = { trusted == null || it in trusted },
    nowMs = { now },
    localTailnetAddresses = { ownAddresses },
    tailnetPeers = { online },
  )

  @Test
  fun doesNothingWithoutATailnetAddressOfOurOwn() = runTest {
    val probes = Probes(emptyMap())
    discovery(probes, ownAddresses = emptyList(), online = listOf("100.64.0.2")).probeOnce()
    assertEquals(emptyList(), probes.attempts)
  }

  @Test
  fun unknownOnlinePeerIsProbedOnTheDefaultPortAndAddedWithItsListenPort() = runTest {
    val probes = Probes(mapOf("100.64.0.2:$defaultPort" to peer("phone001", listenPort = 47470)))
    val visible = Visible()
    discovery(probes, visible, online = listOf("100.64.0.2", own)).probeOnce()

    assertEquals(listOf("100.64.0.2:$defaultPort"), probes.attempts, "own address must not be probed")
    val (info, connection) = visible.added.single()
    assertEquals("phone001", info.deviceId)
    assertEquals("Phone phone001", info.name)
    assertEquals(DeviceConnection.KlardropConnection("100.64.0.2", 47470), connection)
  }

  @Test
  fun rememberedPeerIsProbedOnItsKnownPortThenTheDefault() = runTest {
    val probes = Probes(mapOf("100.64.0.3:$defaultPort" to peer("desk0001", listenPort = 51000)))
    val visible = Visible()
    val endpoints = InMemoryEndpoints(KnownEndpoint("desk0001", 51000, listOf("100.64.0.3")))
    // Mobile: Tailscale can't tell us who's online, so remembered peers are all we have.
    discovery(probes, visible, endpoints, online = null).probeOnce()

    assertEquals(listOf("100.64.0.3:51000", "100.64.0.3:$defaultPort"), probes.attempts)
    assertEquals(DeviceConnection.KlardropConnection("100.64.0.3", 51000), visible.added.single().second)
  }

  @Test
  fun withoutTailscalesPeerListOnlyPairedDevicesAreProbed() = runTest {
    val probes = Probes(emptyMap())
    val endpoints = InMemoryEndpoints(
      KnownEndpoint("paired01", 47470, listOf("100.64.0.3")),
      KnownEndpoint("stranger", 47470, listOf("100.99.0.7")),
    )
    discovery(probes, endpoints = endpoints, online = null, trusted = setOf("paired01")).probeOnce()
    assertEquals(listOf("100.64.0.3:47470"), probes.attempts)
  }

  @Test
  fun rememberedPeerThatTailscaleReportsOfflineIsSkipped() = runTest {
    val probes = Probes(emptyMap())
    val endpoints = InMemoryEndpoints(KnownEndpoint("desk0001", 51000, listOf("100.64.0.3")))
    discovery(probes, endpoints = endpoints, online = emptyList()).probeOnce()
    assertEquals(emptyList(), probes.attempts)
  }

  @Test
  fun connectedOrFreshLanPeersAreSkippedButStaleLanPeersAreProbed() = runTest {
    val probes = Probes(emptyMap())
    val lan = DeviceConnection.KlardropConnection("192.168.1.20", 47470)
    fun device(id: String, lastSeen: Long) =
      DiscoveryDevice(DeviceInfo(id, id, DeviceType.MOBILE), listOf(lan), lastSeenTimestamp = lastSeen)
    val endpoints = InMemoryEndpoints(
      KnownEndpoint("connectd", 47470, listOf("100.64.0.4")),
      KnownEndpoint("freshlan", 47470, listOf("100.64.0.5")),
      KnownEndpoint("stalelan", 47470, listOf("100.64.0.6")),
    )
    val visible = Visible(
      listOf(device("freshlan", lastSeen = now - 10_000), device("stalelan", lastSeen = now - 10 * 60_000)),
    )
    discovery(probes, visible, endpoints, pool = Pool(connected = setOf("connectd"))).probeOnce()

    assertEquals(listOf("100.64.0.6:47470"), probes.attempts)
  }

  @Test
  fun answerFromOurselvesIsIgnored() = runTest {
    val probes = Probes(mapOf("100.64.0.9:$defaultPort" to peer("selfid01", listenPort = defaultPort)))
    val visible = Visible()
    discovery(probes, visible, online = listOf("100.64.0.9")).probeOnce()
    assertTrue(visible.added.isEmpty())
  }

  @Test
  fun carrierCgnatAddressesAreNotTailnetAddresses() {
    // What the Pixel reported: its Tailscale tun plus two carrier CGNAT addresses on rmnet.
    val ifaces = listOf(
      "tun0" to "100.126.181.103",
      "rmnet_data1" to "100.99.190.222",
      "rmnet_data2" to "100.124.250.37",
      "wlan0" to "192.168.1.30",
      "tailscale0" to "100.119.219.68",
      "utun4" to "100.94.243.66",
      "pdp_ip0" to "100.70.1.2",
    )
    assertEquals(listOf("100.126.181.103", "100.119.219.68", "100.94.243.66"), tailnetIpv4sOf(ifaces))
  }

  @Test
  fun parsesOnlinePeersOfTheSameUserFromTailscaleStatus() {
    val json = """
      {
        "Self": { "UserID": 7, "TailscaleIPs": ["100.64.0.1"] },
        "Peer": {
          "a": { "UserID": 7, "Online": true, "TailscaleIPs": ["100.64.0.2", "fd7a:115c:a1e0::2"] },
          "b": { "UserID": 7, "Online": false, "TailscaleIPs": ["100.64.0.3"] },
          "c": { "UserID": 9, "Online": true, "TailscaleIPs": ["100.64.0.4"] }
        }
      }
    """.trimIndent()
    assertEquals(listOf("100.64.0.2"), parseTailscaleStatus(json))
    assertEquals(emptyList(), parseTailscaleStatus("not json"))
    assertEquals(emptyList(), parseTailscaleStatus("""{"BackendState":"Stopped"}"""))
  }
}
