package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoveryIngestFilterTest {

  private val info = DeviceInfo(
    deviceId = "dae596bf",
    name = "Carlo's Pixel",
    deviceType = DeviceType.MOBILE,
    osType = OsType.ANDROID,
  )
  private val endpoint = DeviceConnection.KlardropConnection("10.79.71.209", 46099)
  private val stored = DiscoveryDevice(info, listOf(endpoint), lastSeenTimestamp = 0L)

  // --- classify ---

  @Test
  fun unknownDeviceIsProcessed() {
    val filter = DiscoveryIngestFilter(nowMs = { 0L })
    assertEquals(IngestOutcome.Process, filter.classify(info, endpoint, null))
  }

  @Test
  fun identicalEndpointAndIdentityIsDuplicate() {
    val filter = DiscoveryIngestFilter(nowMs = { 0L })
    assertEquals(IngestOutcome.Duplicate, filter.classify(info, endpoint, stored))
  }

  @Test
  fun endpointPortMoveIsProcessed() {
    val filter = DiscoveryIngestFilter(nowMs = { 0L })
    val moved = DeviceConnection.KlardropConnection("10.79.71.209", 46100)
    assertEquals(IngestOutcome.Process, filter.classify(info, moved, stored))
  }

  @Test
  fun endpointAddressMoveIsProcessed() {
    val filter = DiscoveryIngestFilter(nowMs = { 0L })
    val moved = DeviceConnection.KlardropConnection("10.79.71.210", 46099)
    assertEquals(IngestOutcome.Process, filter.classify(info, moved, stored))
  }

  @Test
  fun identityChangeOnSameEndpointIsProcessed() {
    val filter = DiscoveryIngestFilter(nowMs = { 0L })
    val renamed = info.copy(name = "New name")
    assertEquals(IngestOutcome.Process, filter.classify(renamed, endpoint, stored))
  }

  @Test
  fun sameAddressUnderOtherTransportIsProcessed() {
    // A Nearby endpoint at an address known only as Klardrop must still flow
    // through (VisibleDevices owns the cross-transport supersede logic).
    val filter = DiscoveryIngestFilter(nowMs = { 0L })
    val nearby = DeviceConnection.NearbyConnection("10.79.71.209", 46099)
    assertEquals(IngestOutcome.Process, filter.classify(info, nearby, stored))
  }

  // --- touchDueForDuplicate ---

  @Test
  fun firstDuplicateTouchIsDueThenThrottled() {
    var now = 1_000L
    val filter = DiscoveryIngestFilter(nowMs = { now })
    assertTrue(filter.touchDueForDuplicate("dae596bf"))
    assertFalse(filter.touchDueForDuplicate("dae596bf"))
    now += DiscoveryIngestFilter.DUPLICATE_TOUCH_INTERVAL_MS
    assertTrue(filter.touchDueForDuplicate("dae596bf"))
  }

  @Test
  fun touchThrottleIsPerDevice() {
    val filter = DiscoveryIngestFilter(nowMs = { 0L })
    assertTrue(filter.touchDueForDuplicate("aaa"))
    assertTrue(filter.touchDueForDuplicate("bbb"))
    assertFalse(filter.touchDueForDuplicate("aaa"))
  }

  // --- shouldLogInvalid / onMdnsRebuilt ---

  @Test
  fun invalidVerdictLoggedOncePerService() {
    val filter = DiscoveryIngestFilter(nowMs = { 0L })
    assertTrue(filter.shouldLogInvalid("klardrop|_klardrop._tcp.|ZGFlNTk2YmY"))
    assertFalse(filter.shouldLogInvalid("klardrop|_klardrop._tcp.|ZGFlNTk2YmY"))
    assertTrue(filter.shouldLogInvalid("nearby|_nearbyshare._tcp.|ZGFlNTk2YmY"))
  }

  @Test
  fun mdnsRebuildResetsVerdictsAndTouches() {
    var now = 0L
    val filter = DiscoveryIngestFilter(nowMs = { now })
    assertTrue(filter.touchDueForDuplicate("dae596bf"))
    assertTrue(filter.shouldLogInvalid("k"))
    filter.onMdnsRebuilt()
    // Touch history cleared: due again even though the interval hasn't elapsed.
    assertTrue(filter.touchDueForDuplicate("dae596bf"))
    assertTrue(filter.shouldLogInvalid("k"))
    // ...but the fresh verdicts stick.
    now += 1L
    assertFalse(filter.touchDueForDuplicate("dae596bf"))
    assertFalse(filter.shouldLogInvalid("k"))
  }
}
