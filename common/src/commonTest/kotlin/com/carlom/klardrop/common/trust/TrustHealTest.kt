package com.carlom.klardrop.common.trust

import FakeLocalPropertiesRepository
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.utils.Clock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TrustHealTest {

  private val aliceId = "alice001"
  private val bobId = "bob00002"

  private fun newManager(deviceId: String) = TrustManager(
    crypto = TrustCrypto(),
    storage = InMemoryTrustStorage(),
    clock = Clock(),
    currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository(deviceId)),
  )

  @Test
  fun noRevocationWhenPeerDoesNotClaimTrust() = runTest {
    val alice = newManager(aliceId)
    alice.initialize()
    assertNull(revocationIfPeerStale(alice, bobId, peerClaimsTrust = false))
  }

  @Test
  fun noRevocationWhenBothStillTrustEachOther() = runTest {
    val alice = newManager(aliceId)
    val bob = newManager(bobId)
    val response = bob.createPairingAcceptance(alice.createPairingRequest(bobId).getOrThrow()).getOrThrow()
    alice.finalizePairing(response)
    assertNull(revocationIfPeerStale(alice, bobId, peerClaimsTrust = true))
  }

  @Test
  fun noRevocationWhilePairingSessionIsOpen() = runTest {
    val alice = newManager(aliceId)
    alice.createPairingRequest(bobId).getOrThrow()
    assertNull(
      revocationIfPeerStale(alice, bobId, peerClaimsTrust = true),
      "Acceptor may already trust us before our finalizePairing runs",
    )
  }

  @Test
  fun supersededTrustedIdsDropsOldIdAtSameAddress() {
    val old = com.carlom.klardrop.common.discovery.DiscoveryDevice(
      deviceInfo = com.carlom.klardrop.common.discovery.DeviceInfo(
        deviceId = "oldid001",
        name = "Phone",
        deviceType = com.carlom.klardrop.common.utils.DeviceType.MOBILE,
      ),
      deviceConnections = listOf(
        com.carlom.klardrop.common.discovery.DeviceConnection.KlardropConnection("10.0.0.8", 1),
      ),
      lastSeenTimestamp = 1L,
    )
    val fresh = com.carlom.klardrop.common.discovery.DiscoveryDevice(
      deviceInfo = com.carlom.klardrop.common.discovery.DeviceInfo(
        deviceId = "newid002",
        name = "Phone",
        deviceType = com.carlom.klardrop.common.utils.DeviceType.MOBILE,
      ),
      deviceConnections = listOf(
        com.carlom.klardrop.common.discovery.DeviceConnection.KlardropConnection("10.0.0.8", 2),
      ),
      lastSeenTimestamp = 2L,
    )
    val dropped = supersededTrustedIds(
      keepDeviceId = "newid002",
      address = "10.0.0.8",
      devices = mapOf("oldid001" to old, "newid002" to fresh),
      isTrusted = { it == "oldid001" },
    )
    kotlin.test.assertEquals(listOf("oldid001"), dropped)
  }

  @Test
  fun revocationWhenPeerClaimsTrustAfterLocalUnpair() = runTest {
    val alice = newManager(aliceId)
    val bob = newManager(bobId)
    val response = bob.createPairingAcceptance(alice.createPairingRequest(bobId).getOrThrow()).getOrThrow()
    alice.finalizePairing(response)
    alice.removeTrust(bobId)

    val revocation = assertNotNull(revocationIfPeerStale(alice, bobId, peerClaimsTrust = true))
    kotlin.test.assertEquals(aliceId, revocation.senderId)
    kotlin.test.assertEquals(bobId, revocation.targetDeviceId)
    kotlin.test.assertEquals("device_unknown", revocation.reason)
  }
}
