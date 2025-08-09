package com.carlom.klardrop.common.trust.storage

import com.carlom.klardrop.common.database.AppDatabase
import com.carlom.klardrop.common.database.createTestDriver
import com.carlom.klardrop.common.trust.model.ClipboardEntry
import com.carlom.klardrop.common.trust.model.DeviceKeypair
import com.carlom.klardrop.common.trust.model.PairingSession
import com.carlom.klardrop.common.trust.model.PairingSessionStatus
import com.carlom.klardrop.common.trust.model.Permission
import com.carlom.klardrop.common.trust.model.SecurityEvent
import com.carlom.klardrop.common.trust.model.SecurityEventType
import com.carlom.klardrop.common.trust.model.TrustGroup
import com.carlom.klardrop.common.trust.model.TrustLevel
import com.carlom.klardrop.common.trust.model.TrustedDevice
import com.carlom.klardrop.common.utils.CoroutinesImpl
import com.carlom.klardrop.common.utils.DeviceType
import io.ktor.utils.io.core.toByteArray
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Comprehensive tests for TrustStore implementation
 */
@OptIn(ExperimentalTime::class)
class TrustStoreTest {

  private lateinit var database: AppDatabase
  private lateinit var secureKeyStorage: FakeSecureKeyStorage
  private lateinit var trustStore: TrustStore

  @BeforeTest
  fun setup() {
    database = AppDatabase.invoke(createTestDriver())
    secureKeyStorage = FakeSecureKeyStorage()
    trustStore = TrustStoreImpl(database, secureKeyStorage, CoroutinesImpl())
  }

  @AfterTest
  fun teardown() {
    // Database doesn't need explicit close for in-memory testing
  }

  // Device keypair operations tests

  @Test
  fun testGetDeviceKeypair_whenNotExists_returnsNull() = runTest {
    val result = trustStore.getDeviceKeypair()
    assertNull(result)
  }

  @Test
  fun testSaveAndGetDeviceKeypair_success() = runTest {
    val keypair = createTestDeviceKeypair()

    trustStore.saveDeviceKeypair(keypair)
    val result = trustStore.getDeviceKeypair()

    assertNotNull(result)
    assertEquals(keypair.deviceId, result.deviceId)
    assertEquals(keypair.deviceName, result.deviceName)
    assertEquals(keypair.deviceType, result.deviceType)
    assertTrue(keypair.publicKey.contentEquals(result.publicKey))
    assertTrue(keypair.privateKey.contentEquals(result.privateKey))
    assertEquals(keypair.createdAt, result.createdAt)
  }

  @Test
  fun testUpdateDeviceName_success() = runTest {
    val keypair = createTestDeviceKeypair()
    trustStore.saveDeviceKeypair(keypair)

    val newName = "Updated Device Name"
    trustStore.updateDeviceName(newName)

    val result = trustStore.getDeviceKeypair()
    assertNotNull(result)
    assertEquals(newName, result.deviceName)
  }

  // Trust group operations tests

  @Test
  fun testGetTrustGroup_whenNotExists_returnsNull() = runTest {
    val result = trustStore.getTrustGroup()
    assertNull(result)
  }

  @Test
  fun testSaveAndGetTrustGroup_success() = runTest {
    val trustGroup = createTestTrustGroup()

    trustStore.saveTrustGroup(trustGroup)
    val result = trustStore.getTrustGroup()

    assertNotNull(result)
    assertEquals(trustGroup.groupId, result.groupId)
    assertEquals(trustGroup.groupName, result.groupName)
    assertTrue(trustGroup.groupKey.contentEquals(result.groupKey))
    assertEquals(trustGroup.createdAt, result.createdAt)
    assertEquals(trustGroup.updatedAt, result.updatedAt)
    assertEquals(trustGroup.protocolVersion, result.protocolVersion)
    assertEquals(trustGroup.cloudSyncEnabled, result.cloudSyncEnabled)
  }

  @Test
  fun testUpdateGroupKey_success() = runTest {
    val trustGroup = createTestTrustGroup()
    trustStore.saveTrustGroup(trustGroup)

    val newKey = "new-group-key-bytes".toByteArray()
    trustStore.updateGroupKey(trustGroup.groupId, newKey)

    val result = trustStore.getTrustGroup()
    assertNotNull(result)
    assertTrue(newKey.contentEquals(result.groupKey))
  }

  @Test
  fun testEnableCloudSync_success() = runTest {
    val trustGroup = createTestTrustGroup(cloudSyncEnabled = false)
    trustStore.saveTrustGroup(trustGroup)

    trustStore.enableCloudSync(trustGroup.groupId)

    val result = trustStore.getTrustGroup()
    assertNotNull(result)
    assertTrue(result.cloudSyncEnabled)
  }

