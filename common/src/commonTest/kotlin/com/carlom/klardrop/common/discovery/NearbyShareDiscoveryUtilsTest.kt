package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.mdns.ServiceInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the filtering rules that keep unreachable or metadata-less peers out
 * of the NEARBY list (see DiscoveryNetwork / NearbyShareDiscoveryUtils).
 */
class NearbyShareDiscoveryUtilsTest {

  private val utils = NearbyShareDiscoveryUtils()

  @Test
  fun validEndpointInfoIsAccepted() {
    val info = nearbyService(
      addresses = listOf("192.168.1.20"),
      endpointInfo = buildEndpointInfo("Pixel 9 Pro XL")
    )
    assertTrue(utils.isValidService(info))
  }

  @Test
  fun emptyAttributesAreRejected() {
    val info = nearbyService(
      addresses = listOf("192.168.1.20"),
      attributes = emptyMap()
    )
    assertFalse(utils.isValidService(info))
  }

  @Test
  fun zeroLengthDeviceNameWithoutDiIsRejected() {
    // This is the exact case that surfaced as "unknown device name" in the UI.
    val info = nearbyService(
      addresses = listOf("192.168.1.20"),
      endpointInfo = buildEndpointInfo(name = ""),
      attributes = mapOf("n" to urlSafeBase64EncodedString(buildEndpointInfo(""))),
    )
    assertFalse(utils.isValidService(info))
  }

  @Test
  fun shortEndpointInfoWithDiIsAccepted() {
    val info = nearbyService(
      addresses = listOf("192.168.1.20"),
      endpointInfo = ByteArray(10),
      attributes = mapOf(
        "n" to urlSafeBase64EncodedString(ByteArray(10)),
        "di" to urlSafeBase64EncodedString("abcd"),
      ),
    )
    assertTrue(utils.isValidService(info))
  }

  @Test
  fun truncatedEndpointInfoWithoutDiIsRejected() {
    // Less than the 19 bytes required to hold even a zero-length name field.
    val info = nearbyService(
      addresses = listOf("192.168.1.20"),
      endpointInfo = ByteArray(10),
      attributes = mapOf("n" to urlSafeBase64EncodedString(ByteArray(10))),
    )
    assertFalse(utils.isValidService(info))
  }

  @Test
  fun endpointInfoWithNameLengthBeyondBufferIsRejected() {
    // bytes[17] claims a 50-byte name but the buffer is only 25 bytes long.
    val bytes = ByteArray(25).also { it[17] = 50 }
    val info = nearbyService(
      addresses = listOf("192.168.1.20"),
      endpointInfo = bytes,
      attributes = mapOf("n" to urlSafeBase64EncodedString(bytes)),
    )
    assertFalse(utils.isValidService(info))
  }

  @Test
  fun malformedBase64EndpointInfoWithoutDiIsRejected() {
    val info = nearbyService(
      addresses = listOf("192.168.1.20"),
      attributes = mapOf("n" to "!!!not-base64!!!")
    )
    assertFalse(utils.isValidService(info))
  }

  @Test
  fun diWithoutEndpointInfoIsAccepted() {
    val info = nearbyService(
      addresses = listOf("192.168.1.20"),
      attributes = mapOf("di" to urlSafeBase64EncodedString("abcd"))
    )
    assertTrue(utils.isValidService(info))
  }

  @Test
  fun zeroPortIsRejected() {
    val info = nearbyService(
      port = 0,
      addresses = listOf("192.168.1.20"),
      endpointInfo = buildEndpointInfo("Pixel 9 Pro XL")
    )
    assertFalse(utils.isValidService(info))
  }

  @Test
  fun loopbackOnlyAddressesAreRejected() {
    val info = nearbyService(
      addresses = listOf("127.0.0.1", "::1"),
      endpointInfo = buildEndpointInfo("Pixel 9 Pro XL")
    )
    assertFalse(utils.isValidService(info))
  }

  @Test
  fun unspecifiedOnlyAddressesAreRejected() {
    val info = nearbyService(
      addresses = listOf("0.0.0.0", "::"),
      endpointInfo = buildEndpointInfo("Pixel 9 Pro XL")
    )
    assertFalse(utils.isValidService(info))
  }

  @Test
  fun mixedAddressesPassIfAnyIsReachable() {
    val info = nearbyService(
      addresses = listOf("127.0.0.1", "192.168.1.20"),
      endpointInfo = buildEndpointInfo("Pixel 9 Pro XL")
    )
    assertTrue(utils.isValidService(info))
  }

  // ---------- String.isReachableAddress() ----------

  @Test
  fun reachableAddressHelperRecognizesLoopbackAndUnspecified() {
    assertFalse("".isReachableAddress())
    assertFalse(" ".isReachableAddress())
    assertFalse("0.0.0.0".isReachableAddress())
    assertFalse("::".isReachableAddress())
    assertFalse("::0".isReachableAddress())
    assertFalse("127.0.0.1".isReachableAddress())
    assertFalse("127.255.255.254".isReachableAddress())
    assertFalse("::1".isReachableAddress())
    assertFalse("::1%eth0".isReachableAddress())

    assertTrue("192.168.1.1".isReachableAddress())
    assertTrue("10.0.0.5".isReachableAddress())
    assertTrue("169.254.1.2".isReachableAddress()) // link-local IPv4 is still on-link reachable
  }

  @Test
  fun reachableAddressHelperRejectsAllIpv6() {
    // The Klardrop server only binds 0.0.0.0 (IPv4); no IPv6 endpoint is ever dialable, and
    // Android API 34+ resolvers surface link-local fe80:: addresses with no zone id that would
    // otherwise burn a full connect timeout per peer.
    assertFalse("fe80::1".isReachableAddress())
    assertFalse("fe80::1%wlan0".isReachableAddress())
    assertFalse("[fe80::1]".isReachableAddress())
    assertFalse("2001:db8::1".isReachableAddress()) // global IPv6 unicast, still not dialable

    // IPv4 still passes through untouched.
    assertTrue("192.168.1.1".isReachableAddress())
  }

  // ---------- helpers ----------

  private fun nearbyService(
    port: Int = 5555,
    addresses: List<String>,
    endpointInfo: ByteArray = buildEndpointInfo("Pixel 9 Pro XL"),
    attributes: Map<String, String> = mapOf(
      "n" to urlSafeBase64EncodedString(endpointInfo),
      "di" to urlSafeBase64EncodedString("abcd")
    )
  ): ServiceInfo = ServiceInfo(
    port = port,
    serviceName = "svc",
    serviceType = NearbyShareDiscoveryUtils.NEARBY_SERVICE_TYPE,
    attributes = attributes,
    addresses = addresses
  )

  /**
   * Produces a minimal Nearby Share endpoint info blob where bytes[17] carries the
   * UTF-8 name length and bytes[18..] carry the name itself.
   */
  private fun buildEndpointInfo(name: String): ByteArray {
    val nameBytes = name.encodeToByteArray()
    val buffer = ByteArray(18 + nameBytes.size)
    buffer[17] = nameBytes.size.toByte()
    nameBytes.copyInto(buffer, destinationOffset = 18)
    return buffer
  }
}
