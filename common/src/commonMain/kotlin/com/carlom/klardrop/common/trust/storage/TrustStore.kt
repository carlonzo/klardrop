package com.carlom.klardrop.common.trust.storage

import com.carlom.klardrop.common.database.AppDatabase
import com.carlom.klardrop.common.trust.model.*
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.protos.trust.DeviceType
import com.carlom.klardrop.protos.trust.Permission
import com.carlom.klardrop.protos.trust.TrustLevel
import com.carlom.klardrop.protos.trust.UpdateAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers

interface TrustStore {
    // Device keypair operations
    suspend fun getDeviceKeypair(): DeviceKeypair?
    suspend fun saveDeviceKeypair(keypair: DeviceKeypair)
    suspend fun updateDeviceName(name: String)
    
    // Trust group operations
    suspend fun getTrustGroup(): TrustGroup?
    suspend fun saveTrustGroup(group: TrustGroup)
    suspend fun updateGroupKey(groupId: String, newKey: ByteArray)
    suspend fun enableCloudSync(groupId: String)
    
    // Trusted device operations
    suspend fun getTrustedDevices(): List<TrustedDevice>
    suspend fun getTrustedDevice(deviceId: String): TrustedDevice?
    suspend fun addTrustedDevice(device: TrustedDevice)
    suspend fun removeTrustedDevice(deviceId: String)
    suspend fun updateDeviceLastSeen(deviceId: String)
    suspend fun isDeviceTrusted(deviceId: String): Boolean
    suspend fun getDeviceTrustLevel(deviceId: String): TrustLevel?
    fun observeTrustedDevices(): Flow<List<TrustedDevice>>
    
    // Security event logging
    suspend fun logSecurityEvent(event: SecurityEvent)
    suspend fun getRecentSecurityEvents(limit: Int = 100): List<SecurityEvent>
    suspend fun getSecurityEventsByDevice(deviceId: String, limit: Int = 50): List<SecurityEvent>
    
    // Pairing session management
    suspend fun createPairingSession(session: PairingSession)
    suspend fun getPairingSession(sessionId: String): PairingSession?
    suspend fun updatePairingSessionStatus(sessionId: String, status: PairingSessionStatus)
    suspend fun cleanExpiredPairingSessions()
    
    // Clipboard sync
    suspend fun saveClipboardEntry(entry: ClipboardEntry)
    suspend fun getLatestClipboardEntry(): ClipboardEntry?
    suspend fun getUnsyncedClipboardEntries(): List<ClipboardEntry>
    suspend fun markClipboardEntrySynced(id: Long)
    suspend fun isClipboardContentNew(contentHash: String): Boolean
    
    // Cleanup operations
    suspend fun cleanupExpiredDevices()
    suspend fun cleanupOldSecurityEvents(daysToKeep: Int = 30)
    suspend fun cleanupOldClipboardEntries(maxEntries: Int = 100)
}

