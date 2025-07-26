package com.carlom.klardrop.common.trust.integration

import TestCoroutines
import app.cash.turbine.test
import com.carlom.klardrop.common.trust.crypto.CryptoProviderImpl
import com.carlom.klardrop.common.trust.model.*
import com.carlom.klardrop.common.trust.protocol.*
import com.carlom.klardrop.common.trust.storage.*
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.protos.trust.*
import com.google.protobuf.ByteString
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Integration tests for the trust system that test full workflows
 * across multiple components working together.
 */
class TrustIntegrationTest {
    
    private val testCoroutines = TestCoroutines()
    private val cryptoProvider = CryptoProviderImpl()
    
    @Test
    fun testFullPairingWorkflow() = runTest(testCoroutines.dispatcher) {
        // Setup two devices
        val device1 = createTestDevice("device-1", "Alice's Phone", DeviceType.DEVICE_TYPE_MOBILE)
        val device2 = createTestDevice("device-2", "Bob's Laptop", DeviceType.DEVICE_TYPE_DESKTOP)
        
        // Device 1 creates a trust group
        val trustGroup = device1.createTrustGroup("Alice's Devices")
        assertNotNull(trustGroup)
        
        // Device 2 sends discovery announcement
        val announcement = device2.createDiscoveryAnnouncement()
        device1.handleDiscoveryAnnouncement(announcement, "192.168.1.100")
        
        // Device 1 initiates pairing with Device 2
        val sessionId = device1.initiatePairing(device2.deviceInfo.deviceId)
        assertNotNull(sessionId)
        
        // Check that ECDH initiation was sent
        val sentMessage = device1.getSentMessages().firstOrNull()
        assertNotNull(sentMessage)
        assertEquals(TrustMessageType.MESSAGE_TYPE_ECDH_INITIATION, sentMessage.second.type)
        
        // Device 2 handles ECDH initiation
        val initiation = ECDHInitiation.parseFrom(sentMessage.second.payload)
        device2.handleECDHInitiation(initiation, "192.168.1.101")
        
        // Check that ECDH response was sent
        val responseMessage = device2.getSentMessages().firstOrNull()
        assertNotNull(responseMessage)
        assertEquals(TrustMessageType.MESSAGE_TYPE_ECDH_RESPONSE, responseMessage.second.type)
        
        // Device 1 handles ECDH response
        val response = ECDHResponse.parseFrom(responseMessage.second.payload)
        device1.handleECDHResponse(response)
        
        // Check that group invitation was sent
        val invitationMessage = device1.getSentMessages().find { 
            it.second.type == TrustMessageType.MESSAGE_TYPE_GROUP_INVITATION 
        }
        assertNotNull(invitationMessage)
        
        // Device 2 handles group invitation
        val invitation = GroupInvitation.parseFrom(invitationMessage.second.payload)
        device2.handleGroupInvitation(invitation)
        
        // Device 2 should now be part of the trust group
        assertTrue(device2.isInTrustGroup())
        
        // Both devices should trust each other
        assertTrue(device1.isDeviceTrusted(device2.deviceInfo.deviceId))
        assertTrue(device2.isDeviceTrusted(device1.deviceInfo.deviceId))
    }
    
    @Test
    fun testClipboardSyncBetweenTrustedDevices() = runTest(testCoroutines.dispatcher) {
        // Setup two devices in the same trust group
        val (device1, device2) = setupTrustedDevices()
        
        // Device 1 updates clipboard
        val clipboardContent = "Hello from Device 1!"
        device1.updateClipboard(clipboardContent)
        
        // Check clipboard sync message was sent
        val syncMessage = device1.getSentMessages().find {
            it.second.type == TrustMessageType.MESSAGE_TYPE_CLIPBOARD_SYNC
        }
        assertNotNull(syncMessage)
        assertEquals(device2.deviceInfo.deviceId, syncMessage.first)
        
        // Device 2 handles clipboard sync
        val clipboardSync = ClipboardSync.parseFrom(syncMessage.second.payload)
        device2.handleClipboardSync(clipboardSync)
        
        // Device 2 should receive the clipboard content
        device2.getTrustEvents().test {
            val event = awaitItem()
            assertTrue(event is TrustEvent.ClipboardUpdate)
            assertEquals(clipboardContent, event.content)
            assertEquals(device1.deviceInfo.deviceId, event.fromDevice)
        }
        
        // Verify clipboard entry was saved
        val latestEntry = device2.trustStore.getLatestClipboardEntry()
        assertNotNull(latestEntry)
        assertEquals(clipboardContent, latestEntry.content)
    }
    
