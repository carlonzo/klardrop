package com.carlom.klardrop.common.discovery

import TestCoroutines
import app.cash.turbine.test
import com.carlom.klardrop.common.utils.DeviceType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals


class VisibleDevicesImplTest {

  private val coroutines = TestCoroutines()
  private val visibleDevices = VisibleDevicesImpl(coroutines)

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


  val device1 = DeviceInfo("1", "device1", DeviceType.MOBILE)
  val device2 = DeviceInfo("2", "device2", DeviceType.DESKTOP)

  val connection1 = DeviceConnection.NearbyConnection("1.1.1.1", 8080)
  val connection2 = DeviceConnection.KlardropConnection("1.1.1.2", 8081)

}