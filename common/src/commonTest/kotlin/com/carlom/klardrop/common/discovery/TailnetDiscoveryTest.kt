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
import com.carlom.klardrop.common.qrshare.LanAddressSelector
import com.carlom.klardrop.common.qrshare.ManualLanAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
      val prev = map[deviceId]
      map[deviceId] = KnownEndpoint(
        deviceId = deviceId,
        port = port,
        tailnetAddresses = tailnetAddresses.distinct().ifEmpty { prev?.tailnetAddresses.orEmpty() },
        lanAddresses = prev?.lanAddresses.orEmpty(),
      )
    }
    override suspend fun recordManual(deviceId: String, port: Int, lanAddress: String) {
      val prev = map[deviceId]
      val newLan = (listOf(lanAddress) + (prev?.lanAddresses.orEmpty())).distinct().take(4)
      map[deviceId] = KnownEndpoint(
        deviceId = deviceId,
        port = port,
        tailnetAddresses = prev?.tailnetAddresses.orEmpty(),
        lanAddresses = newLan,
      )
      val others = map.filterKeys { it != deviceId }
      others.filterValues { other -> other.lanAddresses.any { it in newLan } }.forEach { (id, other) ->
        map[id] = other.copy(lanAddresses = other.lanAddresses - newLan.toSet())
      }
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
  private open class Probes(protected val answers: Map<String, HandshakeMessage>) {
    val attempts = mutableListOf<String>()
    open suspend fun probe(address: String, port: Int): HandshakeMessage? {
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
    ownLan: List<String> = listOf("192.168.1.5"),
    ownLanFn: (suspend () -> List<String>)? = null,
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
    ownLanAddresses = ownLanFn ?: { ownLan },
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

  @Test
  fun manualLanTargetsProbedWithNoOwnTailnetAddressAndUntrusted() = runTest {
    val probes = Probes(mapOf("192.168.1.37:47470" to peer("manualpeer", listenPort = 47470)))
    val visible = Visible()
    val endpoints = InMemoryEndpoints(
      KnownEndpoint(
        deviceId = "manualpeer",
        port = 47470,
        lanAddresses = listOf("192.168.1.37"),
        tailnetAddresses = listOf("100.64.0.99"),
      )
    )
    discovery(
      probes,
      visible = visible,
      endpoints = endpoints,
      ownAddresses = emptyList(),
      trusted = emptySet(),
    ).probeOnce()

    // 192.168.1.37 was probed, but 100.64.0.99 was not (tailnet branch gated)
    assertEquals(listOf("192.168.1.37:47470"), probes.attempts)
    val (info, connection) = visible.added.single()
    assertEquals("manualpeer", info.deviceId)
    assertEquals(DeviceConnection.KlardropConnection("192.168.1.37", 47470), connection)
  }

  @Test
  fun tailnetBranchStillGatedWithoutOwnTailnetAddress() = runTest {
    val probes = Probes(emptyMap())
    val endpoints = InMemoryEndpoints(
      KnownEndpoint("tailpeer", 47470, tailnetAddresses = listOf("100.64.0.5")),
    )
    discovery(probes, endpoints = endpoints, ownAddresses = emptyList()).probeOnce()
    assertEquals(emptyList(), probes.attempts)
  }

  @Test
  fun addManuallyAddedOutcomeAndRecordsEndpoint() = runTest {
    val probes = Probes(mapOf("192.168.1.37:47470" to peer("newpeer", listenPort = 47470)))
    val visible = Visible()
    val endpoints = InMemoryEndpoints()
    val disco = discovery(probes, visible = visible, endpoints = endpoints)

    val result = disco.addManually(listOf("192.168.1.37"), 47470, expectedDeviceId = "newpeer")
    assertEquals(ManualAddResult.Added("Phone newpeer"), result)
    val (info, connection) = visible.added.single()
    assertEquals("newpeer", info.deviceId)
    assertEquals(DeviceConnection.KlardropConnection("192.168.1.37", 47470), connection)
    assertEquals(listOf("192.168.1.37"), endpoints.map["newpeer"]?.lanAddresses)
  }

  @Test
  fun addManuallyThisDeviceOutcome() = runTest {
    val probes = Probes(mapOf("192.168.1.37:47470" to peer("selfid01", listenPort = 47470)))
    val visible = Visible()
    val disco = discovery(probes, visible = visible)

    val result = disco.addManually(listOf("192.168.1.37"), 47470, expectedDeviceId = null)
    assertEquals(ManualAddResult.ThisDevice, result)
    assertTrue(visible.added.isEmpty())
  }

  @Test
  fun addManuallyWrongDeviceOutcome() = runTest {
    val probes = Probes(mapOf("192.168.1.37:47470" to peer("otherpeer", listenPort = 47470)))
    val visible = Visible()
    val disco = discovery(probes, visible = visible)

    val result = disco.addManually(listOf("192.168.1.37"), 47470, expectedDeviceId = "expectedpeer")
    assertEquals(ManualAddResult.WrongDevice, result)
    assertTrue(visible.added.isEmpty())
  }

  @Test
  fun addManuallyUnreachableOutcome() = runTest {
    val probes = Probes(emptyMap())
    val disco = discovery(probes)

    val result = disco.addManually(listOf("192.168.1.37"), 47470, expectedDeviceId = null)
    assertEquals(ManualAddResult.Unreachable("192.168.1.37:47470"), result)
  }

  @Test
  fun addManuallyAlreadyVisibleOutcome() = runTest {
    val probes = Probes(mapOf("192.168.1.37:47470" to peer("connectedpeer", listenPort = 47470)))
    val visible = Visible()
    val pool = Pool(connected = setOf("connectedpeer"))
    val endpoints = InMemoryEndpoints()
    val disco = discovery(probes, visible = visible, endpoints = endpoints, pool = pool)

    val result = disco.addManually(listOf("192.168.1.37"), 47470, expectedDeviceId = null)
    assertEquals(ManualAddResult.AlreadyVisible("Phone connectedpeer"), result)
    assertEquals(listOf("192.168.1.37"), endpoints.map["connectedpeer"]?.lanAddresses)
  }

  @Test
  fun addManuallyReturnsOnFastFirstAnswerWithoutWaitingForHangingCandidate() = runTest {
    val probes = object : Probes(mapOf("192.168.1.37:47470" to peer("newpeer", listenPort = 47470))) {
      override suspend fun probe(address: String, port: Int): HandshakeMessage? {
        if (address == "10.0.0.7") {
          attempts += "$address:$port"
          kotlinx.coroutines.awaitCancellation()
        }
        return super.probe(address, port)
      }
    }
    val result = discovery(probes).addManually(listOf("192.168.1.37", "10.0.0.7"), 47470, expectedDeviceId = null)
    assertEquals(ManualAddResult.Added("Phone newpeer"), result)
  }

  @Test
  fun addManuallyKeepsScanningPastOwnAnswer() = runTest {
    val probes = Probes(mapOf(
      "10.0.0.37:47470" to peer("selfid01", listenPort = 47470),
      "192.168.1.37:47470" to peer("newpeer", listenPort = 47470),
    ))
    val visible = Visible()
    val result = discovery(probes, visible = visible).addManually(listOf("10.0.0.37", "192.168.1.37"), 47470, null)
    assertEquals(ManualAddResult.Added("Phone newpeer"), result)
    assertEquals("newpeer", visible.added.single().first.deviceId)
  }

  @Test
  fun addManuallyOwnAnswerIsNotThisDeviceWhenAnotherDeviceWasExpected() = runTest {
    val probes = Probes(mapOf("192.168.1.37:47470" to peer("selfid01", listenPort = 47470)))
    val visible = Visible()
    val disco = discovery(probes, visible = visible)
    assertEquals(ManualAddResult.WrongDevice, disco.addManually(listOf("192.168.1.37"), 47470, "otherid"))
    assertEquals(ManualAddResult.ThisDevice, disco.addManually(listOf("192.168.1.37"), 47470, "selfid01"))
    assertTrue(visible.added.isEmpty())
  }

  @Test
  fun sweepDoesNotEnumerateOwnLanAddressesWithoutSavedLanEndpoints() = runTest {
    var calls = 0
    val endpoints = InMemoryEndpoints(KnownEndpoint("tailpeer", 47470, tailnetAddresses = listOf("100.64.0.5")))
    discovery(Probes(emptyMap()), endpoints = endpoints, ownLanFn = { calls++; listOf("192.168.1.5") }).probeOnce()
    assertEquals(0, calls)
    val withLan = InMemoryEndpoints(KnownEndpoint("p", 47470, lanAddresses = listOf("192.168.1.37")))
    discovery(Probes(emptyMap()), endpoints = withLan, ownLanFn = { calls++; listOf("192.168.1.5") }).probeOnce()
    assertEquals(1, calls)
  }

  @Test
  fun manualCodeIsProbedOnEveryOwnNetwork() = runTest {
    val probes = Probes(mapOf("192.168.1.37:47470" to peer("peer0037", listenPort = 47470)))
    val selector = object : LanAddressSelector {
      override suspend fun selectIpv4(): String? = "192.168.43.1"
      override suspend fun selectManualLanAddress() =
        ManualLanAddress("192.168.43.1", 24, listOf("192.168.43.1", "192.168.1.5"))
      override fun observeChanges(): Flow<String?> = emptyFlow()
    }
    val adder = ManualDeviceAdder(
      lanAddressSelector = selector,
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("selfid01")),
      serverPort = MutableStateFlow(55555),
      defaultListenPort = defaultPort,
      tailnetDiscovery = discovery(probes),
    )
    assertEquals(ManualAddResult.Added("Phone peer0037"), adder.add("37"))
    assertTrue("192.168.43.37:47470" in probes.attempts)
  }

  @Test
  fun addManuallyWorksWhileSweepHoldsLock() = runTest {
    val sweepStarted = kotlinx.coroutines.channels.Channel<Unit>(1)
    val sweepRelease = kotlinx.coroutines.channels.Channel<Unit>(1)
    val probes = object : Probes(
      mapOf(
        "100.64.0.2:$defaultPort" to peer("peer1", listenPort = 47470),
        "192.168.1.50:$defaultPort" to peer("manualpeer", listenPort = 47470),
      )
    ) {
      override suspend fun probe(address: String, port: Int): HandshakeMessage? {
        if (address == "100.64.0.2") {
          sweepStarted.send(Unit)
          sweepRelease.receive()
        }
        return super.probe(address, port)
      }
    }

    val visible = Visible()
    val endpoints = InMemoryEndpoints()
    val disco = discovery(probes, visible = visible, endpoints = endpoints, online = listOf("100.64.0.2", own))

    val sweepJob = backgroundScope.launch {
      disco.probeOnce()
    }

    // Wait until the sweep is actively running and holding the lock inside probeAddress
    sweepStarted.receive()

    // While sweep is running, addManually must succeed and not be blocked by the sweep's mutex
    val addResult = disco.addManually(listOf("192.168.1.50"), defaultPort, expectedDeviceId = null)
    assertEquals(ManualAddResult.Added("Phone manualpeer"), addResult)

    sweepRelease.send(Unit)
    sweepJob.join()
  }

  @Test
  fun sweepSkipsAlreadyReachableManualTargets() = runTest {
    val probes = Probes(mapOf("192.168.1.37:47470" to peer("manualpeer", listenPort = 47470)))
    val endpoints = InMemoryEndpoints(KnownEndpoint("manualpeer", 47470, lanAddresses = listOf("192.168.1.37")))
    discovery(probes, endpoints = endpoints, pool = Pool(connected = setOf("manualpeer"))).probeOnce()
    assertEquals(emptyList(), probes.attempts)
  }

  @Test
  fun sweepDoesNotProbeSavedLanAddressOnAnotherNetwork() = runTest {
    val probes = Probes(emptyMap())
    val endpoints = InMemoryEndpoints(KnownEndpoint("manualpeer", 47470, lanAddresses = listOf("10.20.1.37")))
    // Own address is on 192.168.x.x: the saved 10.20.x.x address is from some other network.
    discovery(probes, endpoints = endpoints, ownLan = listOf("192.168.1.5")).probeOnce()
    assertEquals(emptyList(), probes.attempts)
    // No LAN address at all: no manual targets either.
    discovery(probes, endpoints = endpoints, ownLan = emptyList()).probeOnce()
    assertEquals(emptyList(), probes.attempts)
    // Same /16: probed.
    discovery(probes, endpoints = endpoints, ownLan = listOf("10.20.99.5")).probeOnce()
    assertEquals(listOf("10.20.1.37:47470"), probes.attempts)
  }

  @Test
  fun sweepRefreshesSavedLanAddressOnlyForTheSameDevice() = runTest {
    val probes = Probes(mapOf("192.168.1.37:47470" to peer("manualpeer", listenPort = 47470)))
    val endpoints = InMemoryEndpoints(KnownEndpoint("manualpeer", 47470, lanAddresses = listOf("192.168.1.37")))
    var recorded = 0
    val counting = object : KnownEndpointsRepository by endpoints {
      override suspend fun recordManual(deviceId: String, port: Int, lanAddress: String) {
        recorded++
        endpoints.recordManual(deviceId, port, lanAddress)
      }
    }
    discovery(probes, endpoints = counting).probeOnce()
    assertEquals(1, recorded)
  }

  @Test
  fun multiIpQrUsesSecondAddressWhenFirstIsAnotherDevice() = runTest {
    val probes = Probes(
      mapOf(
        "192.168.1.50:47470" to peer("stranger1", listenPort = 47470),
        "10.0.0.7:47470" to peer("wantedid", listenPort = 47470),
      ),
    )
    val visible = Visible()
    val endpoints = InMemoryEndpoints()
    val result = discovery(probes, visible, endpoints).addManually(listOf("192.168.1.50", "10.0.0.7"), 47470, "wantedid")
    assertEquals(ManualAddResult.Added("Phone wantedid"), result)
    assertEquals(DeviceConnection.KlardropConnection("10.0.0.7", 47470), visible.added.single().second)
    assertEquals(listOf("10.0.0.7"), endpoints.map["wantedid"]?.lanAddresses)
  }

  @Test
  fun manualDeviceAdderEndToEndFromCode() = runTest {
    val probes = Probes(mapOf("192.168.1.37:47470" to peer("peer0037", listenPort = 47470)))
    val visible = Visible()
    val endpoints = InMemoryEndpoints()
    val selector = object : LanAddressSelector {
      override suspend fun selectIpv4(): String? = "192.168.1.5"
      override suspend fun selectManualLanAddress() = ManualLanAddress("192.168.1.5", 24, listOf("192.168.1.5"))
      override fun observeChanges(): Flow<String?> = emptyFlow()
    }
    val adder = ManualDeviceAdder(
      lanAddressSelector = selector,
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("selfid01")),
      serverPort = MutableStateFlow(55555), // our own live port must not be used for the peer
      defaultListenPort = defaultPort,
      tailnetDiscovery = discovery(probes, visible, endpoints),
    )
    assertEquals(ManualAddResult.Added("Phone peer0037"), adder.add("37"))
    assertEquals(listOf("192.168.1.37:47470"), probes.attempts)
    assertEquals(listOf("192.168.1.37"), endpoints.map["peer0037"]?.lanAddresses)
  }
}