    @Test
    fun testMemberUpdatePropagation() = runTest(testCoroutines.dispatcher) {
        // Setup three devices
        val device1 = createTestDevice("device-1", "Device 1", DeviceType.DEVICE_TYPE_MOBILE)
        val device2 = createTestDevice("device-2", "Device 2", DeviceType.DEVICE_TYPE_DESKTOP)
        val device3 = createTestDevice("device-3", "Device 3", DeviceType.DEVICE_TYPE_TABLET)
        
        // Create trust group with device 1 and 2
        val (dev1, dev2) = setupTrustedDevices(device1, device2)
        
        // Device 1 adds device 3 to the group
        val device3TrustedInfo = TrustedDevice(
            deviceId = device3.deviceInfo.deviceId,
            groupId = dev1.trustStore.getTrustGroup()!!.groupId,
            publicKey = device3.deviceInfo.publicKey,
            deviceName = device3.deviceInfo.deviceName,
            deviceType = device3.deviceInfo.deviceType,
            addedAt = Clock().currentTimeMillis(),
            addedBy = dev1.deviceInfo.deviceId
        )
        
        dev1.addDeviceToGroup(device3TrustedInfo)
        
        // Check member update was sent to device 2
        val updateMessage = dev1.getSentMessages().find {
            it.second.type == TrustMessageType.MESSAGE_TYPE_MEMBER_UPDATE &&
            it.first == dev2.deviceInfo.deviceId
        }
        assertNotNull(updateMessage)
        
        // Device 2 handles member update
        val memberUpdate = MemberUpdate.parseFrom(updateMessage.second.payload)
        dev2.handleMemberUpdate(memberUpdate)
        
        // Device 2 should now know about device 3
        assertTrue(dev2.isDeviceTrusted(device3.deviceInfo.deviceId))
        
        // Check device 2's trust events
        dev2.getTrustEvents().test {
            val event = awaitItem()
            assertTrue(event is TrustEvent.DeviceJoined)
            assertEquals(device3.deviceInfo.deviceId, event.device.deviceId)
        }
    }
    