class TrustStoreImpl(
    private val database: AppDatabase,
    private val secureKeyStorage: SecureKeyStorage
) : TrustStore {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    override suspend fun getDeviceKeypair(): DeviceKeypair? {
        val dbKeypair = database.deviceKeypairQueries.getDeviceKeypair().executeAsOneOrNull()
            ?: return null
        
        val privateKey = secureKeyStorage.retrievePrivateKey(dbKeypair.private_key_alias)
            ?: return null
        
        return DeviceKeypair(
            deviceId = dbKeypair.device_id,
            publicKey = dbKeypair.public_key,
            privateKey = privateKey,
            deviceName = dbKeypair.device_name,
            deviceType = DeviceType.entries.find { it.value == dbKeypair.device_type.toInt() } ?: DeviceType.DEVICE_TYPE_UNKNOWN,
            createdAt = dbKeypair.created_at
        )
    }
    
    override suspend fun saveDeviceKeypair(keypair: DeviceKeypair) {
        val alias = "device_key_${keypair.deviceId}"
        
        // Store private key in secure storage
        secureKeyStorage.storePrivateKey(alias, keypair.privateKey)
        
        // Store public key and metadata in database
        database.deviceKeypairQueries.upsertDeviceKeypair(
            device_id = keypair.deviceId,
            public_key = keypair.publicKey,
            private_key_alias = alias,
            device_name = keypair.deviceName,
            device_type = keypair.deviceType.value.toString(),
            created_at = keypair.createdAt
        )
    }
    
    override suspend fun updateDeviceName(name: String) {
        database.deviceKeypairQueries.updateDeviceName(name)
    }
    
    override suspend fun getTrustGroup(): TrustGroup? {
        val dbGroup = database.trustGroupQueries.getActiveTrustGroup().executeAsOneOrNull()
            ?: return null
        
        val devices = database.trustedDeviceQueries.getTrustedDevicesByGroup(dbGroup.group_id)
            .executeAsList()
            .map { it.toTrustedDevice() }
        
        return TrustGroup(
            groupId = dbGroup.group_id,
            groupKey = dbGroup.group_key,
            groupName = dbGroup.group_name,
            devices = devices.associateBy { it.deviceId },
            createdAt = dbGroup.created_at,
            updatedAt = dbGroup.updated_at,
            protocolVersion = dbGroup.protocol_version.toInt(),
            cloudSyncEnabled = dbGroup.cloud_sync_enabled == 1L
        )
    }
    
    override suspend fun saveTrustGroup(group: TrustGroup) {
        database.transaction {
            // Deactivate all existing groups
            database.trustGroupQueries.deactivateAllGroups()
            
            // Insert new group
            database.trustGroupQueries.upsertTrustGroup(
                group_id = group.groupId,
                group_key = group.groupKey,
                group_name = group.groupName,
                created_at = group.createdAt,
                updated_at = group.updatedAt,
                protocol_version = group.protocolVersion.toLong(),
                cloud_sync_enabled = if (group.cloudSyncEnabled) 1L else 0L,
                is_active = 1L
            )
            
            // Insert all devices
            group.devices.values.forEach { device ->
                saveTrustedDeviceInternal(device)
            }
        }
    }
    
    override suspend fun updateGroupKey(groupId: String, newKey: ByteArray) {
        database.trustGroupQueries.updateGroupKey(
            group_key = newKey,
            updated_at = Clock().currentTimeMillis(),
            group_id = groupId
        )
    }
    
    override suspend fun enableCloudSync(groupId: String) {
        database.trustGroupQueries.enableCloudSync(
            updated_at = Clock().currentTimeMillis(),
            group_id = groupId
        )
    }
    
    override suspend fun getTrustedDevices(): List<TrustedDevice> {
        val group = getTrustGroup() ?: return emptyList()
        return database.trustedDeviceQueries.getTrustedDevicesByGroup(group.groupId)
            .executeAsList()
            .map { it.toTrustedDevice() }
    }
    
    override suspend fun getTrustedDevice(deviceId: String): TrustedDevice? {
        return database.trustedDeviceQueries.getTrustedDeviceById(deviceId)
            .executeAsOneOrNull()
            ?.toTrustedDevice()
    }
    
    override suspend fun addTrustedDevice(device: TrustedDevice) {
        database.transaction {
            saveTrustedDeviceInternal(device)
            
            // Log security event
            logSecurityEventInternal(
                SecurityEvent(
                    eventType = SecurityEventType.DEVICE_ADDED,
                    deviceId = device.deviceId,
                    timestamp = Clock().currentTimeMillis(),
                    details = mapOf("added_by" to device.addedBy)
                )
            )
        }
    }
    
    override suspend fun removeTrustedDevice(deviceId: String) {
        database.transaction {
            database.trustedDeviceQueries.removeTrustedDevice(deviceId)
            
            // Log security event
            logSecurityEventInternal(
                SecurityEvent(
                    eventType = SecurityEventType.DEVICE_REMOVED,
                    deviceId = deviceId,
                    timestamp = Clock().currentTimeMillis()
                )
            )
        }
    }
    
    override suspend fun updateDeviceLastSeen(deviceId: String) {
        database.trustedDeviceQueries.updateLastSeen(
            last_seen = Clock().currentTimeMillis(),
            device_id = deviceId
        )
    }
    
    override suspend fun isDeviceTrusted(deviceId: String): Boolean {
        return database.trustedDeviceQueries.isDeviceTrusted(
            device_id = deviceId,
            expires_at = Clock().currentTimeMillis()
        ).executeAsOne()
    }
    
    override suspend fun getDeviceTrustLevel(deviceId: String): TrustLevel? {
        val levelStr = database.trustedDeviceQueries.getDeviceTrustLevel(
            device_id = deviceId,
            expires_at = Clock().currentTimeMillis()
        ).executeAsOneOrNull()
        
        return levelStr?.let { 
            TrustLevel.entries.find { level -> level.value == it.toInt() } ?: TrustLevel.TRUST_LEVEL_UNKNOWN
        }
    }
    
    override fun observeTrustedDevices(): Flow<List<TrustedDevice>> {
        // This would need to be implemented based on your specific requirements
        // For now, returning a simple flow
        return database.trustedDeviceQueries.getTrustedDevicesByGroup("")
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { devices ->
                devices.map { it.toTrustedDevice() }
            }
    }
    
    override suspend fun logSecurityEvent(event: SecurityEvent) {
        logSecurityEventInternal(event)
    }
    
    override suspend fun getRecentSecurityEvents(limit: Int): List<SecurityEvent> {
        return database.securityEventQueries.getRecentSecurityEvents(limit.toLong())
            .executeAsList()
            .map { it.toSecurityEvent() }
    }
    
    override suspend fun getSecurityEventsByDevice(deviceId: String, limit: Int): List<SecurityEvent> {
        return database.securityEventQueries.getSecurityEventsByDevice(deviceId, limit.toLong())
            .executeAsList()
            .map { it.toSecurityEvent() }
    }
    
    override suspend fun createPairingSession(session: PairingSession) {
        database.pairingSessionQueries.insertPairingSession(
            session_id = session.sessionId,
            device_id = session.deviceId,
            ephemeral_public_key = session.ephemeralPublicKey,
            expires_at = session.expiresAt,
            status = session.status.name,
            created_at = session.createdAt
        )
    }
    
    override suspend fun getPairingSession(sessionId: String): PairingSession? {
        return database.pairingSessionQueries.getActivePairingSession(
            session_id = sessionId,
            expires_at = Clock().currentTimeMillis()
        ).executeAsOneOrNull()?.toPairingSession()
    }
    
    override suspend fun updatePairingSessionStatus(sessionId: String, status: PairingSessionStatus) {
        database.pairingSessionQueries.updatePairingSessionStatus(
            status = status.name,
            session_id = sessionId
        )
    }
    
    override suspend fun cleanExpiredPairingSessions() {
        database.pairingSessionQueries.cleanExpiredPairingSessions(Clock().currentTimeMillis())
    }
    
    override suspend fun saveClipboardEntry(entry: ClipboardEntry) {
        database.clipboardEntryQueries.insertClipboardEntry(
            device_id = entry.deviceId,
            content = entry.content,
            content_hash = entry.contentHash,
            timestamp = entry.timestamp,
            signature = entry.signature
        )
    }
    
    override suspend fun getLatestClipboardEntry(): ClipboardEntry? {
        return database.clipboardEntryQueries.getLatestClipboardEntry()
            .executeAsOneOrNull()
            ?.toClipboardEntry()
    }
    
    override suspend fun getUnsyncedClipboardEntries(): List<ClipboardEntry> {
        return database.clipboardEntryQueries.getUnsyncedClipboardEntries()
            .executeAsList()
            .map { it.toClipboardEntry() }
    }
    
    override suspend fun markClipboardEntrySynced(id: Long) {
        database.clipboardEntryQueries.markClipboardEntrySynced(id)
    }
    
    override suspend fun isClipboardContentNew(contentHash: String): Boolean {
        return !database.clipboardEntryQueries.contentExists(contentHash).executeAsOne()
    }
    
    override suspend fun cleanupExpiredDevices() {
        database.trustedDeviceQueries.deleteExpiredDevices(Clock().currentTimeMillis())
    }
    
    override suspend fun cleanupOldSecurityEvents(daysToKeep: Int) {
        val cutoffTime = Clock().currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
        database.securityEventQueries.cleanOldSecurityEvents(cutoffTime)
    }
    
    override suspend fun cleanupOldClipboardEntries(maxEntries: Int) {
        database.clipboardEntryQueries.cleanOldClipboardEntries(maxEntries.toLong())
    }
    
    // Helper functions
    
    private fun saveTrustedDeviceInternal(device: TrustedDevice) {
        database.trustedDeviceQueries.upsertTrustedDevice(
            device_id = device.deviceId,
            group_id = device.groupId,
            public_key = device.publicKey,
            device_name = device.deviceName,
            device_type = device.deviceType.name,
            added_at = device.addedAt,
            added_by = device.addedBy,
            last_seen = device.lastSeen,
            trust_level = device.trustLevel.name,
            permissions = json.encodeToString(device.permissions.map { it.name }),
            expires_at = device.expiresAt,
            is_active = if (device.isActive) 1L else 0L
        )
    }
    
    private fun logSecurityEventInternal(event: SecurityEvent) {
        database.securityEventQueries.insertSecurityEvent(
            event_type = event.eventType.name,
            device_id = event.deviceId,
            ip_address = event.ipAddress,
            timestamp = event.timestamp,
            details = event.details?.let { json.encodeToString(it) }
        )
    }
    
    // Extension functions for data conversion
    
    private fun com.carlom.klardrop.common.database.Trusted_devices.toTrustedDevice(): TrustedDevice {
        return TrustedDevice(
            deviceId = device_id,
            groupId = group_id,
            publicKey = public_key,
            deviceName = device_name,
            deviceType = DeviceType.entries.find { it.value == device_type.toInt() } ?: DeviceType.DEVICE_TYPE_UNKNOWN,
            addedAt = added_at,
            addedBy = added_by,
            lastSeen = last_seen,
            trustLevel = TrustLevel.entries.find { it.value == trust_level.toInt() } ?: TrustLevel.TRUST_LEVEL_UNKNOWN,
            permissions = json.decodeFromString<List<String>>(permissions).map { 
                Permission.entries.find { perm -> perm.name == it } ?: Permission.PERMISSION_UNKNOWN 
            }.toSet(),
            expiresAt = expires_at,
            isActive = is_active == 1L
        )
    }
    
    private fun com.carlom.klardrop.common.database.Security_events.toSecurityEvent(): SecurityEvent {
        return SecurityEvent(
            id = id,
            eventType = SecurityEventType.valueOf(event_type),
            deviceId = device_id,
            ipAddress = ip_address,
            timestamp = timestamp,
            details = details?.let { json.decodeFromString(it) }
        )
    }
    
    private fun com.carlom.klardrop.common.database.Pairing_sessions.toPairingSession(): PairingSession {
        return PairingSession(
            sessionId = session_id,
            deviceId = device_id,
            ephemeralPublicKey = ephemeral_public_key,
            expiresAt = expires_at,
            status = PairingSessionStatus.valueOf(status),
            createdAt = created_at
        )
    }
    
    private fun com.carlom.klardrop.common.database.Clipboard_entries.toClipboardEntry(): ClipboardEntry {
        return ClipboardEntry(
            id = id,
            deviceId = device_id,
            content = content,
            contentHash = content_hash,
            timestamp = timestamp,
            signature = signature,
            synced = synced == 1L
        )
    }
}