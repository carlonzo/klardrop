package com.carlom.klardrop.common.discovery

import FakeLocalPropertiesRepository
import TestCoroutines
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.carlom.klardrop.common.FakeMessagesRouter
import com.carlom.klardrop.common.communication.ClientImpl
import com.carlom.klardrop.common.communication.ConnectOutcome
import com.carlom.klardrop.common.communication.ConnectionsPoolImpl
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.createTestServer
import com.carlom.klardrop.common.mdns.FakeVisibleDevices
import com.carlom.klardrop.common.persistence.KnownEndpoint
import com.carlom.klardrop.common.persistence.KnownEndpointsRepository
import com.carlom.klardrop.common.persistence.KnownEndpointsRepositoryImpl
import com.carlom.klardrop.common.trust.InMemoryTrustStorage
import com.carlom.klardrop.common.trust.TrustCrypto
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.utils.DeviceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days

class TailnetEndpointsIntegrationTest {

  private class Recorder : KnownEndpointsRepository {
    val recorded = mutableListOf<KnownEndpoint>()
    override suspend fun endpoints() = recorded.associateBy { it.deviceId }
    override suspend fun record(deviceId: String, port: Int, tailnetAddresses: List<String>) {
      val prev = recorded.firstOrNull { it.deviceId == deviceId }
      recorded.removeAll { it.deviceId == deviceId }
      recorded += KnownEndpoint(
        deviceId,
        port,
        tailnetAddresses,
        lanAddresses = prev?.lanAddresses.orEmpty(),
      )
    }
    override suspend fun recordManual(deviceId: String, port: Int, lanAddress: String) {
      val prev = recorded.firstOrNull { it.deviceId == deviceId }
      recorded.removeAll { it.deviceId == deviceId }
      recorded += KnownEndpoint(
        deviceId,
        port,
        tailnetAddresses = prev?.tailnetAddresses.orEmpty(),
        lanAddresses = listOf(lanAddress),
      )
    }
    override suspend fun fallbackListenPort() = 0
    override suspend fun saveFallbackListenPort(port: Int) = Unit
  }