    @Test
    fun testSecurityEventLoggingDuringPairing() = runTest(testCoroutines.dispatcher) {
        val device1 = createTestDevice("device-1", "Device 1", DeviceType.DEVICE_TYPE_MOBILE)
        val device2 = createTestDevice("device-2", "Device 2", DeviceType.DEVICE_TYPE_DESKTOP)
        
        // Create malicious device with invalid signature
        val maliciousAnnouncement = DiscoveryAnnouncement.newBuilder()
            .setDeviceId("malicious-device")
            .setPublicKey(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
            .setIsInTrustGroup(false)
            .setSupportsAutoTrust(true)
            .setTimestamp(Clock().currentTimeMillis())
            .setProtocolVersion(1)
            .setSignature(ByteString.copyFrom(byteArrayOf(4, 5, 6))) // Invalid signature
            .build()
        
        // Device 1 handles malicious announcement
        device1.handleDiscoveryAnnouncement(maliciousAnnouncement, "192.168.1.666")
        
        // Check security event was logged
        val securityEvents = device1.trustStore.getRecentSecurityEvents(10)
        assertEquals(1, securityEvents.size)
        
        val event = securityEvents[0]
        assertEquals(SecurityEventType.AUTH_FAILED, event.eventType)
        assertEquals("malicious-device", event.deviceId)
        assertEquals("192.168.1.666", event.ipAddress)
        assertEquals("Invalid signature", event.details?.get("reason"))
    }
    
    @Test
    fun testKeyRotation() = runTest(testCoroutines.dispatcher) {
        // Setup trusted devices
        val (device1, device2) = setupTrustedDevices()
        
        val originalGroupKey = device1.trustStore.getTrustGroup()!!.groupKey
        
        // Device 1 rotates group key
        val newGroupKey = cryptoProvider.generateAESKey()
        device1.rotateGroupKey(newGroupKey)
        
        // Check key update was sent to device 2
        val keyUpdateMessage = device1.getSentMessages().find {
            it.second.type == TrustMessageType.MESSAGE_TYPE_KEY_ROTATION
        }
        assertNotNull(keyUpdateMessage)
        
        // Verify new key is different
        assertFalse(originalGroupKey.contentEquals(newGroupKey))
        
        // Device 2 handles key rotation
        val keyRotation = KeyRotation.parseFrom(keyUpdateMessage.second.payload)
        device2.handleKeyRotation(keyRotation)
        
        // Both devices should have the new key
        val device1NewKey = device1.trustStore.getTrustGroup()!!.groupKey
        val device2NewKey = device2.trustStore.getTrustGroup()!!.groupKey
        assertTrue(device1NewKey.contentEquals(newGroupKey))
        assertTrue(device2NewKey.contentEquals(newGroupKey))
    }
    
    @Test
    fun testDeviceRemoval() = runTest(testCoroutines.dispatcher) {
        // Setup three devices
        val device1 = createTestDevice("device-1", "Device 1", DeviceType.DEVICE_TYPE_MOBILE)
        val device2 = createTestDevice("device-2", "Device 2", DeviceType.DEVICE_TYPE_DESKTOP)
        val device3 = createTestDevice("device-3", "Device 3", DeviceType.DEVICE_TYPE_TABLET)
        
        // Create trust group with all three devices
        val trustGroup = device1.createTrustGroup("Test Group")
        device1.addDeviceToGroup(createTrustedDevice(device2, trustGroup.groupId, device1.deviceInfo.deviceId))
        device1.addDeviceToGroup(createTrustedDevice(device3, trustGroup.groupId, device1.deviceInfo.deviceId))
        
        // Share trust group with other devices
        device2.joinTrustGroup(trustGroup)
        device3.joinTrustGroup(trustGroup)
        
        // Device 1 removes device 3
        device1.removeDeviceFromGroup(device3.deviceInfo.deviceId)
        
        // Check removal update was sent to device 2
        val removalMessage = device1.getSentMessages().find {
            it.second.type == TrustMessageType.MESSAGE_TYPE_MEMBER_UPDATE &&
            it.first == device2.deviceInfo.deviceId
        }
        assertNotNull(removalMessage)
        
        val memberUpdate = MemberUpdate.parseFrom(removalMessage.second.payload)
        assertEquals(UpdateAction.UPDATE_ACTION_REMOVE, memberUpdate.action)
        assertEquals(device3.deviceInfo.deviceId, memberUpdate.device.deviceId)
        
        // Device 2 handles the removal
        device2.handleMemberUpdate(memberUpdate)
        
        // Device 2 should no longer trust device 3
        assertFalse(device2.isDeviceTrusted(device3.deviceInfo.deviceId))
        
        // Device 3 should no longer be in the trust group
        assertEquals(2, device1.trustStore.getTrustedDevices().size)
        assertEquals(2, device2.trustStore.getTrustedDevices().size)
    }
    
    @Test
    fun testExpiredDeviceCleanup() = runTest(testCoroutines.dispatcher) {
        val device1 = createTestDevice("device-1", "Device 1", DeviceType.DEVICE_TYPE_MOBILE)
        val trustGroup = device1.createTrustGroup("Test Group")
        
        // Add device with expiration
        val expiringDevice = TrustedDevice(
            deviceId = "expiring-device",
            groupId = trustGroup.groupId,
            publicKey = byteArrayOf(1, 2, 3),
            deviceName = "Expiring Device",
            deviceType = DeviceType.DEVICE_TYPE_DESKTOP,
            addedAt = Clock().currentTimeMillis(),
            addedBy = device1.deviceInfo.deviceId,
            expiresAt = Clock().currentTimeMillis() + 1000 // Expires in 1 second
        )
        
        device1.trustStore.addTrustedDevice(expiringDevice)
        
        // Initially device should be trusted
        assertTrue(device1.isDeviceTrusted(expiringDevice.deviceId))
        
        // Wait for expiration
        delay(1100)
        
        // Run cleanup
        device1.trustStore.cleanupExpiredDevices()
        
        // Device should no longer be trusted
        assertFalse(device1.isDeviceTrusted(expiringDevice.deviceId))
    }
    
    // Helper functions
    
    private suspend fun createTestDevice(
        deviceId: String,
        deviceName: String,
        deviceType: DeviceType
    ): TestDevice {
        val keypair = cryptoProvider.generateECDSAKeypair()
        val deviceKeypair = DeviceKeypair(
            deviceId = deviceId,
            publicKey = keypair.publicKey,
            privateKey = keypair.privateKey,
            deviceName = deviceName,
            deviceType = deviceType
        )
        
        val trustStore = InMemoryTrustStore()
        trustStore.saveDeviceKeypair(deviceKeypair)
        
        val sentMessages = mutableListOf<Pair<String, TrustMessage>>()
        
        val protocolHandler = TestTrustProtocolHandler(
            trustStore = trustStore,
            cryptoProvider = cryptoProvider,
            deviceInfo = { deviceKeypair },
            sendMessage = { targetId, message ->
                sentMessages.add(targetId to message)
            }
        )
        
        return TestDevice(
            deviceInfo = deviceKeypair,
            trustStore = trustStore,
            protocolHandler = protocolHandler,
            sentMessages = sentMessages
        )
    }
    
    private suspend fun setupTrustedDevices(
        device1: TestDevice? = null,
        device2: TestDevice? = null
    ): Pair<TestDevice, TestDevice> {
        val dev1 = device1 ?: createTestDevice("device-1", "Device 1", DeviceType.DEVICE_TYPE_MOBILE)
        val dev2 = device2 ?: createTestDevice("device-2", "Device 2", DeviceType.DEVICE_TYPE_DESKTOP)
        
        // Create trust group
        val groupKey = cryptoProvider.generateAESKey()
        val trustGroup = TrustGroup(
            groupId = "test-group-123",
            groupKey = groupKey,
            groupName = "Test Group",
            devices = mapOf(
                dev1.deviceInfo.deviceId to createTrustedDevice(dev1, "test-group-123", "self"),
                dev2.deviceInfo.deviceId to createTrustedDevice(dev2, "test-group-123", dev1.deviceInfo.deviceId)
            ),
            createdAt = Clock().currentTimeMillis(),
            updatedAt = Clock().currentTimeMillis()
        )
        
        // Save trust group to both devices
        dev1.trustStore.saveTrustGroup(trustGroup)
        dev2.trustStore.saveTrustGroup(trustGroup)
        
        // Add trusted devices
        dev1.trustStore.addTrustedDevice(trustGroup.devices[dev2.deviceInfo.deviceId]!!)
        dev2.trustStore.addTrustedDevice(trustGroup.devices[dev1.deviceInfo.deviceId]!!)
        
        return dev1 to dev2
    }
    
    private fun createTrustedDevice(
        device: TestDevice,
        groupId: String,
        addedBy: String
    ): TrustedDevice {
        return TrustedDevice(
            deviceId = device.deviceInfo.deviceId,
            groupId = groupId,
            publicKey = device.deviceInfo.publicKey,
            deviceName = device.deviceInfo.deviceName,
            deviceType = device.deviceInfo.deviceType,
            addedAt = Clock().currentTimeMillis(),
            addedBy = addedBy,
            permissions = setOf(
                Permission.PERMISSION_FILE_SEND,
                Permission.PERMISSION_FILE_RECEIVE,
                Permission.PERMISSION_CLIPBOARD_SYNC
            )
        )
    }
}

// Test helper classes

data class TestDevice(
    val deviceInfo: DeviceKeypair,
    val trustStore: TrustStore,
    val protocolHandler: TrustProtocolHandler,
    private val sentMessages: MutableList<Pair<String, TrustMessage>>
) {
    suspend fun createTrustGroup(name: String): TrustGroup {
        val groupId = kotlin.uuid.ExperimentalUuidApi.let {
            kotlin.uuid.Uuid.random().toString()
        }
        val groupKey = CryptoProviderImpl().generateAESKey()
        
        val trustGroup = TrustGroup(
            groupId = groupId,
            groupKey = groupKey,
            groupName = name,
            devices = mapOf(
                deviceInfo.deviceId to TrustedDevice(
                    deviceId = deviceInfo.deviceId,
                    groupId = groupId,
                    publicKey = deviceInfo.publicKey,
                    deviceName = deviceInfo.deviceName,
                    deviceType = deviceInfo.deviceType,
                    addedAt = Clock().currentTimeMillis(),
                    addedBy = "self"
                )
            ),
            createdAt = Clock().currentTimeMillis(),
            updatedAt = Clock().currentTimeMillis()
        )
        
        trustStore.saveTrustGroup(trustGroup)
        trustStore.addTrustedDevice(trustGroup.devices[deviceInfo.deviceId]!!)
        
        return trustGroup
    }
    
    suspend fun joinTrustGroup(group: TrustGroup) {
        trustStore.saveTrustGroup(group)
        group.devices.values.forEach { device ->
            trustStore.addTrustedDevice(device)
        }
    }
    
    suspend fun createDiscoveryAnnouncement(): DiscoveryAnnouncement {
        return protocolHandler.createDiscoveryAnnouncement()
    }
    
    suspend fun handleDiscoveryAnnouncement(announcement: DiscoveryAnnouncement, senderAddress: String) {
        protocolHandler.handleDiscoveryAnnouncement(announcement, senderAddress)
    }
    
    suspend fun initiatePairing(deviceId: String): String {
        return protocolHandler.initiatePairing(deviceId)
    }
    
    suspend fun handleECDHInitiation(initiation: ECDHInitiation, senderAddress: String) {
        protocolHandler.handleECDHInitiation(initiation, senderAddress)
    }
    
    suspend fun handleECDHResponse(response: ECDHResponse) {
        protocolHandler.handleECDHResponse(response)
    }
    
    suspend fun handleGroupInvitation(invitation: GroupInvitation) {
        protocolHandler.handleGroupInvitation(invitation)
    }
    
    suspend fun handleMemberUpdate(update: MemberUpdate) {
        protocolHandler.handleMemberUpdate(update)
    }
    
    suspend fun handleClipboardSync(sync: ClipboardSync) {
        protocolHandler.handleClipboardSync(sync)
    }
    
    suspend fun handleKeyRotation(rotation: KeyRotation) {
        (protocolHandler as TestTrustProtocolHandler).handleKeyRotation(rotation)
    }
    
    suspend fun updateClipboard(content: String) {
        protocolHandler.broadcastClipboardUpdate(content)
    }
    
    suspend fun addDeviceToGroup(device: TrustedDevice) {
        trustStore.addTrustedDevice(device)
        protocolHandler.broadcastMemberUpdate(UpdateAction.UPDATE_ACTION_ADD, device)
    }
    
    suspend fun removeDeviceFromGroup(deviceId: String) {
        val device = trustStore.getTrustedDevice(deviceId) ?: return
        trustStore.removeTrustedDevice(deviceId)
        protocolHandler.broadcastMemberUpdate(UpdateAction.UPDATE_ACTION_REMOVE, device)
    }
    
    suspend fun rotateGroupKey(newKey: ByteArray) {
        val group = trustStore.getTrustGroup() ?: return
        trustStore.updateGroupKey(group.groupId, newKey)
        
        // Send key rotation message
        val rotation = KeyRotation.newBuilder()
            .setGroupId(group.groupId)
            .setEncryptedNewKey(ByteString.copyFrom(newKey)) // Simplified for test
            .setTimestamp(Clock().currentTimeMillis())
            .build()
        
        val message = TrustMessage.newBuilder()
            .setType(TrustMessageType.MESSAGE_TYPE_KEY_ROTATION)
            .setPayload(rotation.toByteString())
            .build()
        
        for ((deviceId, _) in group.devices) {
            if (deviceId != deviceInfo.deviceId) {
                sentMessages.add(deviceId to message)
            }
        }
    }
    
    suspend fun isDeviceTrusted(deviceId: String): Boolean {
        return trustStore.isDeviceTrusted(deviceId)
    }
    
    suspend fun isInTrustGroup(): Boolean {
        return trustStore.getTrustGroup() != null
    }
    
    fun getSentMessages(): List<Pair<String, TrustMessage>> = sentMessages.toList()
    
    fun getTrustEvents(): Flow<TrustEvent> = protocolHandler.getTrustEvents()
}

// Extended protocol handler for testing
class TestTrustProtocolHandler(
    trustStore: TrustStore,
    cryptoProvider: CryptoProvider,
    deviceInfo: suspend () -> DeviceKeypair,
    sendMessage: suspend (deviceId: String, message: TrustMessage) -> Unit
) : TrustProtocolHandlerImpl(trustStore, cryptoProvider, deviceInfo, sendMessage) {
    
    suspend fun handleKeyRotation(rotation: KeyRotation) {
        // Simplified key rotation handling for tests
        val groupId = rotation.groupId
        val newKey = rotation.encryptedNewKey.toByteArray() // Simplified - normally would decrypt
        
        trustStore.updateGroupKey(groupId, newKey)
        
        // Log security event
        trustStore.logSecurityEvent(
            SecurityEvent(
                eventType = SecurityEventType.KEY_ROTATION,
                timestamp = Clock().currentTimeMillis(),
                details = mapOf("groupId" to groupId)
            )
        )
    }
}

// In-memory trust store for testing
class InMemoryTrustStore : TrustStore {
    private var deviceKeypair: DeviceKeypair? = null
    private var trustGroup: TrustGroup? = null
    private val trustedDevices = mutableMapOf<String, TrustedDevice>()
    private val securityEvents = mutableListOf<SecurityEvent>()
    private val pairingSessions = mutableMapOf<String, PairingSession>()
    private val clipboardEntries = mutableListOf<ClipboardEntry>()
    private var clipboardIdCounter = 1L
    
