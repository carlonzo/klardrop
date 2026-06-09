package com.carlom.klardrop.common.discovery

import app.cash.turbine.test
import TestCoroutines
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.DeviceType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull


class VisibleDevicesImplTest {

  private val coroutines = TestCoroutines()
  private val visibleDevices = VisibleDevicesImpl(coroutines, Clock())

  @Test
  fun addAndRemoveDevices() = runTest(coroutines.dispatcher) {

    visibleDevices.visibleDevices.test {
      awaitItem() // ignore first empty state

      visibleDevices.onNewDeviceVisible(device1, connection1)

      awaitItem().also {
        assertEquals(1, it.size)
        assertEquals(device1, it.values.first().deviceInfo)

        val deviceConnections = it.values.first().deviceConnections

        assertEquals(1, deviceConnections.size)

        assertContains(deviceConnections, connection1)
      }

      // adds second device
      visibleDevices.onNewDeviceVisible(
        device2,
        connection2
      )

      // assert second device is visible
      awaitItem().also {
        assertEquals(2, it.size)
        val deviceInfos = it.values.map { it.deviceInfo }

        assertContains(deviceInfos, device1)
        assertContains(deviceInfos, device2)

        assertEquals(listOf(connection1), it.getValue(device1.deviceId).deviceConnections)
        assertEquals(listOf(connection2), it.getValue(device2.deviceId).deviceConnections)
      }

      visibleDevices.onDeviceLost(device2.deviceId, connection2)

      awaitItem().also {
        assertEquals(1, it.size)
        assertEquals(device1, it.values.first().deviceInfo)
      }

    }
  }

  @Test
  fun addMultipleConnections() = runTest(coroutines.dispatcher) {

    visibleDevices.visibleDevices.test {
      awaitItem() // ignore first empty state

      // adds 2 connection to same device

      visibleDevices.onNewDeviceVisible(device1, connection1)
      awaitItem()

      visibleDevices.onNewDeviceVisible(device1, connection2)

      // asserts 2 connections have been added to 1 device
      awaitItem().also {

        val deviceInfos = it.values.map { it.deviceInfo }
        assertEquals(1, deviceInfos.size)

        assertContains(deviceInfos, device1)

        assertEquals(listOf(connection1, connection2), it.getValue(device1.deviceId).deviceConnections)

      }

      // remove connection1
      visibleDevices.onDeviceLost(device1.deviceId, connection1)

      // assert only connection2 is attached
      awaitItem().also {
        assertEquals(1, it.keys.size)

        it.values.first().deviceConnections.also {
          assertEquals(1, it.size)
          assertContains(it, connection2)
        }
      }

      // remove connection that was already removed
      visibleDevices.onDeviceLost(device1.deviceId, connection1)
      // expect no emission as the flow didnt change
      expectNoEvents()


      visibleDevices.onDeviceLost(device1.deviceId, connection2)
      // assert no device is available
      assertEquals(0, awaitItem().keys.size)
    }

  }


  /**
   * Regression test for: a transient mDNS "service lost" event (with no addresses,
   * as Android NsdManager delivers) must NOT remove a device whose lastSeenTimestamp
   * is fresh (i.e. recently touched by a TCP heartbeat). The device should survive
   * the bare onDeviceLost(deviceId) call and only be evicted by the TTL sweep once
   * it genuinely goes quiet.
   *
   * Before the fix this test FAILS because onDeviceLost(deviceId) removes the device
   * immediately with no liveness check.
   */
  @Test
  fun mdnsServiceLostDoesNotRemoveDeviceWithFreshLastSeen() = runTest(coroutines.dispatcher) {
    // 1. Add a device so it appears in the visible map.
    visibleDevices.onNewDeviceVisible(device1, connection1)
    assertNotNull(visibleDevices.getDevice(device1.deviceId), "device should be visible after onNewDeviceVisible")

    // 2. Simulate a TCP heartbeat: refresh lastSeenTimestamp to "now".
    visibleDevices.touchLastSeen(device1.deviceId)

    // 3. Fire the no-address onDeviceLost path — this is exactly what happens when
    //    Android NsdManager.onServiceLost delivers an unresolved ServiceInfo (port=0,
    //    addresses=[]) and DiscoveryNetwork falls through to onDeviceLost(deviceId).
    visibleDevices.onDeviceLost(device1.deviceId)

    // 4. The device MUST still be present because its lastSeen is within the grace window.
    assertNotNull(
      visibleDevices.getDevice(device1.deviceId),
      "device must NOT be removed by a bare onDeviceLost when lastSeen is fresh"
    )
  }

  /**
   * Negative counterpart to [mdnsServiceLostDoesNotRemoveDeviceWithFreshLastSeen]: once a
   * device's lastSeenTimestamp is OLDER than the 30s grace window (no heartbeat has refreshed
   * it), a bare onDeviceLost MUST evict it — the grace window must not shield a genuinely
   * departed peer indefinitely. Uses an injected time source so we can age the clock past the
   * window without a real-time wait.
   */
  @Test
  fun mdnsServiceLostRemovesDeviceWhenLastSeenIsStale() = runTest(coroutines.dispatcher) {
    var now = 1_000_000L
    val devices = VisibleDevicesImpl(coroutines, Clock(), nowMs = { now })

    devices.onNewDeviceVisible(device1, connection1)
    assertNotNull(devices.getDevice(device1.deviceId), "device should be visible after onNewDeviceVisible")

    // Advance past the 30s grace window with no liveness refresh — the peer has gone quiet.
    now += 31_000L

    // The bare onDeviceLost path (Android unresolved ServiceInfo) must now remove it.
    devices.onDeviceLost(device1.deviceId)

    assertNull(
      devices.getDevice(device1.deviceId),
      "device whose lastSeen is older than the grace window must be removed by a bare onDeviceLost",
    )
  }

