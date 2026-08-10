package com.carlom.klardrop.common.discovery

import TestCoroutines
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.trust.InMemoryTrustStorage
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeKnownDevicesRepository(
  initial: Map<String, DeviceInfo> = emptyMap(),
) : KnownDevicesRepository {

  val entries = MutableStateFlow(initial)

  override val knownDevices: Flow<Map<String, DeviceInfo>> = entries

  override suspend fun addKnownDevice(deviceInfo: DeviceInfo) {
    entries.update { it + (deviceInfo.deviceId to deviceInfo) }
  }

  override suspend fun removeKnownDevice(deviceId: String) {
    entries.update { it - deviceId }
  }
}

/**
 * Discovery stand-in: only the visibility map and the identity cache matter here, and the
 * real [VisibleDevicesImpl] runs a periodic staleness sweep that a virtual-time test clock
 * would spin on forever.
 */
private class FakeVisibleDevices : VisibleDevices {

  private val devices = MutableStateFlow<Map<String, DiscoveryDevice>>(emptyMap())
  private val identityCache = mutableMapOf<String, DeviceInfo>()

  override val visibleDevices: StateFlow<Map<String, DiscoveryDevice>> = devices

  override suspend fun onNewDeviceVisible(deviceInfo: DeviceInfo, deviceConnection: DeviceConnection) {
    identityCache[deviceInfo.deviceId] = deviceInfo
    devices.update {
      it + (deviceInfo.deviceId to DiscoveryDevice(deviceInfo, listOf(deviceConnection), lastSeenTimestamp = 0L))
    }
  }

  override fun isDeviceVisible(deviceId: String) = devices.value.containsKey(deviceId)

  override fun getDevice(deviceId: String) = devices.value[deviceId]

  override fun cachedNameFor(deviceId: String): String? = identityCache[deviceId]?.name
    ?.takeIf { it.isNotBlank() && it != deviceId }

  override fun touchLastSeen(deviceId: String) = Unit

  override fun onDeviceLost(deviceId: String) {
    devices.update { it - deviceId }
  }

  override fun onDeviceLost(deviceId: String, deviceConnectionToRemove: DeviceConnection) = onDeviceLost(deviceId)

  override fun findDeviceByAddress(address: io.ktor.network.sockets.InetSocketAddress): DiscoveryDevice? = null

  override fun invalidateKlardropEndpoint(deviceId: String, address: String, port: Int) = Unit
}

class TrustedDevicesDirectoryTest {

  private val dispatcher = StandardTestDispatcher()
  private val coroutines = TestCoroutines(dispatcher, ioDispatcher = dispatcher)

  private val trustStorage = InMemoryTrustStorage()
  private val trustChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
  private val visibleDevices = FakeVisibleDevices()

  private val laptop = DeviceInfo(
    deviceId = "laptop-0123456789",
    name = "Work laptop",
    deviceType = DeviceType.DESKTOP,
    osType = OsType.APPLE,
  )
  private val connection = DeviceConnection.KlardropConnection(address = "10.0.0.2", port = 4444)

  private fun directory(knownDevices: KnownDevicesRepository) = TrustedDevicesDirectory(
    visibleDevices = visibleDevices,
    knownDevicesRepository = knownDevices,
    trustStorage = trustStorage,
    trustChanges = trustChanges,
    coroutines = coroutines,
  )

  @Test
  fun keepsTrustedDeviceListedAfterItStopsBeingVisible() = runTest(dispatcher) {
    val knownDevices = FakeKnownDevicesRepository()
    val directory = directory(knownDevices)

    visibleDevices.onNewDeviceVisible(laptop, connection)
    trustStorage.storeTrustedDevice(laptop.deviceId, byteArrayOf(1, 2, 3))
    trustChanges.emit(Unit)
    advanceUntilIdle()

    assertEquals(laptop, directory.trustedDevices.value[laptop.deviceId])
    // The identity is snapshotted while the peer is visible — that is the only moment we
    // ever learn it.
    assertEquals(laptop, knownDevices.entries.value[laptop.deviceId])

    visibleDevices.onDeviceLost(laptop.deviceId, connection)
    advanceUntilIdle()

    assertTrue(visibleDevices.visibleDevices.value.isEmpty())
    val offline = assertNotNull(directory.trustedDevices.value[laptop.deviceId])
    assertEquals("Work laptop", offline.name)
    assertEquals(DeviceType.DESKTOP, offline.deviceType)
    assertEquals(OsType.APPLE, offline.osType)
  }

  @Test
  fun replaysPersistedIdentityForDeviceNeverSeenThisSession() = runTest(dispatcher) {
    // Cold start: nothing has been discovered yet, the pairing and identity are on disk.
    trustStorage.storeTrustedDevice(laptop.deviceId, byteArrayOf(1, 2, 3))
    val directory = directory(FakeKnownDevicesRepository(mapOf(laptop.deviceId to laptop)))

    advanceUntilIdle()

    assertEquals(laptop, directory.trustedDevices.value[laptop.deviceId])
  }

  @Test
  fun listsTrustedDeviceWithNoStoredIdentityUnderAShortenedId() = runTest(dispatcher) {
    // Paired before identities were snapshotted, and not seen since.
    trustStorage.storeTrustedDevice(laptop.deviceId, byteArrayOf(1, 2, 3))
    val knownDevices = FakeKnownDevicesRepository()
    val directory = directory(knownDevices)

    advanceUntilIdle()

    val entry = assertNotNull(directory.trustedDevices.value[laptop.deviceId])
    assertEquals("laptop-0", entry.name)
    assertEquals(DeviceType.UNKNOWN, entry.deviceType)
    // A placeholder identity is not worth persisting; the real one lands on next sighting.
    assertTrue(knownDevices.entries.value.isEmpty())
  }

  @Test
  fun dropsDeviceAndItsStoredIdentityOnceTrustIsRemoved() = runTest(dispatcher) {
    trustStorage.storeTrustedDevice(laptop.deviceId, byteArrayOf(1, 2, 3))
    val knownDevices = FakeKnownDevicesRepository(mapOf(laptop.deviceId to laptop))
    val directory = directory(knownDevices)
    advanceUntilIdle()
    assertNotNull(directory.trustedDevices.value[laptop.deviceId])

    trustStorage.removeTrustedDevice(laptop.deviceId)
    trustChanges.emit(Unit)
    advanceUntilIdle()

    assertNull(directory.trustedDevices.value[laptop.deviceId])
    assertTrue(knownDevices.entries.value.isEmpty())
  }

  @Test
  fun prefersTheRicherIdentityWhenDiscoveryOnlyHasAPlaceholder() = runTest(dispatcher) {
    trustStorage.storeTrustedDevice(laptop.deviceId, byteArrayOf(1, 2, 3))
    val knownDevices = FakeKnownDevicesRepository(mapOf(laptop.deviceId to laptop))
    val directory = directory(knownDevices)

    // BLE announces a placeholder: name == deviceId, everything else UNKNOWN.
    visibleDevices.onNewDeviceVisible(
      DeviceInfo(
        deviceId = laptop.deviceId,
        name = laptop.deviceId,
        deviceType = DeviceType.UNKNOWN,
        osType = OsType.UNKNOWN,
      ),
      DeviceConnection.BleConnection(address = "AA:BB:CC:DD:EE:FF"),
    )
    advanceUntilIdle()

    assertEquals(laptop, directory.trustedDevices.value[laptop.deviceId])
  }
}