    override suspend fun getDeviceKeypair(): DeviceKeypair? = deviceKeypair
    
    override suspend fun saveDeviceKeypair(keypair: DeviceKeypair) {
        deviceKeypair = keypair
    }
    
    override suspend fun updateDeviceName(name: String) {
        deviceKeypair?.let {
            deviceKeypair = it.copy(deviceName = name)
        }
    }
    
    override suspend fun getTrustGroup(): TrustGroup? = trustGroup
    
    override suspend fun saveTrustGroup(group: TrustGroup) {
        trustGroup = group
    }
    
    override suspend fun updateGroupKey(groupId: String, newKey: ByteArray) {
        if (trustGroup?.groupId == groupId) {
            trustGroup = trustGroup?.copy(
                groupKey = newKey,
                updatedAt = Clock().currentTimeMillis()
            )
        }
    }
    
    override suspend fun enableCloudSync(groupId: String) {
        if (trustGroup?.groupId == groupId) {
            trustGroup = trustGroup?.copy(cloudSyncEnabled = true)
        }
    }
    
    override suspend fun getTrustedDevices(): List<TrustedDevice> = trustedDevices.values.toList()
    
    override suspend fun getTrustedDevice(deviceId: String): TrustedDevice? = trustedDevices[deviceId]
    