  /**
   * Sanity-check: the two-arg onDeviceLost (specific-address removal) continues to
   * work normally — it must still remove the matching transport entry and, when the
   * last connection is gone, remove the device entirely.
   */
  @Test
  fun specificAddressLostStillRemovesTransport() = runTest(coroutines.dispatcher) {
    visibleDevices.onNewDeviceVisible(device1, connection1)
    visibleDevices.touchLastSeen(device1.deviceId)

    // Remove with the specific connection — this must still work.
    visibleDevices.onDeviceLost(device1.deviceId, connection1)

    // Device has no remaining connections → should be gone.
    assertNull(
      visibleDevices.getDevice(device1.deviceId),
      "device with no remaining connections should be removed"
    )
  }

  /**
   * Regression guard: addDevice already replaces a same-(type, address) endpoint when a new port
   * arrives via ServiceFound (peer restarted on a fresh ephemeral port and mDNS delivered a fresh
   * SRV). After the update only the new port must remain for that address.
   */
  @Test
  fun addDevice_replacesStalePortWhenSameTypeAndAddressArrivesWithNewPort() = runTest(coroutines.dispatcher) {
    val address = "192.168.1.10"
    val portA = 40000
    val portB = 40001

    val connectionA = DeviceConnection.KlardropConnection(address, portA)
    val connectionB = DeviceConnection.KlardropConnection(address, portB)

    // Seed with old port
    visibleDevices.onNewDeviceVisible(device1, connectionA)

    // Fresh ServiceFound arrives with new port (same address, same type)
    visibleDevices.onNewDeviceVisible(device1, connectionB)

    val device = visibleDevices.getDevice(device1.deviceId)!!
    val klardropConnections = device.deviceConnections
      .filterIsInstance<DeviceConnection.KlardropConnection>()
      .filter { it.address == address }

    // Only the fresh port must survive; the stale port must have been evicted.
    assertEquals(1, klardropConnections.size)
    assertEquals(portB, klardropConnections.first().port)
  }

  /**
   * Core bug scenario: a dial to a cached endpoint is refused (peer restarted on a new
   * ephemeral port). [VisibleDevices.invalidateKlardropEndpoint] must remove only the
   * dead endpoint. If it was the device's only connection the device entry is also removed.
   */
  @Test
  fun invalidateKlardropEndpoint_removesStaleEndpointAfterRefusedDial() = runTest(coroutines.dispatcher) {
    val address = "192.168.1.20"
    val stalePort = 50000
    val staleConnection = DeviceConnection.KlardropConnection(address, stalePort)

    // Device is visible with a single Klardrop endpoint.
    visibleDevices.onNewDeviceVisible(device1, staleConnection)
    assertEquals(true, visibleDevices.isDeviceVisible(device1.deviceId))

    // Simulate: dial to stalePort is refused — invalidate the endpoint.
    visibleDevices.invalidateKlardropEndpoint(device1.deviceId, address, stalePort)

    // The stale endpoint (and the device, since it had no other connections) must be gone.
    val deviceAfter = visibleDevices.getDevice(device1.deviceId)
    val stillHasStalePort = deviceAfter?.deviceConnections
      ?.filterIsInstance<DeviceConnection.KlardropConnection>()
      ?.any { it.address == address && it.port == stalePort } == true
    assertEquals(false, stillHasStalePort,
      "Stale endpoint must be removed after a refused dial")
  }

  /**
   * When a device has multiple Klardrop connections (e.g. dual-homed), only the refused
   * endpoint should be evicted; the device entry must survive with the remaining connection.
   */
  @Test
  fun invalidateKlardropEndpoint_leavesOtherConnectionsIntact() = runTest(coroutines.dispatcher) {
    val staleAddress = "192.168.1.30"
    val stalePort = 60000
    val healthyAddress = "10.0.0.5"
    val healthyPort = 60001

    val staleConnection = DeviceConnection.KlardropConnection(staleAddress, stalePort)
    val healthyConnection = DeviceConnection.KlardropConnection(healthyAddress, healthyPort)

    visibleDevices.onNewDeviceVisible(device1, staleConnection)
    visibleDevices.onNewDeviceVisible(device1, healthyConnection)

    // Refused dial on stale endpoint only
    visibleDevices.invalidateKlardropEndpoint(device1.deviceId, staleAddress, stalePort)

    val deviceAfter = visibleDevices.getDevice(device1.deviceId)
    // Device still visible (healthy connection remains)
    assertEquals(true, deviceAfter != null, "Device must remain visible when it has other connections")
    val remaining = deviceAfter!!.deviceConnections
    assertEquals(false, remaining.any { it.address == staleAddress && it.port == stalePort },
      "Stale endpoint must be gone")
    assertEquals(true, remaining.any { it.address == healthyAddress && it.port == healthyPort },
      "Healthy endpoint must survive")
  }

  val device1 = DeviceInfo("1", "device1", DeviceType.MOBILE)
  val device2 = DeviceInfo("2", "device2", DeviceType.DESKTOP)

  val connection1 = DeviceConnection.NearbyConnection("1.1.1.1", 8080)
  val connection2 = DeviceConnection.KlardropConnection("1.1.1.2", 8081)

}