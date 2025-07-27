package com.carlom.klardrop.common.trust

import TestCoroutines
import app.cash.turbine.test
import com.carlom.klardrop.common.trust.crypto.CryptoProvider
import com.carlom.klardrop.common.trust.crypto.CryptoProviderImpl
import com.carlom.klardrop.common.trust.db.DatabaseDriverFactory
import com.carlom.klardrop.common.trust.db.TrustDatabase
import com.carlom.klardrop.common.trust.model.*
import com.carlom.klardrop.common.trust.storage.SecureKeyStorage
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.protos.trust.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TrustManagerTest {
    
    private val testCoroutines = TestCoroutines()
    private val fakeDatabaseDriverFactory = FakeDatabaseDriverFactory()
    private val fakeSecureKeyStorage = FakeSecureKeyStorage()
    private val sentMessages = mutableListOf<Pair<String, TrustMessage>>()
    
    private lateinit var trustManager: TrustManager
    
    @BeforeTest
    fun setup() {
        sentMessages.clear()
    }
    
    private fun createTrustManager(
        deviceName: String = "Test Device",
        deviceType: DeviceType = DeviceType.DEVICE_TYPE_MOBILE
    ): TrustManager {
        return TrustManager(
            databaseDriverFactory = fakeDatabaseDriverFactory,
            secureKeyStorage = fakeSecureKeyStorage,
            deviceName = deviceName,
            deviceType = deviceType,
            scope = testCoroutines.newScope(),
            sendTrustMessage = { deviceId, message ->
                sentMessages.add(deviceId to message)
            }
        )
    }
    
    @Test
    fun testInitialization() = runTest(testCoroutines.dispatcher) {
        trustManager = createTrustManager()
        
        // Wait for initialization
        trustManager.isInitialized.test {
            assertFalse(awaitItem()) // Initial state
            assertTrue(awaitItem()) // After initialization
        }
        
        // Check device keypair was created
        val keypair = trustManager.currentDeviceKeypair.first()
        assertNotNull(keypair)
        assertEquals("Test Device", keypair.deviceName)
        assertEquals(DeviceType.DEVICE_TYPE_MOBILE, keypair.deviceType)
        assertTrue(keypair.publicKey.isNotEmpty())
        assertTrue(keypair.privateKey.isNotEmpty())
        
        // Initially no trust group
        assertNull(trustManager.currentTrustGroup.first())
        assertTrue(trustManager.trustedDevices.first().isEmpty())
    }
    
    @Test
    fun testPersistentDeviceKeypair() = runTest(testCoroutines.dispatcher) {
        // Create first manager
        val manager1 = createTrustManager()
        
        // Wait for initialization
        assertTrue(manager1.isInitialized.first { it })
        
        val keypair1 = manager1.currentDeviceKeypair.first()
        assertNotNull(keypair1)
        
        // Create second manager with same database
        val manager2 = createTrustManager()
        
        // Wait for initialization
        assertTrue(manager2.isInitialized.first { it })
        
        val keypair2 = manager2.currentDeviceKeypair.first()
        assertNotNull(keypair2)
        
        // Should load the same keypair
        assertEquals(keypair1.deviceId, keypair2.deviceId)
        assertTrue(keypair1.publicKey.contentEquals(keypair2.publicKey))
        assertTrue(keypair1.privateKey.contentEquals(keypair2.privateKey))
    }
    
    @Test
    fun testCreateTrustGroup() = runTest(testCoroutines.dispatcher) {
        trustManager = createTrustManager()
        
        // Wait for initialization
        assertTrue(trustManager.isInitialized.first { it })
        
        // Create trust group
        val groupName = "My Devices"
        val trustGroup = trustManager.createTrustGroup(groupName)
        
        assertNotNull(trustGroup)
        assertEquals(groupName, trustGroup.groupName)
        assertTrue(trustGroup.groupKey.isNotEmpty())
        assertEquals(1, trustGroup.devices.size)
        
        // Check device is in the group
        val deviceKeypair = trustManager.currentDeviceKeypair.first()!!
        assertTrue(trustGroup.devices.containsKey(deviceKeypair.deviceId))
        
        val trustedDevice = trustGroup.devices[deviceKeypair.deviceId]!!
        assertEquals(deviceKeypair.deviceId, trustedDevice.deviceId)
        assertEquals(deviceKeypair.deviceName, trustedDevice.deviceName)
        assertEquals(deviceKeypair.deviceType, trustedDevice.deviceType)
        assertEquals("self", trustedDevice.addedBy)
        
        // Check trust group is stored
        assertEquals(trustGroup.groupId, trustManager.currentTrustGroup.first()?.groupId)
        
        // Check trusted devices list
        val trustedDevices = trustManager.trustedDevices.first()
        assertEquals(1, trustedDevices.size)
        assertEquals(deviceKeypair.deviceId, trustedDevices[0].deviceId)
    }
    
    @Test
    fun testUpdateDeviceName() = runTest(testCoroutines.dispatcher) {
        trustManager = createTrustManager()
        
        // Wait for initialization
        assertTrue(trustManager.isInitialized.first { it })
        
        val originalKeypair = trustManager.currentDeviceKeypair.first()!!
        assertEquals("Test Device", originalKeypair.deviceName)
        
        // Update device name
        val newName = "My Phone"
        trustManager.updateDeviceName(newName)
        
        // Verify name was updated
        val updatedKeypair = trustManager.currentDeviceKeypair.first()
        assertEquals(newName, updatedKeypair?.deviceName)
        
        // Other fields should remain the same
        assertEquals(originalKeypair.deviceId, updatedKeypair?.deviceId)
        assertTrue(originalKeypair.publicKey.contentEquals(updatedKeypair?.publicKey ?: ByteArray(0)))
    }
    
    @Test
    fun testCreateDiscoveryAnnouncement() = runTest(testCoroutines.dispatcher) {
        trustManager = createTrustManager()
        
        // Wait for initialization
        assertTrue(trustManager.isInitialized.first { it })
        
        // Create announcement without trust group
        val announcement1 = trustManager.createDiscoveryAnnouncement()
        assertNotNull(announcement1)
        assertFalse(announcement1.isInTrustGroup)
        
        // Create trust group
        trustManager.createTrustGroup("Test Group")
        
        // Create announcement with trust group
        val announcement2 = trustManager.createDiscoveryAnnouncement()
        assertNotNull(announcement2)
        assertTrue(announcement2.isInTrustGroup)
        
        // Verify announcement fields
        val deviceKeypair = trustManager.currentDeviceKeypair.first()!!
        assertEquals(deviceKeypair.deviceId, announcement2.deviceId)
        assertTrue(deviceKeypair.publicKey.contentEquals(announcement2.publicKey.toByteArray()))
        assertTrue(announcement2.supportsAutoTrust)
        assertEquals(1, announcement2.protocolVersion)
    }
    
    @Test
    fun testInitiatePairing() = runTest(testCoroutines.dispatcher) {
        trustManager = createTrustManager()
        
        // Wait for initialization
        assertTrue(trustManager.isInitialized.first { it })
        
        // Create trust group first
        trustManager.createTrustGroup("Test Group")
        
        val targetDeviceId = "other-device-123"
        
        // Initiate pairing
        val sessionId = trustManager.initiatePairing(targetDeviceId)
        assertNotNull(sessionId)
        assertTrue(sessionId.isNotEmpty())
        
        // Check message was sent
        assertEquals(1, sentMessages.size)
        val (recipientId, message) = sentMessages[0]
        assertEquals(targetDeviceId, recipientId)
        assertEquals(TrustMessageType.MESSAGE_TYPE_ECDH_INITIATION, message.type)
    }
    
    @Test
    fun testHandleTrustMessage() = runTest(testCoroutines.dispatcher) {
        trustManager = createTrustManager()
        
        // Wait for initialization
        assertTrue(trustManager.isInitialized.first { it })
        
        // Create a discovery announcement from another device
        val cryptoProvider = CryptoProviderImpl()
        val otherDeviceKeypair = cryptoProvider.generateECDSAKeypair()
        val otherDeviceId = "other-device-456"
        
        val announcement = DiscoveryAnnouncement.newBuilder()
            .setDeviceId(otherDeviceId)
            .setPublicKey(com.google.protobuf.ByteString.copyFrom(otherDeviceKeypair.publicKey))
            .setIsInTrustGroup(false)
            .setSupportsAutoTrust(true)
            .setTimestamp(Clock().currentTimeMillis())
            .setProtocolVersion(1)
            .build()
        
        val dataToSign = announcement.toByteArray()
        val signature = cryptoProvider.signECDSA(dataToSign, otherDeviceKeypair.privateKey)
        
        val signedAnnouncement = announcement.toBuilder()
            .setSignature(com.google.protobuf.ByteString.copyFrom(signature))
            .build()
        
        val trustMessage = TrustMessage.newBuilder()
            .setType(TrustMessageType.MESSAGE_TYPE_DISCOVERY_ANNOUNCEMENT)
            .setPayload(signedAnnouncement.toByteString())
            .build()
        
        // Handle the message
        trustManager.handleTrustMessage(trustMessage, "192.168.1.100")
        
        // Give time for async processing
        delay(100)
        
        // In a real implementation, this might trigger UI notifications
        // For now, we just verify no exceptions were thrown
    }
    
    @Test
    fun testIsDeviceTrusted() = runTest(testCoroutines.dispatcher) {
        trustManager = createTrustManager()
        
        // Wait for initialization
        assertTrue(trustManager.isInitialized.first { it })
        
        // Create trust group
        trustManager.createTrustGroup("Test Group")
        
        // Add a trusted device
        val trustedDevice = TrustedDevice(
            deviceId = "trusted-device-123",
            groupId = trustManager.currentTrustGroup.first()!!.groupId,
            publicKey = byteArrayOf(1, 2, 3),
            deviceName = "Trusted Device",
            deviceType = DeviceType.DEVICE_TYPE_DESKTOP,
            addedAt = Clock().currentTimeMillis(),
            addedBy = trustManager.currentDeviceKeypair.first()!!.deviceId
        )
        
        // Manually add to trust store for testing
        val trustStore = (trustManager as TrustManagerWithTestAccess).trustStore
        trustStore.addTrustedDevice(trustedDevice)
        
        // Check trust status
        assertTrue(trustManager.isDeviceTrusted(trustedDevice.deviceId))
        assertFalse(trustManager.isDeviceTrusted("unknown-device"))
    }
    
    @Test
    fun testGetDeviceTrustStatus() = runTest(testCoroutines.dispatcher) {
        trustManager = createTrustManager()
        
        // Wait for initialization
        assertTrue(trustManager.isInitialized.first { it })
        
        // Create trust group
        trustManager.createTrustGroup("Test Group")
        
        // Add trusted devices with different trust levels
        val trustStore = (trustManager as TrustManagerWithTestAccess).trustStore
        
        val fullTrustDevice = TrustedDevice(
            deviceId = "full-trust-device",
            groupId = trustManager.currentTrustGroup.first()!!.groupId,
            publicKey = byteArrayOf(1, 2, 3),
            deviceName = "Full Trust Device",
            deviceType = DeviceType.DEVICE_TYPE_DESKTOP,
            addedAt = Clock().currentTimeMillis(),
            addedBy = trustManager.currentDeviceKeypair.first()!!.deviceId,
            trustLevel = TrustLevel.TRUST_LEVEL_FULL
        )
        
        val restrictedDevice = TrustedDevice(
            deviceId = "restricted-device",
            groupId = trustManager.currentTrustGroup.first()!!.groupId,
            publicKey = byteArrayOf(4, 5, 6),
            deviceName = "Restricted Device",
            deviceType = DeviceType.DEVICE_TYPE_MOBILE,
            addedAt = Clock().currentTimeMillis(),
            addedBy = trustManager.currentDeviceKeypair.first()!!.deviceId,
            trustLevel = TrustLevel.TRUST_LEVEL_RESTRICTED
        )
        
        val expiredDevice = TrustedDevice(
            deviceId = "expired-device",
            groupId = trustManager.currentTrustGroup.first()!!.groupId,
            publicKey = byteArrayOf(7, 8, 9),
            deviceName = "Expired Device",
            deviceType = DeviceType.DEVICE_TYPE_TABLET,
            addedAt = Clock().currentTimeMillis() - 86400000,
            addedBy = trustManager.currentDeviceKeypair.first()!!.deviceId,
            expiresAt = Clock().currentTimeMillis() - 3600000 // Expired 1 hour ago
        )
        
        trustStore.addTrustedDevice(fullTrustDevice)
        trustStore.addTrustedDevice(restrictedDevice)
        trustStore.addTrustedDevice(expiredDevice)
        
        // Check trust status
        assertEquals(TrustStatus.TRUSTED, trustManager.getDeviceTrustStatus(fullTrustDevice.deviceId))
        assertEquals(TrustStatus.TRUSTED, trustManager.getDeviceTrustStatus(restrictedDevice.deviceId))
        assertEquals(TrustStatus.TRUST_EXPIRED, trustManager.getDeviceTrustStatus(expiredDevice.deviceId))
        assertEquals(TrustStatus.UNTRUSTED, trustManager.getDeviceTrustStatus("unknown-device"))
    }
    
    @Test
    fun testSyncClipboard() = runTest(testCoroutines.dispatcher) {
        trustManager = createTrustManager()
        
        // Wait for initialization
        assertTrue(trustManager.isInitialized.first { it })
        
        // Create trust group
        trustManager.createTrustGroup("Test Group")
        
        // Add a trusted device with clipboard permission
        val trustedDevice = TrustedDevice(
            deviceId = "trusted-device-123",
            groupId = trustManager.currentTrustGroup.first()!!.groupId,
            publicKey = byteArrayOf(1, 2, 3),
            deviceName = "Trusted Device",
            deviceType = DeviceType.DEVICE_TYPE_DESKTOP,
            addedAt = Clock().currentTimeMillis(),
            addedBy = trustManager.currentDeviceKeypair.first()!!.deviceId,
            permissions = setOf(Permission.PERMISSION_CLIPBOARD_SYNC)
        )
        
        val trustStore = (trustManager as TrustManagerWithTestAccess).trustStore
        trustStore.addTrustedDevice(trustedDevice)
        
        // Update trust group to include the device
        val group = trustManager.currentTrustGroup.first()!!
        val updatedGroup = group.copy(
            devices = group.devices + (trustedDevice.deviceId to trustedDevice)
        )
        trustStore.saveTrustGroup(updatedGroup)
        
        // Sync clipboard
        val clipboardContent = "Hello from clipboard!"
        trustManager.syncClipboard(clipboardContent)
        
        // Check message was sent
        assertEquals(1, sentMessages.size)
        val (recipientId, message) = sentMessages[0]
        assertEquals(trustedDevice.deviceId, recipientId)
        assertEquals(TrustMessageType.MESSAGE_TYPE_CLIPBOARD_SYNC, message.type)
    }
    
    @Test
    fun testObserveTrustedDevices() = runTest(testCoroutines.dispatcher) {
        trustManager = createTrustManager()
        
        // Wait for initialization
        assertTrue(trustManager.isInitialized.first { it })
        
        // Create trust group
        trustManager.createTrustGroup("Test Group")
        
        trustManager.trustedDevices.test {
            // Initial state - only self
            val initial = awaitItem()
            assertEquals(1, initial.size)
            
            // Add a trusted device
            val trustedDevice = TrustedDevice(
                deviceId = "new-device-123",
                groupId = trustManager.currentTrustGroup.first()!!.groupId,
                publicKey = byteArrayOf(1, 2, 3),
                deviceName = "New Device",
                deviceType = DeviceType.DEVICE_TYPE_DESKTOP,
                addedAt = Clock().currentTimeMillis(),
                addedBy = trustManager.currentDeviceKeypair.first()!!.deviceId
            )
            
            val trustStore = (trustManager as TrustManagerWithTestAccess).trustStore
            trustStore.addTrustedDevice(trustedDevice)
            
            // Manually trigger update for test
            (trustManager as TrustManagerWithTestAccess).refreshTrustedDevices()
            
            val updated = awaitItem()
            assertEquals(2, updated.size)
            assertTrue(updated.any { it.deviceId == trustedDevice.deviceId })
        }
    }
}

