package com.carlom.klardrop

import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.database.File_transfers
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.carlom.klardrop.common.persistence.ChatMessage
import com.carlom.klardrop.common.persistence.FileTransferStatus
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType
import com.carlom.klardrop.common.persistence.SendStatus
import com.carlom.klardrop.common.utils.DeviceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A pairing outlives discovery: a trusted device that isn't announcing right now must stay in
 * the list (flagged offline) so its message history is still reachable, instead of vanishing
 * along with its mDNS record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShowDevicesControllerHelperTest {

  private val dispatcher = StandardTestDispatcher()

  private val visibleDevices = MutableStateFlow<Map<String, DiscoveryDevice>>(emptyMap())
  private val unreadCounts = MutableStateFlow<Map<String, Long>>(emptyMap())
  private val trustedDevices = MutableStateFlow<Map<String, DeviceInfo>>(emptyMap())
  private val reachability = MutableStateFlow<Map<String, Reachability>>(emptyMap())

  private val laptop = DeviceInfo(
    deviceId = "laptop-0123456789",
    name = "Work laptop",
    deviceType = DeviceType.DESKTOP,
  )
  private val phone = DeviceInfo(
    deviceId = "phone-9876543210",
    name = "Pixel",
    deviceType = DeviceType.MOBILE,
  )

  private fun visible(deviceInfo: DeviceInfo, connection: DeviceConnection) = mapOf(
    deviceInfo.deviceId to DiscoveryDevice(deviceInfo, listOf(connection), lastSeenTimestamp = 0L),
  )

  private fun helper(scope: CoroutineScope) = ShowDevicesControllerHelper(
    coroutineScope = scope,
    visibleDevices = visibleDevices,
    messageRepository = FakeMessageRepository(unreadCounts),
    trustedDevices = trustedDevices,
    reachabilitySource = reachability,
  )

  /** Builds the helper, drains its flow, and returns the rows it produced, keyed by device id. */
  private fun TestScope.devices(): Map<String, DeviceUi> {
    // Detached from the test's own job: the helper collects forever, and runTest fails the
    // test if a coroutine it owns is still running at the end.
    val scope = CoroutineScope(coroutineContext + Job())
    val helper = helper(scope)
    var latest: Collection<DeviceUi> = emptyList()
    scope.launch { helper.devicesFlow.collect { latest = it } }
    advanceUntilIdle()
    scope.cancel()
    return latest.associateBy { it.deviceId }
  }

  @Test
  fun listsATrustedDeviceThatIsNotVisibleAsOffline() = runTest(dispatcher) {
    trustedDevices.value = mapOf(laptop.deviceId to laptop)
    unreadCounts.value = mapOf(laptop.deviceId to 2L)

    val row = assertNotNull(devices()[laptop.deviceId])

    assertEquals("Work laptop", row.deviceName)
    assertEquals(DeviceType.DESKTOP, row.deviceType)
    assertEquals(TrustStatus.Trusted, row.trustStatus)
    assertEquals(Reachability.Unreachable, row.reachability)
    assertTrue(row.hasUnreadMessages)
    assertTrue(row.connectionTypes.isEmpty())
  }

  @Test
  fun doesNotDuplicateATrustedDeviceThatIsAlsoVisible() = runTest(dispatcher) {
    visibleDevices.value = visible(laptop, DeviceConnection.KlardropConnection("10.0.0.2", 4444))
    trustedDevices.value = mapOf(laptop.deviceId to laptop)
    reachability.value = mapOf(laptop.deviceId to Reachability.Reachable)

    val rows = devices()

    assertEquals(1, rows.size)
    val row = assertNotNull(rows[laptop.deviceId])
    assertEquals(TrustStatus.Trusted, row.trustStatus)
    assertEquals(Reachability.Reachable, row.reachability)
    assertEquals(listOf(DeviceConnection.DeviceConnectionType.KLARDROP), row.connectionTypes)
  }

  @Test
  fun keepsAReachableVerdictForATrustedDeviceThatStoppedAnnouncing() = runTest(dispatcher) {
    // A live connection can outlive the mDNS record; don't call such a peer offline.
    trustedDevices.value = mapOf(laptop.deviceId to laptop)
    reachability.value = mapOf(laptop.deviceId to Reachability.Reachable)

    assertEquals(Reachability.Reachable, devices().getValue(laptop.deviceId).reachability)
  }

  @Test
  fun leavesUntrustedVisibleDevicesAlone() = runTest(dispatcher) {
    visibleDevices.value = visible(phone, DeviceConnection.KlardropConnection("10.0.0.3", 4444))

    val rows = devices()

    assertEquals(TrustStatus.Untrusted, rows.getValue(phone.deviceId).trustStatus)
    assertNull(rows[laptop.deviceId])
  }
}

private class FakeMessageRepository(
  private val unreadCounts: StateFlow<Map<String, Long>>,
) : MessageRepository {
  override suspend fun insertMessage(
    remoteDeviceId: String,
    content: String,
    isSender: Boolean,
    messageType: MessageType,
    fileTransferId: Long?,
    isRead: Boolean,
    mimeType: String,
    messageId: Long?,
    sendStatus: SendStatus,
  ): Long = 0L

  override suspend fun insertFileTransfer(
    fileName: String,
    filePath: String,
    totalSize: Long,
    status: FileTransferStatus,
    mimeType: String,
  ): Long = 0L

  override suspend fun updateFileTransferStatus(id: Long, status: FileTransferStatus) = Unit
  override suspend fun markStaleInProgressAsFailed() = Unit
  override fun getMessagesForDevice(remoteDeviceId: String, limit: Long): Flow<List<ChatMessage>> = flowOf(emptyList())
  override fun getFileTransferById(id: Long): Flow<File_transfers?> = flowOf(null)
  override suspend fun updateFileTransferFilePath(id: Long, filePath: String) = Unit
  override suspend fun markMessagesAsRead(remoteDeviceId: String) = Unit
  override suspend fun getUnreadCountForDevice(remoteDeviceId: String): Long = 0L
  override fun getAllDevicesWithUnreadCounts(): Flow<Map<String, Long>> = unreadCounts
}