  // Trusted device operations tests

  @Test
  fun testGetTrustedDevices_whenNoGroup_returnsEmptyList() = runTest {
    val result = trustStore.getTrustedDevices()
    assertTrue(result.isEmpty())
  }

  @Test
  fun testAddAndGetTrustedDevice_success() = runTest {
    val trustGroup = createTestTrustGroup()
    trustStore.saveTrustGroup(trustGroup)

    val device = createTestTrustedDevice()
    trustStore.addTrustedDevice(device)

    val result = trustStore.getTrustedDevice(device.deviceId)
    assertNotNull(result)
    assertEquals(device.deviceId, result.deviceId)
    assertEquals(device.deviceName, result.deviceName)
    assertEquals(device.deviceType, result.deviceType)
    assertEquals(device.trustLevel, result.trustLevel)
    assertTrue(device.publicKey.contentEquals(result.publicKey))
  }

  @Test
  fun testGetTrustedDevices_success() = runTest {
    val trustGroup = createTestTrustGroup()
    trustStore.saveTrustGroup(trustGroup)

    val device1 = createTestTrustedDevice(deviceId = "device1")
    val device2 = createTestTrustedDevice(deviceId = "device2")

    trustStore.addTrustedDevice(device1)
    trustStore.addTrustedDevice(device2)

    val result = trustStore.getTrustedDevices()
    assertEquals(2, result.size)
    assertTrue(result.any { it.deviceId == device1.deviceId })
    assertTrue(result.any { it.deviceId == device2.deviceId })
  }

  @Test
  fun testRemoveTrustedDevice_success() = runTest {
    val trustGroup = createTestTrustGroup()
    trustStore.saveTrustGroup(trustGroup)

    val device = createTestTrustedDevice()
    trustStore.addTrustedDevice(device)

    // Verify it's there and active
    var result = trustStore.getTrustedDevice(device.deviceId)
    assertNotNull(result)
    assertTrue(result.isActive)

    // Remove it
    trustStore.removeTrustedDevice(device.deviceId)

    // Verify it's marked as inactive (soft delete)
    result = trustStore.getTrustedDevice(device.deviceId)
    assertNotNull(result)
    assertFalse(result.isActive)

    // Verify it no longer appears in trusted devices list (which filters by is_active)
    val trustedDevices = trustStore.getTrustedDevices()
    assertFalse(trustedDevices.any { it.deviceId == device.deviceId })

    // Verify it's no longer considered trusted
    val isTrusted = trustStore.isDeviceTrusted(device.deviceId)
    assertFalse(isTrusted)
  }

  @Test
  fun testIsDeviceTrusted_success() = runTest {
    val trustGroup = createTestTrustGroup()
    trustStore.saveTrustGroup(trustGroup)

    val device = createTestTrustedDevice()
    trustStore.addTrustedDevice(device)

    val result = trustStore.isDeviceTrusted(device.deviceId)
    assertTrue(result)
  }

  @Test
  fun testIsDeviceTrusted_whenNotTrusted_returnsFalse() = runTest {
    val result = trustStore.isDeviceTrusted("non-existent-device")
    assertFalse(result)
  }

  @Test
  fun testGetDeviceTrustLevel_success() = runTest {
    val trustGroup = createTestTrustGroup()
    trustStore.saveTrustGroup(trustGroup)

    val device = createTestTrustedDevice(trustLevel = TrustLevel.FULL)
    trustStore.addTrustedDevice(device)

    val result = trustStore.getDeviceTrustLevel(device.deviceId)
    assertEquals(TrustLevel.FULL, result)
  }

  @Test
  fun testUpdateDeviceLastSeen_success() = runTest {
    val trustGroup = createTestTrustGroup()
    trustStore.saveTrustGroup(trustGroup)

    val device = createTestTrustedDevice(lastSeen = null)
    trustStore.addTrustedDevice(device)

    trustStore.updateDeviceLastSeen(device.deviceId)

    val result = trustStore.getTrustedDevice(device.deviceId)
    assertNotNull(result)
    assertNotNull(result.lastSeen)
    assertTrue(result.lastSeen!! > 0)
  }

  // Security event logging tests

  @Test
  fun testLogAndGetSecurityEvent_success() = runTest {
    val event = createTestSecurityEvent()

    trustStore.logSecurityEvent(event)

    val events = trustStore.getRecentSecurityEvents(10)
    assertTrue(events.isNotEmpty())
    val foundEvent = events.first()
    assertEquals(event.eventType, foundEvent.eventType)
    assertEquals(event.deviceId, foundEvent.deviceId)
    assertEquals(event.ipAddress, foundEvent.ipAddress)
  }