// Test implementations

class FakeDatabaseDriverFactory : DatabaseDriverFactory {
    private val fakeDatabase = FakeInMemoryDatabase()
    
    override fun createDriver(): app.cash.sqldelight.db.SqlDriver {
        return FakeSqlDriver(fakeDatabase)
    }
}


class FakeSecureKeyStorage : SecureKeyStorage {
    private val storage = mutableMapOf<String, ByteArray>()
    
    override suspend fun storePrivateKey(alias: String, key: ByteArray) {
        storage[alias] = key.copyOf()
    }
    
    override suspend fun retrievePrivateKey(alias: String): ByteArray? {
        return storage[alias]?.copyOf()
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

// Simple fake SQL driver and database for testing
class FakeSqlDriver(private val database: FakeInMemoryDatabase) : app.cash.sqldelight.db.SqlDriver {
    override fun close() {}
    
    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?
    ): app.cash.sqldelight.db.QueryResult<Long> {
        return app.cash.sqldelight.db.QueryResult.Value(0)
    }
    
    override fun executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (app.cash.sqldelight.db.SqlCursor) -> app.cash.sqldelight.db.QueryResult<Any?>,
        parameters: Int,
        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?
    ): app.cash.sqldelight.db.QueryResult<Any?> {
        return mapper(FakeSqlCursor())
    }
    
    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
        mapper: (app.cash.sqldelight.db.SqlCursor) -> R
    ): R {
        return mapper(FakeSqlCursor())
    }
    
    override fun newTransaction(): app.cash.sqldelight.db.QueryResult<app.cash.sqldelight.Transacter.Transaction> {
        return app.cash.sqldelight.db.QueryResult.Value(FakeTransaction())
    }
    
    override fun currentTransaction(): app.cash.sqldelight.Transacter.Transaction? = null
    
    override fun addListener(vararg queryKeys: String, listener: app.cash.sqldelight.db.Query.Listener) {}
    override fun removeListener(vararg queryKeys: String, listener: app.cash.sqldelight.db.Query.Listener) {}
    override fun notifyListeners(vararg queryKeys: String) {}
}

