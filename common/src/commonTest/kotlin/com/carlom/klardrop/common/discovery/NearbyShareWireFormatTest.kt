package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.mdns.ServiceInfo
import com.carlom.klardrop.common.mdns.createEndpointInfo
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks down the on-wire byte format of the Nearby Share / Quick Share mDNS
 * advertisement. Android refuses to discover Klardrop when these encodings
 * diverge from the format described in
 * https://github.com/grishka/NearDrop/blob/master/PROTOCOL.md.
 */
class NearbyShareWireFormatTest {

  private val utils = NearbyShareDiscoveryUtils()

  // ---------- URL-safe base64 ----------

  @Test
  fun urlSafeBase64HasNoPaddingAndUsesUrlSafeAlphabet() {
    // 10 bytes -> 16 base64 chars with 2 '=' padding chars under the standard
    // RFC 4648 alphabet. The advertised form must drop the '=' (NearDrop does
    // the same; Android's Quick Share parser rejects padded input).
    val bytes = byteArrayOf(0x23, 0x41, 0x42, 0x43, 0x44, 0xFC.toByte(), 0x9F.toByte(), 0x5E, 0, 0)
    val encoded = urlSafeBase64EncodedString(bytes)

    assertFalse(encoded.contains('='), "url-safe base64 must not contain '=' padding, got: $encoded")
    assertFalse(encoded.contains('+'), "url-safe base64 must use '-' instead of '+', got: $encoded")
    assertFalse(encoded.contains('/'), "url-safe base64 must use '_' instead of '/', got: $encoded")

    val roundTripped = urlSafeBase64DecodeString(encoded)
    assertEquals(bytes.toList(), roundTripped.toList())
  }

  @Test
  fun urlSafeBase64DecoderTolerantOfPaddedInput() {
    // The decoder must still accept padded input so peers that haven't been
    // updated yet (e.g. older Klardrop builds) keep working.
    val padded = "I0FCQ0T8n14AAA=="
    val decoded = urlSafeBase64DecodeString(padded)
    assertEquals(10, decoded.size)
    assertEquals(0x23.toByte(), decoded[0])
  }

  // ---------- mDNS service name ----------

  @Test
  fun serviceNameEncodingMatchesProtocol() {
    val device = CurrentDevice(
      deviceId = "ABCDEFGH",
      deviceName = "Klardrop Test",
      deviceType = DeviceType.DESKTOP,
      osType = OsType.APPLE,
    )

    val registration = utils.getRegisterServiceInfo(port = 12345, currentDevice = device)

    // Expected name bytes per PROTOCOL.md:
    //   0x23 (PCP), 4-byte endpoint id ("ABCD"), 0xFC 0x9F 0x5E, 0x00 0x00
    val expectedBytes = byteArrayOf(
      0x23, 0x41, 0x42, 0x43, 0x44,
      0xFC.toByte(), 0x9F.toByte(), 0x5E,
      0, 0,
    )
    val decoded = urlSafeBase64DecodeString(registration.serviceName)
    assertEquals(expectedBytes.toList(), decoded.toList())
    assertFalse(registration.serviceName.contains('='), "service name must not be padded")
  }

  // ---------- Endpoint info (TXT record 'n') ----------

  @Test
  fun endpointInfoEncodesDesktopWithAsciiName() {
    val device = CurrentDevice(
      deviceId = "ABCDEFGH",
      deviceName = "Carlo's MacBook",
      deviceType = DeviceType.DESKTOP,
      osType = OsType.APPLE,
    )

    val info = createEndpointInfo(device)

    // byte 0: Version(3)|Visibility(1)|DeviceType(3)|Reserved(1)
    //   visibility = 0 (visible, plaintext name follows)
    //   device type DESKTOP -> Klardrop maps to nearby id 3 -> bits shifted left by 1 = 0b0000_0110
    assertEquals(0b0000_0110, info[0].toInt() and 0xFF)

    // bytes 1..16 are random (16 bytes); just assert the buffer length is right
    assertEquals(18 + "Carlo's MacBook".encodeToByteArray().size, info.size)

    // byte 17 holds the UTF-8 byte length of the name, followed by the name
    val nameBytes = "Carlo's MacBook".encodeToByteArray()
    assertEquals(nameBytes.size, info[17].toInt() and 0xFF)
    assertEquals(nameBytes.toList(), info.sliceArray(18 until 18 + nameBytes.size).toList())
  }