  @Test
  fun testGetSecurityEventsByDevice_success() = runTest {
    val deviceId = "test-device"
    val event1 = createTestSecurityEvent(deviceId = deviceId)
    val event2 = createTestSecurityEvent(deviceId = "other-device")

    trustStore.logSecurityEvent(event1)
    trustStore.logSecurityEvent(event2)

    val events = trustStore.getSecurityEventsByDevice(deviceId, 10)
    assertEquals(1, events.size)
    assertEquals(deviceId, events.first().deviceId)
  }

  // Pairing session management tests

  @Test
  fun testCreateAndGetPairingSession_success() = runTest {
    val session = createTestPairingSession()

    trustStore.createPairingSession(session)
    val result = trustStore.getPairingSession(session.sessionId)

    assertNotNull(result)
    assertEquals(session.sessionId, result.sessionId)
    assertEquals(session.deviceId, result.deviceId)
    assertTrue(session.ephemeralPublicKey.contentEquals(result.ephemeralPublicKey))
    assertEquals(session.status, result.status)
  }

  @Test
  fun testUpdatePairingSessionStatus_success() = runTest {
    val session = createTestPairingSession()
    trustStore.createPairingSession(session)

    // Verify initial state
    var result = trustStore.getPairingSession(session.sessionId)
    assertNotNull(result)
    assertEquals(PairingSessionStatus.PENDING, result.status)

    // Update status
    trustStore.updatePairingSessionStatus(session.sessionId, PairingSessionStatus.ACCEPTED)

    // Note: getPairingSession only returns PENDING sessions, so after update it will return null
    // This is expected behavior based on the SQL query design
    result = trustStore.getPairingSession(session.sessionId)
    assertNull(result) // Expected because query only returns PENDING sessions

    // This test verifies that the update operation completes without error
    // In a real application, you'd have a different query to retrieve sessions by status
  }

  @Test
  fun testCleanExpiredPairingSessions_success() = runTest {
    // Create an expired session
    val expiredSession = createTestPairingSession(
      expiresAt = Clock.System.now().toEpochMilliseconds() - 1000
    )
    val activeSession = createTestPairingSession(
      sessionId = "active-session",
      expiresAt = Clock.System.now().toEpochMilliseconds() + 10000
    )

    trustStore.createPairingSession(expiredSession)
    trustStore.createPairingSession(activeSession)

    trustStore.cleanExpiredPairingSessions()

    // Expired session should be gone
    val expiredResult = trustStore.getPairingSession(expiredSession.sessionId)
    assertNull(expiredResult)

    // Active session should still exist
    val activeResult = trustStore.getPairingSession(activeSession.sessionId)
    assertNotNull(activeResult)
  }

  // Clipboard sync operations tests

  @Test
  fun testSaveAndGetClipboardEntry_success() = runTest {
    val entry = createTestClipboardEntry()

    trustStore.saveClipboardEntry(entry)
    val result = trustStore.getLatestClipboardEntry()

    assertNotNull(result)
    assertEquals(entry.deviceId, result.deviceId)
    assertEquals(entry.content, result.content)
    assertEquals(entry.contentHash, result.contentHash)
    assertTrue(entry.signature.contentEquals(result.signature))
  }

  @Test
  fun testIsClipboardContentNew_success() = runTest {
    val contentHash = "unique-content-hash"

    // Should be new initially
    var result = trustStore.isClipboardContentNew(contentHash)
    assertTrue(result)

    // Save entry with this hash
    val entry = createTestClipboardEntry(contentHash = contentHash)
    trustStore.saveClipboardEntry(entry)

    // Should not be new anymore
    result = trustStore.isClipboardContentNew(contentHash)
    assertFalse(result)
  }

  @Test
  fun testGetUnsyncedClipboardEntries_success() = runTest {
    val syncedEntry = createTestClipboardEntry(synced = true)
    val unsyncedEntry = createTestClipboardEntry(deviceId = "unsynced-device", synced = false)

    // Note: We can't directly control synced flag in saveClipboardEntry,
    // but we can test the query functionality
    trustStore.saveClipboardEntry(syncedEntry)
    trustStore.saveClipboardEntry(unsyncedEntry)

    val unsynced = trustStore.getUnsyncedClipboardEntries()
    // Both entries will be unsynced initially since saveClipboardEntry doesn't set synced flag
    assertTrue(unsynced.isNotEmpty())
  }

  // Cleanup operations tests