class FakeSqlCursor : app.cash.sqldelight.db.SqlCursor {
    override fun getBytes(index: Int): ByteArray? = null
    override fun getDouble(index: Int): Double? = null
    override fun getLong(index: Int): Long? = null
    override fun getString(index: Int): String? = null
    override fun getBoolean(index: Int): Boolean? = null
    override fun next(): app.cash.sqldelight.db.QueryResult<Boolean> = app.cash.sqldelight.db.QueryResult.Value(false)
}

class FakeTransaction : app.cash.sqldelight.Transacter.Transaction() {
    override val enclosingTransaction: app.cash.sqldelight.Transacter.Transaction? = null
    
    override fun endTransaction(successful: Boolean): app.cash.sqldelight.db.QueryResult<Unit> {
        return app.cash.sqldelight.db.QueryResult.Unit
    }
}

class FakeInMemoryDatabase {
    // Simple in-memory storage for testing
}

// Extended TrustManager for testing that exposes internal components
class TrustManagerWithTestAccess(
    databaseDriverFactory: DatabaseDriverFactory,
    secureKeyStorage: SecureKeyStorage,
    deviceName: String,
    deviceType: DeviceType,
    scope: kotlinx.coroutines.CoroutineScope,
    sendTrustMessage: suspend (deviceId: String, message: TrustMessage) -> Unit
) : TrustManager(
    databaseDriverFactory,
    secureKeyStorage,
    deviceName,
    deviceType,
    scope,
    sendTrustMessage
) {
    val trustStore: com.carlom.klardrop.common.trust.storage.TrustStore
        get() = super.trustStore
    
    suspend fun refreshTrustedDevices() {
        super._trustedDevices.value = trustStore.getTrustedDevices()
    }
}