  @Test
  fun endpointInfoLengthIsUtf8ByteCountNotUtf16CodeUnits() {
    // Regression: "Köln 📱" has 6 UTF-16 code units but 10 UTF-8 bytes (the
    // emoji is a 4-byte surrogate pair). The wire field must be the UTF-8
    // byte length so the receiver's name-extraction doesn't run past the
    // buffer or truncate mid-codepoint.
    val name = "Köln 📱"
    assertTrue(name.length < name.encodeToByteArray().size, "test fixture invariant")

    val device = CurrentDevice(
      deviceId = "12345678",
      deviceName = name,
      deviceType = DeviceType.MOBILE,
      osType = OsType.ANDROID,
    )

    val info = createEndpointInfo(device)
    val expectedBytes = name.encodeToByteArray()

    assertEquals(expectedBytes.size, info[17].toInt() and 0xFF)
    assertEquals(expectedBytes.toList(), info.sliceArray(18 until 18 + expectedBytes.size).toList())
  }

  @Test
  fun endpointInfoTruncatesNameLongerThan255Bytes() {
    val longName = "a".repeat(300)
    val device = CurrentDevice(
      deviceId = "12345678",
      deviceName = longName,
      deviceType = DeviceType.DESKTOP,
      osType = OsType.LINUX,
    )

    val info = createEndpointInfo(device)
    assertEquals(255, info[17].toInt() and 0xFF)
    assertEquals(18 + 255, info.size)
  }

  // ---------- Endpoint info decode (toDeviceInfo) ----------

  @Test
  fun decoderRecoversVisiblePeerName() {
    val service = serviceWithEncodedEndpointInfo(
      deviceTypeBits = 0b0000_0110, // visible, desktop
      name = "Reference Peer",
    )
    val deviceInfo = utils.toDeviceInfo(service)
    assertEquals("Reference Peer", deviceInfo.name)
    assertEquals(DeviceType.DESKTOP, deviceInfo.deviceType)
  }

  @Test
  fun decoderTolerateVisibilityHiddenPeerWithoutCrashing() {
    // Visibility bit set => no plaintext name field. We must not read past
    // byte 17; we surface a placeholder instead.
    val bytes = ByteArray(17).also { it[0] = 0b0001_0010.toByte() /* hidden, mobile */ }
    val service = ServiceInfo(
      port = 12345,
      serviceName = "svc",
      serviceType = NearbyShareDiscoveryUtils.NEARBY_SERVICE_TYPE,
      attributes = mapOf(
        "n" to urlSafeBase64EncodedString(bytes),
        "di" to urlSafeBase64EncodedString("test"),
      ),
      addresses = listOf("192.168.1.50"),
    )
    val deviceInfo = utils.toDeviceInfo(service)
    assertEquals(DeviceType.MOBILE, deviceInfo.deviceType)
    assertContains(deviceInfo.name, "unknown", message = "expected placeholder name, got '${deviceInfo.name}'")
  }

  // ---------- Service registration: TXT records ----------

  @Test
  fun registerServiceInfoTxtRecordIsUnpadded() {
    val device = CurrentDevice(
      deviceId = "ABCDEFGH",
      deviceName = "Klardrop",
      deviceType = DeviceType.DESKTOP,
      osType = OsType.APPLE,
    )
    val registration = utils.getRegisterServiceInfo(port = 5555, currentDevice = device)

    val nValue = registration.attributes["n"]!!
    assertFalse(nValue.contains('='), "TXT record 'n' must not be padded, got: $nValue")
  }

  // ---------- helpers ----------

  private fun serviceWithEncodedEndpointInfo(
    deviceTypeBits: Int,
    name: String,
  ): ServiceInfo {
    val nameBytes = name.encodeToByteArray()
    val bytes = ByteArray(18 + nameBytes.size).also {
      it[0] = deviceTypeBits.toByte()
      it[17] = nameBytes.size.toByte()
      nameBytes.copyInto(it, destinationOffset = 18)
    }
    return ServiceInfo(
      port = 5555,
      serviceName = "svc",
      serviceType = NearbyShareDiscoveryUtils.NEARBY_SERVICE_TYPE,
      attributes = mapOf(
        "n" to urlSafeBase64EncodedString(bytes),
        "di" to urlSafeBase64EncodedString("abcd"),
      ),
      addresses = listOf("192.168.1.50"),
    )
  }
}
