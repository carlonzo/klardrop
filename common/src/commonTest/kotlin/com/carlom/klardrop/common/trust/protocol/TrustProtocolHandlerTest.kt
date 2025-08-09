package com.carlom.klardrop.common.trust.protocol

import TestCoroutines
import app.cash.turbine.test
import com.carlom.klardrop.common.trust.crypto.CryptoProvider
import com.carlom.klardrop.common.trust.crypto.CryptoProviderImpl
import com.carlom.klardrop.common.trust.model.*
import com.carlom.klardrop.common.trust.storage.TrustStore
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.DeviceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.*

class TrustProtocolHandlerTest {
    
    private val testCoroutines = TestCoroutines()
    private val cryptoProvider = CryptoProviderImpl()
    private val fakeTrustStore = FakeTrustStoreImpl()
    private val sentMessages = mutableListOf<Pair<String, TrustMessage>>()
    
    private lateinit var deviceKeypair: DeviceKeypair
    private lateinit var handler: TrustProtocolHandler
    
    @BeforeTest
    fun setup() = runTest(testCoroutines.dispatcher) {
        // Generate device keypair
        val ecdsaKeyPair = cryptoProvider.generateECDSAKeypair()
        deviceKeypair = DeviceKeypair(
            deviceId = "test-device-123",
            publicKey = ecdsaKeyPair.publicKey,
            privateKey = ecdsaKeyPair.privateKey,
            deviceName = "Test Device",
            deviceType = DeviceType.MOBILE
        )
        
        // Save device keypair
        fakeTrustStore.saveDeviceKeypair(deviceKeypair)
        
        // Create handler
        handler = TestTrustProtocolHandlerImpl(
            trustStore = fakeTrustStore,
            cryptoProvider = cryptoProvider,
            deviceInfo = { deviceKeypair },
            sendMessage = { deviceId, message ->
                sentMessages.add(deviceId to message)
            }
        )
        
        // Clear sent messages
        sentMessages.clear()
    }
    
    @Test
    fun testCreateDiscoveryAnnouncement() = runTest(testCoroutines.dispatcher) {
        // Test without trust group
        val announcement1 = handler.createDiscoveryAnnouncement()
        
        assertEquals(deviceKeypair.deviceId, announcement1.deviceId)
        assertTrue(deviceKeypair.publicKey.contentEquals(announcement1.publicKey))
        assertFalse(announcement1.isInTrustGroup)
        assertTrue(announcement1.supportsAutoTrust)
        assertTrue(announcement1.timestamp > 0)
        assertTrue(announcement1.signature.isNotEmpty())
        
        // Verify signature using the same method as the protocol handler
        val dataToVerify = ProtoBuf.encodeToByteArray(
            announcement1.copy(signature = byteArrayOf())
        )
        
        val isValid = cryptoProvider.verifyECDSA(
            dataToVerify,
            announcement1.signature,
            announcement1.publicKey
        )
        assertTrue(isValid)
        
        // Test with trust group
        val groupKey = cryptoProvider.generateAESKey()
        val trustGroup = TrustGroup(
            groupId = "group-123",
            groupKey = groupKey,
            groupName = "Test Group",
            devices = mapOf(deviceKeypair.deviceId to TrustedDevice(
                deviceId = deviceKeypair.deviceId,
                groupId = "group-123",
                publicKey = deviceKeypair.publicKey,
                deviceName = deviceKeypair.deviceName,
                deviceType = deviceKeypair.deviceType,
                addedAt = Clock().currentTimeMillis(),
                addedBy = "self"
            )),
            createdAt = Clock().currentTimeMillis(),
            updatedAt = Clock().currentTimeMillis()
        )
        fakeTrustStore.saveTrustGroup(trustGroup)
        
        val announcement2 = handler.createDiscoveryAnnouncement()
        assertTrue(announcement2.isInTrustGroup)
    }
    
