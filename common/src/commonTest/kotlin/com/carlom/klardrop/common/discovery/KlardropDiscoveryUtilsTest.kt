package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.mdns.ServiceInfo
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-trip tests for the mDNS attribute encoding that carries device identity over the
 * wire. A regression here silently breaks cross-platform discovery without any crash,
 * so assertions cover every attribute we publish and every field we parse back.
 */
class KlardropDiscoveryUtilsTest {

  private val utils = KlardropDiscoveryUtils()

  @Test
  fun registerServiceInfoEncodesDeviceIdAndDeviceNameBase64() = runTestUtils { device ->
    val info = utils.getRegisterServiceInfo(port = 5555, currentDevice = device)

    assertEquals(5555, info.port)
    assertEquals(KlardropDiscoveryUtils.KLARDROP_SERVICE_TYPE, info.serviceType)
    assertTrue(info.attributes.containsKey("dn"), "Device name attribute 'dn' must be present")
    assertTrue(info.attributes.containsKey("d"), "Device info attribute 'd' must be present")
    // serviceName should be url-safe-base64 encoding of the shortDeviceId
    assertFalse(info.serviceName.contains('+'), "URL-safe base64 must not contain '+'")
    assertFalse(info.serviceName.contains('/'), "URL-safe base64 must not contain '/'")
  }

  @Test
  fun serviceInfoRoundTripsBackToDeviceInfo() = runTestUtils { device ->
    val registered = utils.getRegisterServiceInfo(port = 5555, currentDevice = device)
    val serviceInfo = registered.toServiceInfo(addresses = listOf("10.0.0.5"))

    val decoded = utils.toDeviceInfo(serviceInfo)

    assertEquals(device.shortDeviceId, decoded.deviceId)
    assertEquals(device.deviceName, decoded.name)
    assertEquals(device.deviceType, decoded.deviceType)
    assertEquals(device.osType, decoded.osType)
  }

  @Test
  fun roundTripPreservesUnicodeDeviceNames() = runTestUtils(deviceName = "carlo's 📱") { device ->
    val registered = utils.getRegisterServiceInfo(port = 42, currentDevice = device)
    val serviceInfo = registered.toServiceInfo(addresses = listOf("192.168.1.1"))

    val decoded = utils.toDeviceInfo(serviceInfo)
    assertEquals("carlo's 📱", decoded.name)
  }

  @Test
  fun serviceWithoutAddressesIsInvalid() {
    val info = ServiceInfo(
      port = 1,
      serviceName = "x",
      serviceType = KlardropDiscoveryUtils.KLARDROP_SERVICE_TYPE,
      attributes = mapOf("dn" to "x", "d" to "1"),
      addresses = emptyList()
    )
    assertFalse(utils.isValidService(info))
  }

  @Test
  fun serviceWithoutAttributesIsInvalid() {
    val info = ServiceInfo(
      port = 1,
      serviceName = "x",
      serviceType = KlardropDiscoveryUtils.KLARDROP_SERVICE_TYPE,
      attributes = emptyMap(),
      addresses = listOf("1.2.3.4")
    )
    assertFalse(utils.isValidService(info))
  }

  @Test
  fun jmdnsNameCollisionSuffixIsStrippedWhenDecodingDeviceId() = runTestUtils { device ->
    // When two services collide, jmDNS appends "(N)". serviceNameClean() strips it before
    // base64-decoding the shortDeviceId. This test guards that contract.
    val registered = utils.getRegisterServiceInfo(port = 1, currentDevice = device)
    val nameWithSuffix = "${registered.serviceName} (2)"
    val serviceInfo = registered.toServiceInfo(
      addresses = listOf("1.2.3.4"),
      serviceName = nameWithSuffix
    )

    val decoded = utils.toDeviceInfo(serviceInfo)
    assertEquals(device.shortDeviceId, decoded.deviceId)
  }

  @Test
  fun packedDeviceInfoByteOverflowIsRejected() {
    // The packing fits device type in the high nibble and os type in the low nibble.
    // DeviceType.UNKNOWN has nearbyId = 15 (0xF), which packed gives 0xF0, still < 0xFF.
    // This test documents the invariant at the upper edge — if we ever extend nearbyIds
    // beyond 4 bits the production require() needs updating.
    val synthetic = buildDevice(
      deviceId = "wayoffthe_charts",
      deviceName = "x",
      deviceType = DeviceType.UNKNOWN,
      osType = OsType.UNKNOWN
    )
    // UNKNOWN << 4 | UNKNOWN = 0xFF exactly, which violates `< 0xFF` check.
    assertFails { utils.getRegisterServiceInfo(port = 1, currentDevice = synthetic) }
  }

  // ---------- helpers ----------

  private fun runTestUtils(
    deviceId: String = "my-device-id-0001",
    deviceName: String = "Carlo's Desktop",
    block: (CurrentDevice) -> Unit
  ) {
    val device = buildDevice(
      deviceId = deviceId,
      deviceName = deviceName,
      deviceType = DeviceType.DESKTOP,
      osType = OsType.LINUX
    )
    block(device)
  }

  private fun buildDevice(
    deviceId: String,
    deviceName: String,
    deviceType: DeviceType,
    osType: OsType
  ): CurrentDevice = CurrentDevice(
    deviceId = deviceId,
    deviceName = deviceName,
    deviceType = deviceType,
    osType = osType
  )

  private fun com.carlom.klardrop.common.mdns.RegisterServiceInfo.toServiceInfo(
    addresses: List<String>,
    serviceName: String = this.serviceName
  ): ServiceInfo = ServiceInfo(
    port = port,
    serviceName = serviceName,
    serviceType = serviceType,
    attributes = attributes,
    addresses = addresses
  )
}
