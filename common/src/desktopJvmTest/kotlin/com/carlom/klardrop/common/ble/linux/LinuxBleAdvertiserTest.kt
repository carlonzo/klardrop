package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.ble.BleConstants
import com.carlom.klardrop.common.ble.klardropAdvertisePayload
import com.carlom.klardrop.common.discovery.CurrentDevice
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Advertising logic exercised through a fake [BlueZFacade] — no D-Bus daemon involved.
 * The fake materializes the [ExportedAdvertisement] the real facade would export, so the
 * tests pin the wire payload (ServiceUUIDs / ServiceData) against `BleAdvertisePayload`'s
 * encoding without a live BlueZ.
 */
class LinuxBleAdvertiserTest {

  private val device = CurrentDevice("abcd1234xx", "Test Box", DeviceType.DESKTOP, OsType.LINUX)

  /** Fake facade: records advertising calls and simulates the real facade's export. */
  private class FakeBlueZFacade : BlueZFacade {
    var startCount = 0
      private set
    var stopCount = 0
      private set
    var lastDevice: CurrentDevice? = null
      private set
    var registered: ExportedAdvertisement? = null
      private set

    override suspend fun probeCapability() = BlueZCapability(true, listOf("/org/bluez/hci0"))

    override suspend fun startAdvertising(currentDevice: CurrentDevice) {
      startCount++
      lastDevice = currentDevice
      registered = ExportedAdvertisement(currentDevice.shortDeviceId)
    }

    override suspend fun stopAdvertising() {
      stopCount++
      registered = null
    }
  }

  /** Suspends until [condition] holds (fake facade calls complete on the test dispatcher). */
  private suspend fun await(condition: () -> Boolean) {
    withTimeout(5_000) {
      while (!condition()) yield()
    }
  }

  @Test
  fun startRegistersAdvertisementWithPayloadEncoding() = runTest {
    val facade = FakeBlueZFacade()
    val advertiser = LinuxBleAdvertiser(facade)

    advertiser.startAdvertising(device)
    await { facade.startCount == 1 }

    assertEquals(device, facade.lastDevice)
    val adv = facade.registered!!
    val payload = klardropAdvertisePayload(device.shortDeviceId)
    // Primary adv: service UUID in the standard ServiceUUIDs field (scan-filter matchable).
    assertEquals(payload.primary.serviceUuids, adv.getServiceUUIDs())
    // Scan response: shortDeviceId bytes keyed under SERVICE_UUID, exactly as peers decode.
    assertEquals(
      payload.scanResponse!!.serviceData.mapValues { it.value.toList() },
      adv.getServiceData().mapValues { (it.value.value as ByteArray).toList() },
    )
  }

  @Test
  fun doubleStartIsNoOp() = runTest {
    val facade = FakeBlueZFacade()
    val advertiser = LinuxBleAdvertiser(facade)

    advertiser.startAdvertising(device)
    await { facade.startCount == 1 }
    advertiser.startAdvertising(device)
    advertiser.startAdvertising(device)

    assertEquals(1, facade.startCount)
  }

  @Test
  fun stopUnregisters() = runTest {
    val facade = FakeBlueZFacade()
    val advertiser = LinuxBleAdvertiser(facade)

    advertiser.startAdvertising(device)
    await { facade.startCount == 1 }
    advertiser.stopAdvertising()

    assertEquals(1, facade.stopCount)
    assertNull(facade.registered)
  }

  @Test
  fun stopWithoutStartIsNoOp() = runTest {
    val facade = FakeBlueZFacade()
    val advertiser = LinuxBleAdvertiser(facade)

    advertiser.stopAdvertising()

    assertEquals(0, facade.stopCount)
  }

  @Test
  fun restartCycleReRegisters() = runTest {
    val facade = FakeBlueZFacade()
    val advertiser = LinuxBleAdvertiser(facade)

    advertiser.startAdvertising(device)
    await { facade.startCount == 1 }
    advertiser.stopAdvertising()
    advertiser.startAdvertising(device)
    await { facade.startCount == 2 }

    assertEquals(1, facade.stopCount)
    assertTrue(facade.registered != null)
  }

  @Test
  fun exportedAdvertisementMatchesBlueZContract() {
    val shortId = device.shortDeviceId
    val adv = ExportedAdvertisement(shortId)

    assertEquals("peripheral", adv.getType())
    assertEquals(listOf(BleConstants.SERVICE_UUID), adv.getServiceUUIDs())
    assertEquals(shortId, adv.getLocalName())
    assertEquals(listOf("tx-power"), adv.getIncludes())
    // BT2 lesson: byte-array service data must carry the explicit "ay" signature.
    val data = adv.getServiceData().getValue(BleConstants.SERVICE_UUID)
    assertEquals("ay", data.sig)
    assertEquals(shortId.encodeToByteArray().toList(), (data.value as ByteArray).toList())
    assertEquals("/com/carlom/klardrop/ble/advertisement0", adv.getObjectPath())
  }
}