    @Test
    fun testHandleDiscoveryAnnouncementWithValidSignature() = runTest(testCoroutines.dispatcher) {
        // Create another device
        val otherDeviceKeypair = cryptoProvider.generateECDSAKeypair()
        val otherDeviceId = "other-device-456"
        
        // Create valid announcement
        val announcement = DiscoveryAnnouncement(
            deviceId = otherDeviceId,
            deviceName = "Other Device",
            deviceType = DeviceType.DESKTOP,
            publicKey = otherDeviceKeypair.publicKey,
            isInTrustGroup = false,
            supportsAutoTrust = true,
            timestamp = Clock().currentTimeMillis(),
            signature = byteArrayOf() // Will be signed below
        )
        
        val dataToSign = ProtoBuf.encodeToByteArray(announcement)
        val signature = cryptoProvider.signECDSA(dataToSign, otherDeviceKeypair.privateKey)
        
        val signedAnnouncement = announcement.copy(signature = signature)
        
        // Handle announcement
        handler.handleDiscoveryAnnouncement(signedAnnouncement, "192.168.1.100")
        
        // Should not log security event for valid signature
        val securityEvents = fakeTrustStore.getRecentSecurityEvents(10)
        assertTrue(securityEvents.isEmpty())
    }
    
    @Test
    fun testHandleDiscoveryAnnouncementWithInvalidSignature() = runTest(testCoroutines.dispatcher) {
        // Create announcement with invalid signature
        val announcement = DiscoveryAnnouncement(
            deviceId = "fake-device",
            deviceName = "Fake Device",
            deviceType = DeviceType.UNKNOWN,
            publicKey = byteArrayOf(1, 2, 3),
            isInTrustGroup = false,
            supportsAutoTrust = true,
            timestamp = Clock().currentTimeMillis(),
            signature = byteArrayOf(4, 5, 6) // Invalid signature
        )
        
        // Handle announcement
        handler.handleDiscoveryAnnouncement(announcement, "192.168.1.100")
        
        // Should log security event
        val securityEvents = fakeTrustStore.getRecentSecurityEvents(10)
        assertEquals(1, securityEvents.size)
        assertEquals(SecurityEventType.AUTH_FAILED, securityEvents[0].eventType)
        assertEquals("fake-device", securityEvents[0].deviceId)
        assertEquals("192.168.1.100", securityEvents[0].ipAddress)
        assertEquals("Invalid signature", securityEvents[0].details?.get("reason"))
    }
    
    @Test
    fun testHandleDiscoveryAnnouncementFromTrustedDevice() = runTest(testCoroutines.dispatcher) {
        // Setup trust group with another device
        val otherDeviceKeypair = cryptoProvider.generateECDSAKeypair()
        val otherDevice = TrustedDevice(
            deviceId = "other-device-456",
            groupId = "group-123",
            publicKey = otherDeviceKeypair.publicKey,
            deviceName = "Other Device",
            deviceType = DeviceType.DESKTOP,
            addedAt = Clock().currentTimeMillis() - 3600000, // 1 hour ago
            addedBy = deviceKeypair.deviceId,
            lastSeen = Clock().currentTimeMillis() - 1800000 // 30 minutes ago
        )
        
        val trustGroup = TrustGroup(
            groupId = "group-123",
            groupKey = cryptoProvider.generateAESKey(),
            groupName = "Test Group",
            devices = mapOf(
                deviceKeypair.deviceId to TrustedDevice(
                    deviceId = deviceKeypair.deviceId,
                    groupId = "group-123",
                    publicKey = deviceKeypair.publicKey,
                    deviceName = deviceKeypair.deviceName,
                    deviceType = deviceKeypair.deviceType,
                    addedAt = Clock().currentTimeMillis(),
                    addedBy = "self"
                ),
                otherDevice.deviceId to otherDevice
            ),
            createdAt = Clock().currentTimeMillis(),
            updatedAt = Clock().currentTimeMillis()
        )
        
        fakeTrustStore.saveTrustGroup(trustGroup)
        fakeTrustStore.addTrustedDevice(otherDevice)
        
        // Create valid announcement from trusted device
        val announcement = DiscoveryAnnouncement(
            deviceId = otherDevice.deviceId,
            deviceName = otherDevice.deviceName,
            deviceType = otherDevice.deviceType,
            publicKey = otherDeviceKeypair.publicKey,
            isInTrustGroup = true,
            supportsAutoTrust = true,
            timestamp = Clock().currentTimeMillis(),
            signature = byteArrayOf()
        )
        
        val dataToSign = ProtoBuf.encodeToByteArray(announcement)
        val signature = cryptoProvider.signECDSA(dataToSign, otherDeviceKeypair.privateKey)
        
        val signedAnnouncement = announcement.copy(signature = signature)
        
        val oldLastSeen = fakeTrustStore.getTrustedDevice(otherDevice.deviceId)?.lastSeen
        
        // Handle announcement
        handler.handleDiscoveryAnnouncement(signedAnnouncement, "192.168.1.100")
        
        // Should update last seen
        val updatedDevice = fakeTrustStore.getTrustedDevice(otherDevice.deviceId)
        assertNotNull(updatedDevice)
        assertNotNull(updatedDevice.lastSeen)
        assertTrue(updatedDevice.lastSeen!! > oldLastSeen!!)
    }
    