  private fun client(
    selfId: String,
    visibleDevices: VisibleDevices,
    pool: ConnectionsPoolImpl,
    knownEndpoints: KnownEndpointsRepository? = null,
  ): ClientImpl {
    val coroutines = TestCoroutines()
    val currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(selfId))
    return ClientImpl(
      connectionsPool = pool,
      coroutines = coroutines,
      messagesRouter = FakeMessagesRouter(),
      serializer = MessageSerializer(ProtoBuf, coroutines),
      visibleDevices = visibleDevices,
      currentDeviceProvider = currentDeviceProvider,
      trustManager = TrustManager(TrustCrypto(), InMemoryTrustStorage(), com.carlom.klardrop.common.utils.Clock(), currentDeviceProvider),
      serverPort = MutableStateFlow(41000),
      knownEndpoints = knownEndpoints,
    )
  }

  @Test
  fun probeLearnsWhoListensWithoutTheServerRecordingUs() = runBlocking(Dispatchers.IO) {
    val serverVisible = FakeVisibleDevices()
    val serverPool = ConnectionsPoolImpl()
    val serverEndpoints = Recorder()
    val serverPort = MutableStateFlow(0)
    val server = createTestServer(
      connectionsPool = serverPool,
      localPropertiesRepository = FakeLocalPropertiesRepository("probesrv"),
      visibleDevices = serverVisible,
      knownEndpoints = serverEndpoints,
      serverPort = serverPort,
    )
    val port = server.startServer().port
    try {
      val answer = assertNotNull(client("probecli", FakeVisibleDevices(), ConnectionsPoolImpl()).probeHandshake("127.0.0.1", port))
      assertEquals("probesrv", answer.deviceId)
      assertEquals(port, answer.listenPort)

      delay(300) // let the server notice the close
      assertFalse(serverPool.isAvailable("probecli"), "a probe must never become a pooled connection")
      assertFalse(serverVisible.isDeviceVisible("probecli"), "a probe must not add us to the peer's devices")
      assertEquals(emptyList(), serverEndpoints.recorded, "an unauthenticated probe must not be remembered")
    } finally {
      server.stopServer()
    }
  }

  @Test
  fun probeOfAPortWithNothingListeningReturnsNull() = runBlocking(Dispatchers.IO) {
    val freePort = java.net.ServerSocket(0).use { it.localPort }
    assertNull(client("probecli", FakeVisibleDevices(), ConnectionsPoolImpl()).probeHandshake("127.0.0.1", freePort))
  }

  @Test
  fun authenticatedConnectionRecordsTheListenPortOnBothSides() = runBlocking(Dispatchers.IO) {
    val serverPool = ConnectionsPoolImpl()
    val serverEndpoints = Recorder()
    val serverPort = MutableStateFlow(0)
    val server = createTestServer(
      connectionsPool = serverPool,
      localPropertiesRepository = FakeLocalPropertiesRepository("recsrv01"),
      knownEndpoints = serverEndpoints,
      serverPort = serverPort,
    )
    val port = server.startServer().port
    val clientVisible = FakeVisibleDevices()
    clientVisible.onNewDeviceVisible(
      DeviceInfo("recsrv01", "Server", DeviceType.DESKTOP),
      DeviceConnection.KlardropConnection("127.0.0.1", port),
    )
    val clientPool = ConnectionsPoolImpl()
    val clientEndpoints = Recorder()
    val client = client("reccli01", clientVisible, clientPool, clientEndpoints)
    delay(200) // ClientImpl mirrors visibleDevices through an eagerly started stateIn
    try {
      val outcome = withTimeout(15_000) { client.connectTo("recsrv01") }
      assertEquals(ConnectOutcome.Connected, outcome)
      delay(300)

      // Loopback isn't a tailnet address, so only the peers' own advertised ones are kept.
      val ownTailnet = localTailnetIpv4s()
      assertEquals(listOf(KnownEndpoint("recsrv01", port, ownTailnet)), clientEndpoints.recorded)
      assertEquals(listOf(KnownEndpoint("reccli01", 41000, ownTailnet)), serverEndpoints.recorded)
    } finally {
      clientPool.closeAllConnections()
      serverPool.closeAllConnections()
      server.stopServer()
    }
  }

  @Test
  fun repositoryKeepsAddressesMovesReassignedOnesAndForgetsOldPeers() = runBlocking {
    val file = Files.createTempDirectory("known-endpoints").resolve("known_endpoints.preferences_pb")
    var now = 100.days.inWholeMilliseconds
    val repo = KnownEndpointsRepositoryImpl(
      PreferenceDataStoreFactory.createWithPath(produceFile = { file.toOkioPath() }),
      TestCoroutines(),
      nowMs = { now },
    )

    repo.record("phone001", 47470, listOf("100.64.0.2"))
    // Met again on the LAN with Tailscale off: port updates, tailnet address is kept.
    repo.record("phone001", 50000, emptyList())
    assertEquals(KnownEndpoint("phone001", 50000, listOf("100.64.0.2"), now), repo.endpoints()["phone001"])

    // Same IP now belongs to someone else (identity reset / reassigned): it moves.
    repo.record("phone002", 47470, listOf("100.64.0.2"))
    assertEquals(emptyList(), repo.endpoints().getValue("phone001").tailnetAddresses)
    assertEquals(listOf("100.64.0.2"), repo.endpoints().getValue("phone002").tailnetAddresses)

    now += 31.days.inWholeMilliseconds
    repo.record("desk0001", 47470, listOf("100.64.0.9"))
    assertEquals(setOf("desk0001"), repo.endpoints().keys, "peers unseen for over 30 days are not probed")

    assertEquals(0, repo.fallbackListenPort())
    repo.saveFallbackListenPort(51234)
    assertEquals(51234, repo.fallbackListenPort())
  }

  @Test
  fun repositoryPreservesLanAddressesAndEvictsOnRecordManual() = runBlocking {
    val file = Files.createTempDirectory("known-endpoints-manual").resolve("known_endpoints.preferences_pb")
    var now = 100.days.inWholeMilliseconds
    val repo = KnownEndpointsRepositoryImpl(
      PreferenceDataStoreFactory.createWithPath(produceFile = { file.toOkioPath() }),
      TestCoroutines(),
      nowMs = { now },
    )

    // Manual add records peer1 with LAN address
    repo.recordManual("peer1", 47470, "192.168.1.10")
    val ep1 = repo.endpoints().getValue("peer1")
    assertEquals(listOf("192.168.1.10"), ep1.lanAddresses)
    assertEquals(47470, ep1.port)

    // Later, authenticated record() with tailnet addresses must preserve lanAddresses
    repo.record("peer1", 47470, listOf("100.64.0.10"))
    val ep1Updated = repo.endpoints().getValue("peer1")
    assertEquals(listOf("192.168.1.10"), ep1Updated.lanAddresses)
    assertEquals(listOf("100.64.0.10"), ep1Updated.tailnetAddresses)

    // Another peer gets recorded with the same LAN address (DHCP reuse eviction)
    repo.recordManual("peer2", 47470, "192.168.1.10")
    val ep2 = repo.endpoints().getValue("peer2")
    assertEquals(listOf("192.168.1.10"), ep2.lanAddresses)
    // peer1's lanAddresses must have dropped 192.168.1.10
    val ep1AfterEvict = repo.endpoints().getValue("peer1")
    assertEquals(emptyList(), ep1AfterEvict.lanAddresses)
  }

  @Test
  fun lanAddressesExpireAfter14DaysAndRecordKeepsTheirAge() = runBlocking {
    val file = Files.createTempDirectory("known-endpoints-lan-ttl").resolve("known_endpoints.preferences_pb")
    var now = 100.days.inWholeMilliseconds
    val repo = KnownEndpointsRepositoryImpl(
      PreferenceDataStoreFactory.createWithPath(produceFile = { file.toOkioPath() }),
      TestCoroutines(),
      nowMs = { now },
    )
    repo.recordManual("peer1", 47470, "192.168.1.10")
    now += 10.days.inWholeMilliseconds
    // A tailnet/mDNS handshake refreshes lastSeenMs but must not refresh the LAN address's age.
    repo.record("peer1", 47470, listOf("100.64.0.10"))
    assertEquals(listOf("192.168.1.10"), repo.endpoints().getValue("peer1").lanAddresses)
    now += 5.days.inWholeMilliseconds
    val expired = repo.endpoints().getValue("peer1")
    assertEquals(emptyList(), expired.lanAddresses)
    assertEquals(listOf("100.64.0.10"), expired.tailnetAddresses)

    // Re-adding after expiry must not resurrect stale addresses.
    repo.recordManual("peer1", 47470, "192.168.1.11")
    assertEquals(listOf("192.168.1.11"), repo.endpoints().getValue("peer1").lanAddresses)
  }

  @Test
  fun recordManualCapsLanAddressesAtFour() = runBlocking {
    val file = Files.createTempDirectory("known-endpoints-lan-cap").resolve("known_endpoints.preferences_pb")
    val repo = KnownEndpointsRepositoryImpl(
      PreferenceDataStoreFactory.createWithPath(produceFile = { file.toOkioPath() }),
      TestCoroutines(),
      nowMs = { 100.days.inWholeMilliseconds },
    )
    for (i in 1..6) repo.recordManual("peer1", 47470, "192.168.1.$i")
    assertEquals(listOf("192.168.1.6", "192.168.1.5", "192.168.1.4", "192.168.1.3"), repo.endpoints().getValue("peer1").lanAddresses)
  }
}