    override suspend fun addTrustedDevice(device: TrustedDevice) {
        trustedDevices[device.deviceId] = device
    }
    
    override suspend fun removeTrustedDevice(deviceId: String) {
        trustedDevices.remove(deviceId)
    }
    
    override suspend fun updateDeviceLastSeen(deviceId: String) {
        trustedDevices[deviceId]?.let {
            trustedDevices[deviceId] = it.copy(lastSeen = Clock().currentTimeMillis())
        }
    }
    
    override suspend fun isDeviceTrusted(deviceId: String): Boolean = trustedDevices.containsKey(deviceId)
    
    override suspend fun getDeviceTrustLevel(deviceId: String): TrustLevel? = trustedDevices[deviceId]?.trustLevel
    
    override fun observeTrustedDevices(): Flow<List<TrustedDevice>> = flow {
        emit(trustedDevices.values.toList())
    }
    
    override suspend fun logSecurityEvent(event: SecurityEvent) {
        securityEvents.add(event.copy(id = securityEvents.size.toLong() + 1))
    }
    
    override suspend fun getRecentSecurityEvents(limit: Int): List<SecurityEvent> =
        securityEvents.sortedByDescending { it.timestamp }.take(limit)
    
    override suspend fun getSecurityEventsByDevice(deviceId: String, limit: Int): List<SecurityEvent> =
        securityEvents.filter { it.deviceId == deviceId }.sortedByDescending { it.timestamp }.take(limit)
    