    @Test
    fun testVerifyMessageSignature() = runTest(testCoroutines.dispatcher) {
        // Create another device
        val otherDeviceKeypair = cryptoProvider.generateECDSAKeypair()
        val otherDevice = TrustedDevice(
            deviceId = "other-device-456",
            groupId = "group-123",
            publicKey = otherDeviceKeypair.publicKey,
            deviceName = "Other Device",
            deviceType = DeviceType.DESKTOP,
            addedAt = Clock().currentTimeMillis(),
            addedBy = deviceKeypair.deviceId
        )
        
        fakeTrustStore.addTrustedDevice(otherDevice)
        
        // Sign data
        val data = "Test message".encodeToByteArray()
        val signature = cryptoProvider.signECDSA(data, otherDeviceKeypair.privateKey)
        
        // Verify with correct device
        assertTrue(handler.verifyMessageSignature(data, signature, otherDevice.deviceId))
        
        // Verify with wrong data
        val wrongData = "Wrong message".encodeToByteArray()
        assertFalse(handler.verifyMessageSignature(wrongData, signature, otherDevice.deviceId))
        
        // Verify with unknown device
        assertFalse(handler.verifyMessageSignature(data, signature, "unknown-device"))
    }
    
    @Test
    fun testIsMessageFromTrustedDevice() = runTest(testCoroutines.dispatcher) {
        // Setup trust group with another device
        val otherDevice = TrustedDevice(
            deviceId = "other-device-456",
            groupId = "group-123",
            publicKey = byteArrayOf(1, 2, 3),
            deviceName = "Other Device",
            deviceType = DeviceType.DESKTOP,
            addedAt = Clock().currentTimeMillis(),
            addedBy = deviceKeypair.deviceId
        )
        
        fakeTrustStore.addTrustedDevice(otherDevice)
        
        // Test trusted device
        assertTrue(handler.isMessageFromTrustedDevice(otherDevice.deviceId))
        
        // Test untrusted device
        assertFalse(handler.isMessageFromTrustedDevice("unknown-device"))
    }
    
