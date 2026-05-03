package com.carlom.klardrop.common.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BleAdvertisePayloadTest {

  @Test
  fun primaryAdvertisementCarriesTheServiceUuid() {
    // Regression: when only service-data carried the UUID, peers using a
    // standard `setServiceUuid(...)` scan filter never matched. The service
    // UUID must appear in the standard Service Class UUIDs AD field (0x07)
    // in the PRIMARY advertisement so any compliant scanner finds us.
    val payload = klardropAdvertisePayload("abcd1234")
    assertEquals(
      listOf(BleConstants.SERVICE_UUID),
      payload.primary.serviceUuids,
      "Primary AD must include the Klardrop service UUID for scan-filter matching"
    )
  }

  @Test
  fun scanResponseCarriesShortDeviceIdAsServiceData() {
    // Android peers read the shortDeviceId out of service-data without opening
    // a GATT connection. Service-data lives in the scan response because the
    // 128-bit service UUID alone consumes most of the primary AD budget.
    val payload = klardropAdvertisePayload("abcd1234")
    val sr = assertNotNull(payload.scanResponse, "Scan response must exist for service-data")
    val data = assertNotNull(sr.serviceData[BleConstants.SERVICE_UUID])
    assertEquals("abcd1234", data.decodeToString())
  }

  @Test
  fun primaryAdFitsIn31ByteLegacyBudget() {
    // Legacy BLE advertisements are 31 bytes total. The flags AD (3 bytes) is
    // prepended by the stack, leaving 28 bytes for our records. Keep primary
    // strictly within that budget so we never hit ADVERTISE_FAILED_DATA_TOO_LARGE.
    val payload = klardropAdvertisePayload("abcd1234")
    assertTrue(
      payload.primary.approxBytes <= BleAdvertisePayload.LEGACY_AD_BUDGET_BYTES,
      "Primary AD is ${payload.primary.approxBytes} bytes, must be ≤ ${BleAdvertisePayload.LEGACY_AD_BUDGET_BYTES}"
    )
  }

  @Test
  fun scanResponseFitsIn31ByteLegacyBudget() {
    val payload = klardropAdvertisePayload("abcd1234")
    val sr = assertNotNull(payload.scanResponse)
    assertTrue(
      sr.approxBytes <= BleAdvertisePayload.LEGACY_AD_BUDGET_BYTES,
      "Scan response is ${sr.approxBytes} bytes, must be ≤ ${BleAdvertisePayload.LEGACY_AD_BUDGET_BYTES}"
    )
  }

  @Test
  fun serviceUuidAndServiceDataKeyMatch() {
    // The scan-filter UUID and the service-data key MUST be the same UUID.
    // If they ever drift, peers will see the advertisement but extract a
    // shortDeviceId keyed under the wrong UUID — silent breakage.
    val payload = klardropAdvertisePayload("abcd1234")
    val advertisedUuid = payload.primary.serviceUuids.single()
    val serviceDataKey = payload.scanResponse?.serviceData?.keys?.single()
    assertEquals(advertisedUuid, serviceDataKey)
  }

  @Test
  fun rejectsShortDeviceIdLongerThanEightChars() {
    // Defensive contract: the AD budget can't accommodate longer ids, so the
    // builder rejects them rather than silently producing oversized payloads
    // that the BLE stack would refuse with ADVERTISE_FAILED_DATA_TOO_LARGE.
    val ex = assertFailsWith<IllegalArgumentException> {
      klardropAdvertisePayload("abcd12345678abcd")
    }
    assertTrue(ex.message!!.contains("exceeds"), "message: ${ex.message}")
  }
}
