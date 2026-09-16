package com.carlom.klardrop

import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.carlom.klardrop.common.utils.DeviceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisibleDevicesChangeSummaryTest {

  private val phone = DeviceInfo(deviceId = "phone", name = "Pixel", deviceType = DeviceType.MOBILE)
  private val laptop = DeviceInfo(deviceId = "laptop", name = "Laptop", deviceType = DeviceType.DESKTOP)

  private fun device(info: DeviceInfo, timestamp: Long = 0L) = DiscoveryDevice(
    deviceInfo = info,
    deviceConnections = listOf(DeviceConnection.KlardropConnection("10.0.0.2", 1234)),
    lastSeenTimestamp = timestamp,
  )

  @Test
  fun timestampOnlyBumpStaysSilent() {
    val before = mapOf("phone" to device(phone, timestamp = 1L))
    val after = mapOf("phone" to device(phone, timestamp = 2L))
    assertNull(visibleDevicesChangeSummary(before, after))
  }

  @Test
  fun identicalSnapshotsStaySilent() {
    val snapshot = mapOf("phone" to device(phone))
    assertNull(visibleDevicesChangeSummary(snapshot, snapshot))
  }

  @Test
  fun reportsAddedAndRemovedIds() {
    val summary = visibleDevicesChangeSummary(
      mapOf("phone" to device(phone)),
      mapOf("laptop" to device(laptop)),
    )
    assertTrue(summary!!.contains("+[laptop]"), "was: $summary")
    assertTrue(summary.contains("-[phone]"), "was: $summary")
  }

  @Test
  fun reportsConnectionChangesIgnoringOrder() {
    val reordered = device(phone).copy(deviceConnections = listOf(DeviceConnection.KlardropConnection("10.0.0.2", 1234)))
    assertNull(
      visibleDevicesChangeSummary(
        mapOf("phone" to device(phone)),
        mapOf("phone" to reordered),
      )
    )

    val summary = visibleDevicesChangeSummary(
      mapOf("phone" to device(phone)),
      mapOf("phone" to device(phone).copy(deviceConnections = listOf(DeviceConnection.NearbyConnection("10.0.0.2", 5678)))),
    )
    assertTrue(summary!!.contains("~[phone]"), "was: $summary")
  }

  @Test
  fun reportsIdentityChanges() {
    val summary = visibleDevicesChangeSummary(
      mapOf("phone" to device(phone)),
      mapOf("phone" to device(phone.copy(name = "Pixel 9"))),
    )
    assertEquals("VisibleDevices changed (1 total); ~[phone]", summary)
  }
}