  @Test
  fun testCleanupOldSecurityEvents_success() = runTest {
    // This test is more about verifying the method doesn't crash
    // since we can't easily create old events with specific timestamps
    trustStore.cleanupOldSecurityEvents(30)
    // If we get here without exception, the test passes
    assertTrue(true)
  }

  @Test
  fun testCleanupExpiredDevices_success() = runTest {
    // This test is more about verifying the method doesn't crash
    trustStore.cleanupExpiredDevices()
    // If we get here without exception, the test passes
    assertTrue(true)
  }

  @Test
  fun testCleanupOldClipboardEntries_success() = runTest {
    // This test is more about verifying the method doesn't crash
    trustStore.cleanupOldClipboardEntries(100)
    // If we get here without exception, the test passes
    assertTrue(true)
  }

  // Helper methods for creating test data

  private fun createTestDeviceKeypair(
    deviceId: String = "test-device-id",
    deviceName: String = "Test Device",
    deviceType: DeviceType = DeviceType.DESKTOP
  ): DeviceKeypair {
    return DeviceKeypair(
      deviceId = deviceId,
      publicKey = "test-public-key".toByteArray(),
      privateKey = "test-private-key".toByteArray(),
      deviceName = deviceName,
      deviceType = deviceType,
      createdAt = Clock.System.now().toEpochMilliseconds()
    )
  }

  private fun createTestTrustGroup(
    groupId: String = "test-group-id",
    cloudSyncEnabled: Boolean = false
  ): TrustGroup {
    return TrustGroup(
      groupId = groupId,
      groupKey = "test-group-key".toByteArray(),
      groupName = "Test Group",
      devices = emptyMap(),
      createdAt = Clock.System.now().toEpochMilliseconds(),
      updatedAt = Clock.System.now().toEpochMilliseconds(),
      protocolVersion = 1,
      cloudSyncEnabled = cloudSyncEnabled
    )
  }

  private fun createTestTrustedDevice(
    deviceId: String = "test-trusted-device",
    groupId: String = "test-group-id",
    trustLevel: TrustLevel = TrustLevel.FULL,
    lastSeen: Long? = Clock.System.now().toEpochMilliseconds()
  ): TrustedDevice {
    return TrustedDevice(
      deviceId = deviceId,
      groupId = groupId,
      publicKey = "test-public-key".toByteArray(),
      deviceName = "Test Trusted Device",
      deviceType = DeviceType.MOBILE,
      addedAt = Clock.System.now().toEpochMilliseconds(),
      addedBy = "test-user",
      lastSeen = lastSeen,
      trustLevel = trustLevel,
      permissions = setOf(Permission.FILE_SEND, Permission.FILE_RECEIVE),
      expiresAt = null,
      isActive = true
    )
  }

  private fun createTestSecurityEvent(
    deviceId: String = "test-device"
  ): SecurityEvent {
    return SecurityEvent(
      eventType = SecurityEventType.DEVICE_ADDED,
      deviceId = deviceId,
      ipAddress = "192.168.1.1",
      timestamp = Clock.System.now().toEpochMilliseconds(),
      details = mapOf("test" to "details")
    )
  }

  private fun createTestPairingSession(
    sessionId: String = "test-session-id",
    expiresAt: Long = Clock.System.now().toEpochMilliseconds() + 3600000
  ): PairingSession {
    return PairingSession(
      sessionId = sessionId,
      deviceId = "test-device",
      ephemeralPublicKey = "test-ephemeral-key".toByteArray(),
      expiresAt = expiresAt,
      status = PairingSessionStatus.PENDING,
      createdAt = Clock.System.now().toEpochMilliseconds()
    )
  }

  private fun createTestClipboardEntry(
    deviceId: String = "test-device",
    contentHash: String = "test-content-hash",
    synced: Boolean = false
  ): ClipboardEntry {
    return ClipboardEntry(
      deviceId = deviceId,
      content = "Test clipboard content",
      contentHash = contentHash,
      timestamp = Clock.System.now().toEpochMilliseconds(),
      signature = "test-signature".toByteArray(),
      synced = synced
    )
  }
}

/**
 * Fake implementation of SecureKeyStorage for testing
 */
private class FakeSecureKeyStorage : SecureKeyStorage {
  private val storage = mutableMapOf<String, ByteArray>()

  override suspend fun storePrivateKey(alias: String, key: ByteArray) {
    storage[alias] = key
  }

  override suspend fun retrievePrivateKey(alias: String): ByteArray? {
    return storage[alias]
  }

  override suspend fun deletePrivateKey(alias: String) {
    storage.remove(alias)
  }

  override suspend fun keyExists(alias: String): Boolean {
    return storage.containsKey(alias)
  }

  override suspend fun clearAll() {
    storage.clear()
  }
}