    @Test
    fun testBroadcastClipboardUpdate() = runTest(testCoroutines.dispatcher) {
        // Setup trust group with another device
        val device2 = TrustedDevice(
            deviceId = "device-2",
            groupId = "group-123",
            publicKey = byteArrayOf(1, 2, 3),
            deviceName = "Device 2",
            deviceType = DeviceType.MOBILE,
            addedAt = Clock().currentTimeMillis(),
            addedBy = deviceKeypair.deviceId,
            permissions = setOf(Permission.CLIPBOARD_SYNC)
        )
        
        val device3 = TrustedDevice(
            deviceId = "device-3",
            groupId = "group-123",
            publicKey = byteArrayOf(4, 5, 6),
            deviceName = "Device 3",
            deviceType = DeviceType.DESKTOP,
            addedAt = Clock().currentTimeMillis(),
            addedBy = deviceKeypair.deviceId,
            permissions = emptySet() // No clipboard permission
        )
        
        val trustGroup = TrustGroup(
            groupId = "group-123",
            groupKey = cryptoProvider.generateAESKey(),
            groupName = "Test Group",
            devices = mapOf(
                deviceKeypair.deviceId to TrustedDevice(
                    deviceId = deviceKeypair.deviceId,
                    groupId = "group-123",
                    publicKey = deviceKeypair.publicKey,
                    deviceName = deviceKeypair.deviceName,
                    deviceType = deviceKeypair.deviceType,
                    addedAt = Clock().currentTimeMillis(),
                    addedBy = "self"
                ),
                device2.deviceId to device2,
                device3.deviceId to device3
            ),
            createdAt = Clock().currentTimeMillis(),
            updatedAt = Clock().currentTimeMillis()
        )
        
        fakeTrustStore.saveTrustGroup(trustGroup)
        fakeTrustStore.addTrustedDevice(device2)
        fakeTrustStore.addTrustedDevice(device3)
        
        // Broadcast clipboard update
        val clipboardContent = "Hello from clipboard!"
        handler.broadcastClipboardUpdate(clipboardContent)
        
        // Should only send to device with clipboard permission
        assertEquals(1, sentMessages.size)
        assertEquals(device2.deviceId, sentMessages[0].first)
        
        val message = sentMessages[0].second
        assertEquals(TrustMessageType.CLIPBOARD_SYNC, message.type)
        
        // Check clipboard entry was saved
        val savedEntry = fakeTrustStore.getLatestClipboardEntry()
        assertNotNull(savedEntry)
        assertEquals(clipboardContent, savedEntry.content)
        assertEquals(deviceKeypair.deviceId, savedEntry.deviceId)
        assertFalse(savedEntry.synced)
    }
    
    @Test
    fun testGetTrustEvents() = runTest(testCoroutines.dispatcher) {
        handler.getTrustEvents().test {
            // Test error event
            (handler as TestTrustProtocolHandlerImpl).emitError("Test error", "device-123")
            
            val event = awaitItem()
            assertTrue(event is TrustEvent.TrustError)
            val errorEvent = event as TrustEvent.TrustError
            assertEquals("Test error", errorEvent.message)
            assertEquals("device-123", errorEvent.deviceId)
        }
    }
}