    override suspend fun createPairingSession(session: PairingSession) {
        pairingSessions[session.sessionId] = session
    }
    
    override suspend fun getPairingSession(sessionId: String): PairingSession? = pairingSessions[sessionId]
    
    override suspend fun updatePairingSessionStatus(sessionId: String, status: PairingSessionStatus) {
        pairingSessions[sessionId]?.let {
            pairingSessions[sessionId] = it.copy(status = status)
        }
    }
    
    override suspend fun cleanExpiredPairingSessions() {
        val now = Clock().currentTimeMillis()
        pairingSessions.entries.removeIf { it.value.expiresAt < now }
    }
    
    override suspend fun saveClipboardEntry(entry: ClipboardEntry) {
        clipboardEntries.add(entry.copy(id = clipboardIdCounter++))
    }
    
    override suspend fun getLatestClipboardEntry(): ClipboardEntry? = clipboardEntries.maxByOrNull { it.timestamp }
    
    override suspend fun getUnsyncedClipboardEntries(): List<ClipboardEntry> = clipboardEntries.filter { !it.synced }
    
    override suspend fun markClipboardEntrySynced(id: Long) {
        val index = clipboardEntries.indexOfFirst { it.id == id }
        if (index >= 0) {
            clipboardEntries[index] = clipboardEntries[index].copy(synced = true)
        }
    }
    
    override suspend fun isClipboardContentNew(contentHash: String): Boolean =
        clipboardEntries.none { it.contentHash == contentHash }
    
    override suspend fun cleanupExpiredDevices() {
        val now = Clock().currentTimeMillis()
        trustedDevices.entries.removeIf { 
            it.value.expiresAt != null && it.value.expiresAt < now
        }
    }
    
    override suspend fun cleanupOldSecurityEvents(daysToKeep: Int) {
        val cutoff = Clock().currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
        securityEvents.removeIf { it.timestamp < cutoff }
    }
    
    override suspend fun cleanupOldClipboardEntries(maxEntries: Int) {
        if (clipboardEntries.size > maxEntries) {
            val toRemove = clipboardEntries.size - maxEntries
            repeat(toRemove) {
                clipboardEntries.removeAt(0)
            }
        }
    }
}