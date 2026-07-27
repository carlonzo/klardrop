package com.carlom.klardrop.common.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The encode/decode pair here is a wire contract between *different devices*, so these
 * tests stand in for the cross-platform integration test we can't run: whatever any
 * platform advertises via [klardropAdvertisePayload] must come back out of [decode] on
 * every other platform.
 */
class BleAdvertisementCodecTest {

  @Test
  fun decodesShortDeviceIdFromServiceData() {
    // How an Android peer advertises: bare short id in the service-data AD field,
    // no local name (AdvertiseData can't carry a custom one).
    val decoded = BleAdvertisementCodec.decode(
      serviceData = "abcd1234".encodeToByteArray(),
      localName = null,
    )
    assertEquals("abcd1234", assertNotNull(decoded).shortDeviceId)
    assertNull(decoded.friendlyName)
  }

  @Test
  fun decodesShortDeviceIdFromLocalName() {
    // How an Apple peer advertises: short id as the local name, because
    // CBPeripheralManager silently drops custom service-data.
    val decoded = BleAdvertisementCodec.decode(serviceData = null, localName = "abcd1234")
    assertEquals("abcd1234", assertNotNull(decoded).shortDeviceId)
    // The name is just the id echoed back, so there is no friendly name to report.
    // It must stay null: a BLE-only peer whose DeviceInfo.name differs from its id
    // stops looking like a placeholder to BleEagerConnector, which then never opens
    // the GATT session that would fetch its real name, OS and device type.
    assertNull(decoded.friendlyName)
  }

  @Test
  fun serviceDataWinsOverLocalName() {
    // Only a Klardrop peer can populate service-data under our service UUID, so it is
    // the more trustworthy of the two channels when both are present.
    val decoded = BleAdvertisementCodec.decode(
      serviceData = "abcd1234".encodeToByteArray(),
      localName = "beefcafe",
    )
    assertEquals("abcd1234", assertNotNull(decoded).shortDeviceId)
    assertEquals("beefcafe", decoded.friendlyName)
  }

  @Test
  fun rejectsBluetoothNamesThatArentKlardropIds() {
    // Regression: without a shape check, a nearby device's Bluetooth name was adopted
    // as a peer identity, producing a ghost row that never merged with that device's
    // real mDNS entry and never resolved to anything.
    val names = listOf(
      "Galaxy A32",      // spaces + uppercase
      "Pixel9Pro",       // uppercase, wrong length
      "MacBook",         // 7 chars
      "abcd12345",       // 9 chars
      "abcd-123",        // dash isn't in the id alphabet
      "ABCD1234",        // uppercase — cleanDeviceId lowercases
      "",
    )
    for (name in names) {
      assertNull(
        BleAdvertisementCodec.decode(serviceData = null, localName = name),
        "'$name' must not be accepted as a Klardrop short device id",
      )
      assertFalse(BleAdvertisementCodec.isKlardropShortDeviceId(name), "isKlardropShortDeviceId('$name')")
    }
  }

  @Test
  fun ignoresMalformedServiceDataAndFallsBackToLocalName() {
    // A peer advertising some other payload under our UUID must not shadow a perfectly
    // good local name.
    val decoded = BleAdvertisementCodec.decode(
      serviceData = byteArrayOf(0x00, 0x01, 0x02),
      localName = "abcd1234",
    )
    assertEquals("abcd1234", assertNotNull(decoded).shortDeviceId)
  }

  @Test
  fun returnsNullWhenAdvertisementCarriesNoIdentity() {
    assertNull(BleAdvertisementCodec.decode(serviceData = null, localName = null))
    assertNull(BleAdvertisementCodec.decode(serviceData = byteArrayOf(), localName = null))
  }

  @Test
  fun acceptsFullLowercaseAlphanumericAlphabet() {
    // cleanDeviceId() lowercases and strips everything that isn't a letter or digit,
    // so both letters beyond hex and digits must be accepted.
    assertTrue(BleAdvertisementCodec.isKlardropShortDeviceId("zx9y8w7v"))
    assertTrue(BleAdvertisementCodec.isKlardropShortDeviceId("00000000"))
  }

  @Test
  fun encodeRejectsMalformedIds() {
    assertFailsWith<IllegalArgumentException> { BleAdvertisementCodec.encodeShortDeviceId("Galaxy A32") }
    assertFailsWith<IllegalArgumentException> { BleAdvertisementCodec.encodeShortDeviceId("abc") }
  }

  @Test
  fun androidStyleAdvertisementRoundTrips() {
    // Advertise as Android does (service-data record only, no local name) and read it
    // back as any peer would.
    val payload = klardropAdvertisePayload("abcd1234")
    val serviceData = assertNotNull(payload.scanResponse?.serviceData?.get(BleConstants.SERVICE_UUID))
    val decoded = BleAdvertisementCodec.decode(serviceData = serviceData, localName = null)
    assertEquals("abcd1234", assertNotNull(decoded).shortDeviceId)
  }

  @Test
  fun appleStyleAdvertisementRoundTrips() {
    // Advertise as Apple does (service UUID + local name from the primary record only,
    // service-data dropped by CoreBluetooth) and read it back.
    val payload = klardropAdvertisePayload("abcd1234")
    val decoded = BleAdvertisementCodec.decode(
      serviceData = null,
      localName = payload.primary.localName,
    )
    assertEquals("abcd1234", assertNotNull(decoded).shortDeviceId)
  }
}