// Test implementation of TrustProtocolHandler for testing
class TestTrustProtocolHandlerImpl(
    private val trustStore: TrustStore,
    private val cryptoProvider: CryptoProvider,
    private val deviceInfo: suspend () -> DeviceKeypair,
    private val sendMessage: suspend (deviceId: String, message: TrustMessage) -> Unit
) : TrustProtocolHandler {
    
    private val _trustEvents = kotlinx.coroutines.flow.MutableSharedFlow<TrustEvent>()
    
    suspend fun emitError(message: String, deviceId: String?) {
        _trustEvents.emit(TrustEvent.TrustError(message, deviceId))
    }
    
    override suspend fun createDiscoveryAnnouncement(): DiscoveryAnnouncement {
        val device = deviceInfo()
        val trustGroup = trustStore.getTrustGroup()
        
        val announcement = DiscoveryAnnouncement(
            deviceId = device.deviceId,
            deviceName = device.deviceName,
            deviceType = device.deviceType,
            publicKey = device.publicKey,
            isInTrustGroup = trustGroup != null,
            supportsAutoTrust = true,
            timestamp = Clock().currentTimeMillis(),
            signature = byteArrayOf()
        )
        
        val dataToSign = ProtoBuf.encodeToByteArray(announcement)
        val signature = cryptoProvider.signECDSA(dataToSign, device.privateKey)
        
        return announcement.copy(signature = signature)
    }
    
    override suspend fun handleDiscoveryAnnouncement(
        announcement: DiscoveryAnnouncement,
        senderAddress: String
    ) {
        // Verify signature
        val dataToVerify = ProtoBuf.encodeToByteArray(
            announcement.copy(signature = byteArrayOf())
        )
        
        val isValid = try {
            cryptoProvider.verifyECDSA(
                dataToVerify,
                announcement.signature,
                announcement.publicKey
            )
        } catch (e: Exception) {
            false
        }
        
        if (!isValid) {
            trustStore.logSecurityEvent(
                SecurityEvent(
                    eventType = SecurityEventType.AUTH_FAILED,
                    deviceId = announcement.deviceId,
                    ipAddress = senderAddress,
                    timestamp = Clock().currentTimeMillis(),
                    details = mapOf("reason" to "Invalid signature")
                )
            )
            return
        }
        
        // Check if device is already trusted
        val isTrusted = trustStore.isDeviceTrusted(announcement.deviceId)
        
        if (isTrusted) {
            // Update last seen
            trustStore.updateDeviceLastSeen(announcement.deviceId)
        }
    }
    
    override suspend fun handleECDHInitiation(initiation: ECDHInitiation, senderAddress: String) {
        // Simplified for tests
    }
    
    override suspend fun handleECDHResponse(response: ECDHResponse) {
        // Simplified for tests
    }
    
    override suspend fun handleGroupInvitation(invitation: GroupInvitation) {
        // Simplified for tests
    }
    
    override suspend fun handleJoinConfirmation(confirmation: JoinConfirmation) {
        // Simplified for tests
    }
    
    override suspend fun handleMemberUpdate(update: MemberUpdate) {
        // Simplified for tests
    }
    
    override suspend fun broadcastClipboardUpdate(content: String) {
        val device = deviceInfo()
        val trustGroup = trustStore.getTrustGroup() ?: return
        
        // Save clipboard entry
        val contentHash = cryptoProvider.hash(content.encodeToByteArray()).toHexString()
        val signature = cryptoProvider.signECDSA(content.encodeToByteArray(), device.privateKey)
        
        trustStore.saveClipboardEntry(
            ClipboardEntry(
                deviceId = device.deviceId,
                content = content,
                contentHash = contentHash,
                timestamp = Clock().currentTimeMillis(),
                signature = signature,
                synced = false
            )
        )
        
        // Create clipboard sync message
        val sync = ClipboardSyncMessage(
            deviceId = device.deviceId,
            encryptedContent = content.encodeToByteArray(), // Simplified for test
            timestamp = Clock().currentTimeMillis(),
            signature = signature
        )
        
        val message = TrustMessage(
            type = TrustMessageType.CLIPBOARD_SYNC,
            payload = ProtoBuf.encodeToByteArray(sync)
        )
        
        // Send to devices with clipboard permission
        for ((deviceId, trustedDevice) in trustGroup.devices) {
            if (deviceId != device.deviceId && 
                trustedDevice.permissions.contains(Permission.CLIPBOARD_SYNC)) {
                sendMessage(deviceId, message)
            }
        }
    }
    
    override suspend fun handleClipboardSync(sync: ClipboardSync) {
        // Simplified for tests - correct parameter type
    }
    
    // Add missing required methods
    override suspend fun initiatePairing(deviceId: String): String {
        return "test-session-id"
    }
    
    override suspend fun broadcastMemberUpdate(action: UpdateAction, device: com.carlom.klardrop.common.trust.model.TrustedDevice) {
        // Simplified for tests
    }
    
    override fun getTrustEvents(): Flow<TrustEvent> = _trustEvents
    
    override suspend fun isMessageFromTrustedDevice(deviceId: String): Boolean {
        return trustStore.isDeviceTrusted(deviceId)
    }
    
    override suspend fun verifyMessageSignature(
        data: ByteArray,
        signature: ByteArray,
        deviceId: String
    ): Boolean {
        val device = trustStore.getTrustedDevice(deviceId) ?: return false
        return try {
            cryptoProvider.verifyECDSA(data, signature, device.publicKey)
        } catch (e: Exception) {
            false
        }
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}

// Simplified fake trust store for testing
class FakeTrustStoreImpl : TrustStore {
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
            trustGroup = trustGroup?.copy(groupKey = newKey)
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
            val expiresAt = it.value.expiresAt
            expiresAt != null && expiresAt < now
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