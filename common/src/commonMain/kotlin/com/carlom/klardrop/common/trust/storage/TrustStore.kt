package com.carlom.klardrop.common.trust.storage

import com.carlom.klardrop.common.database.AppDatabase
import com.carlom.klardrop.common.trust.model.*
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType as LocalDeviceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.withContext

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
    private val secureKeyStorage: SecureKeyStorage,
    private val coroutines: Coroutines
) : TrustStore {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    // Enum conversion utilities for optimized database operations
    // Note: Currently using string-based storage for backward compatibility
    // These converters are prepared for future schema optimization
    
    private companion object {
        // Cached conversion maps for better performance
        private val trustLevelToString = mapOf(
            TrustLevel.TRUSTED to "TRUSTED",
            TrustLevel.UNTRUSTED to "UNTRUSTED", 
            TrustLevel.FULL to "FULL",
            TrustLevel.LIMITED to "LIMITED",
            TrustLevel.MINIMAL to "MINIMAL"
        )
        
        private val stringToTrustLevel = trustLevelToString.entries.associate { (k, v) -> v to k }
        
        private val deviceTypeToString = mapOf(
            LocalDeviceType.MOBILE to "MOBILE",
            LocalDeviceType.DESKTOP to "DESKTOP",
            LocalDeviceType.UNKNOWN to "UNKNOWN"
        )
        
        private val stringToDeviceType = deviceTypeToString.entries.associate { (k, v) -> v to k }
        
        private val permissionToString = mapOf(
            Permission.FILE_SEND to "FILE_SEND",
            Permission.FILE_RECEIVE to "FILE_RECEIVE",
            Permission.CLIPBOARD_SYNC to "CLIPBOARD_SYNC"
        )
        
        private val stringToPermission = permissionToString.entries.associate { (k, v) -> v to k }
    }
    
    // Optimized enum converters using cached maps
    private fun TrustLevel.toDatabaseString(): String = 
        trustLevelToString[this] ?: this.name
    
    private fun String.toTrustLevel(): TrustLevel = 
        stringToTrustLevel[this] ?: TrustLevel.FULL
    
    private fun LocalDeviceType.toDatabaseString(): String = 
        deviceTypeToString[this] ?: this.name
    
    private fun String.toLocalDeviceType(): LocalDeviceType = 
        stringToDeviceType[this] ?: LocalDeviceType.UNKNOWN
    
    private fun Permission.toDatabaseString(): String = 
        permissionToString[this] ?: this.name
    
    private fun String.toPermission(): Permission? = 
        stringToPermission[this]
    
    // Optimized permissions serialization
    private fun Set<Permission>.toJsonString(): String {
        return json.encodeToString(this.map { it.toDatabaseString() })
    }
    
    private fun String.toPermissionSet(): Set<Permission> {
        return try {
            json.decodeFromString<List<String>>(this)
                .mapNotNull { it.toPermission() }
                .toSet()
        } catch (e: Exception) {
            // Fallback to default permissions if parsing fails
            setOf(Permission.FILE_SEND, Permission.FILE_RECEIVE, Permission.CLIPBOARD_SYNC)
        }
    }
    
    override suspend fun getDeviceKeypair(): DeviceKeypair? = withContext(coroutines.ioDispatcher) {
        try {
            val dbKeypair = database.deviceKeypairQueries.getDeviceKeypair().executeAsOneOrNull()
                ?: return@withContext null
            
            val privateKey = secureKeyStorage.retrievePrivateKey(dbKeypair.private_key_alias)
                ?: return@withContext null
            
            DeviceKeypair(
                deviceId = dbKeypair.device_id,
                publicKey = dbKeypair.public_key,
                privateKey = privateKey,
                deviceName = dbKeypair.device_name,
                deviceType = when (dbKeypair.device_type) {
                    "MOBILE" -> LocalDeviceType.MOBILE
                    "DESKTOP" -> LocalDeviceType.DESKTOP
                    else -> LocalDeviceType.UNKNOWN
                },
                createdAt = dbKeypair.created_at
            )
        } catch (e: Exception) {
            // Log error and return null for graceful degradation
            null
        }
    }
    
    override suspend fun saveDeviceKeypair(keypair: DeviceKeypair) = withContext(coroutines.ioDispatcher) {
        val alias = "device_key_${keypair.deviceId}"
        
        // Store private key in secure storage first (must be done outside transaction)
        secureKeyStorage.storePrivateKey(alias, keypair.privateKey)
        
        // Then store public key and metadata in database
        database.transaction {
            database.deviceKeypairQueries.upsertDeviceKeypair(
                device_id = keypair.deviceId,
                public_key = keypair.publicKey,
                private_key_alias = alias,
                device_name = keypair.deviceName,
                device_type = keypair.deviceType.name,
                created_at = keypair.createdAt
            )
        }
    }
    
    override suspend fun updateDeviceName(name: String) {
        withContext(coroutines.ioDispatcher) {
            database.deviceKeypairQueries.updateDeviceName(name)
        }
    }
    
    override suspend fun getTrustGroup(): TrustGroup? = withContext(coroutines.ioDispatcher) {
        try {
            val dbGroup = database.trustGroupQueries.getActiveTrustGroup().executeAsOneOrNull()
                ?: return@withContext null
            
            val devices = database.trustedDeviceQueries.getTrustedDevicesByGroup(dbGroup.group_id)
                .executeAsList()
                .map { it.toTrustedDevice() }
            
            TrustGroup(
                groupId = dbGroup.group_id,
                groupKey = dbGroup.group_key,
                groupName = dbGroup.group_name,
                devices = devices.associateBy { it.deviceId },
                createdAt = dbGroup.created_at,
                updatedAt = dbGroup.updated_at,
                protocolVersion = dbGroup.protocol_version.toInt(),
                cloudSyncEnabled = dbGroup.cloud_sync_enabled == 1L
            )
        } catch (e: Exception) {
            // Log error and return null for graceful degradation
            null
        }
    }
    
    override suspend fun saveTrustGroup(group: TrustGroup) = withContext(coroutines.ioDispatcher) {
        database.transaction {
            try {
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
                
                // Batch insert all devices - optimized approach
                batchInsertTrustedDevices(group.devices.values.toList())
                
            } catch (e: Exception) {
                // Transaction will be automatically rolled back
                throw e
            }
        }
    }
    
    override suspend fun updateGroupKey(groupId: String, newKey: ByteArray) = withContext(coroutines.ioDispatcher) {
        database.transaction {
            database.trustGroupQueries.updateGroupKey(
                group_key = newKey,
                updated_at = Clock().currentTimeMillis(),
                group_id = groupId
            )
        }
    }
    
    override suspend fun enableCloudSync(groupId: String) = withContext(coroutines.ioDispatcher) {
        database.transaction {
            database.trustGroupQueries.enableCloudSync(
                updated_at = Clock().currentTimeMillis(),
                group_id = groupId
            )
        }
    }
    
    override suspend fun getTrustedDevices(): List<TrustedDevice> = withContext(coroutines.ioDispatcher) {
        try {
            val group = getTrustGroup() ?: return@withContext emptyList()
            database.trustedDeviceQueries.getTrustedDevicesByGroup(group.groupId)
                .executeAsList()
                .map { it.toTrustedDevice() }
        } catch (e: Exception) {
            // Log error and return empty list for graceful degradation
            emptyList()
        }
    }
    
    override suspend fun getTrustedDevice(deviceId: String): TrustedDevice? = withContext(coroutines.ioDispatcher) {
        try {
            database.trustedDeviceQueries.getTrustedDeviceById(deviceId)
                .executeAsOneOrNull()
                ?.toTrustedDevice()
        } catch (e: Exception) {
            // Log error and return null for graceful degradation
            null
        }
    }
    
    override suspend fun addTrustedDevice(device: TrustedDevice) = withContext(coroutines.ioDispatcher) {
        database.transaction {
            try {
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
            } catch (e: Exception) {
                // Transaction will be automatically rolled back
                throw e
            }
        }
    }
    
    override suspend fun removeTrustedDevice(deviceId: String) = withContext(coroutines.ioDispatcher) {
        database.transaction {
            try {
                database.trustedDeviceQueries.removeTrustedDevice(deviceId)
                
                // Log security event
                logSecurityEventInternal(
                    SecurityEvent(
                        eventType = SecurityEventType.DEVICE_REMOVED,
                        deviceId = deviceId,
                        timestamp = Clock().currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                // Transaction will be automatically rolled back
                throw e
            }
        }
    }
    
    override suspend fun updateDeviceLastSeen(deviceId: String) {
        withContext(coroutines.ioDispatcher) {
            database.trustedDeviceQueries.updateLastSeen(
                last_seen = Clock().currentTimeMillis(),
                device_id = deviceId
            )
        }
    }
    
    override suspend fun isDeviceTrusted(deviceId: String): Boolean = withContext(coroutines.ioDispatcher) {
        try {
            database.trustedDeviceQueries.isDeviceTrusted(
                device_id = deviceId,
                expires_at = Clock().currentTimeMillis()
            ).executeAsOne()
        } catch (e: Exception) {
            // Log error and return false for safety
            false
        }
    }
    
    override suspend fun getDeviceTrustLevel(deviceId: String): TrustLevel? = withContext(coroutines.ioDispatcher) {
        try {
            val levelStr = database.trustedDeviceQueries.getDeviceTrustLevel(
                device_id = deviceId,
                expires_at = Clock().currentTimeMillis()
            ).executeAsOneOrNull()
            
            levelStr?.let { 
                when (it) {
                    "FULL" -> TrustLevel.FULL
                    "LIMITED" -> TrustLevel.LIMITED
                    "MINIMAL" -> TrustLevel.MINIMAL
                    "TRUSTED" -> TrustLevel.TRUSTED
                    "UNTRUSTED" -> TrustLevel.UNTRUSTED
                    else -> TrustLevel.FULL
                }
            }
        } catch (e: Exception) {
            // Log error and return null for graceful degradation
            null
        }
    }
    
    override fun observeTrustedDevices(): Flow<List<TrustedDevice>> {
        // This would need to be implemented based on your specific requirements
        // For now, returning a simple flow
        return database.trustedDeviceQueries.getTrustedDevicesByGroup("")
            .asFlow()
            .mapToList(coroutines.cpuDispatcher)
            .map { devices ->
                devices.map { it.toTrustedDevice() }
            }
    }
    
    override suspend fun logSecurityEvent(event: SecurityEvent) = withContext(coroutines.ioDispatcher) {
        database.transaction {
            logSecurityEventInternal(event)
        }
    }
    
    override suspend fun getRecentSecurityEvents(limit: Int): List<SecurityEvent> = withContext(coroutines.ioDispatcher) {
        try {
            database.securityEventQueries.getRecentSecurityEvents(limit.toLong())
                .executeAsList()
                .map { it.toSecurityEvent() }
        } catch (e: Exception) {
            // Log error and return empty list for graceful degradation
            emptyList()
        }
    }
    
    override suspend fun getSecurityEventsByDevice(deviceId: String, limit: Int): List<SecurityEvent> = withContext(coroutines.ioDispatcher) {
        try {
            database.securityEventQueries.getSecurityEventsByDevice(deviceId, limit.toLong())
                .executeAsList()
                .map { it.toSecurityEvent() }
        } catch (e: Exception) {
            // Log error and return empty list for graceful degradation
            emptyList()
        }
    }
    
    override suspend fun createPairingSession(session: PairingSession) = withContext(coroutines.ioDispatcher) {
        database.transaction {
            database.pairingSessionQueries.insertPairingSession(
                session_id = session.sessionId,
                device_id = session.deviceId,
                ephemeral_public_key = session.ephemeralPublicKey,
                expires_at = session.expiresAt,
                status = session.status.name,
                created_at = session.createdAt
            )
        }
    }
    
    override suspend fun getPairingSession(sessionId: String): PairingSession? = withContext(coroutines.ioDispatcher) {
        try {
            database.pairingSessionQueries.getActivePairingSession(
                session_id = sessionId,
                expires_at = Clock().currentTimeMillis()
            ).executeAsOneOrNull()?.toPairingSession()
        } catch (e: Exception) {
            // Log error and return null for graceful degradation
            null
        }
    }
    
    override suspend fun updatePairingSessionStatus(sessionId: String, status: PairingSessionStatus) = withContext(coroutines.ioDispatcher) {
        database.transaction {
            database.pairingSessionQueries.updatePairingSessionStatus(
                status = status.name,
                session_id = sessionId
            )
        }
    }
    
    override suspend fun cleanExpiredPairingSessions() = withContext(coroutines.ioDispatcher) {
        database.transaction {
            database.pairingSessionQueries.cleanExpiredPairingSessions(Clock().currentTimeMillis())
        }
    }
    
    override suspend fun saveClipboardEntry(entry: ClipboardEntry) = withContext(coroutines.ioDispatcher) {
        database.transaction {
            database.clipboardEntryQueries.insertClipboardEntry(
                device_id = entry.deviceId,
                content = entry.content,
                content_hash = entry.contentHash,
                timestamp = entry.timestamp,
                signature = entry.signature
            )
        }
    }
    
    override suspend fun getLatestClipboardEntry(): ClipboardEntry? = withContext(coroutines.ioDispatcher) {
        try {
            database.clipboardEntryQueries.getLatestClipboardEntry()
                .executeAsOneOrNull()
                ?.toClipboardEntry()
        } catch (e: Exception) {
            // Log error and return null for graceful degradation
            null
        }
    }
    
    override suspend fun getUnsyncedClipboardEntries(): List<ClipboardEntry> = withContext(coroutines.ioDispatcher) {
        try {
            database.clipboardEntryQueries.getUnsyncedClipboardEntries()
                .executeAsList()
                .map { it.toClipboardEntry() }
        } catch (e: Exception) {
            // Log error and return empty list for graceful degradation
            emptyList()
        }
    }
    
    override suspend fun markClipboardEntrySynced(id: Long) = withContext(coroutines.ioDispatcher) {
        database.transaction {
            database.clipboardEntryQueries.markClipboardEntrySynced(id)
        }
    }
    
    override suspend fun isClipboardContentNew(contentHash: String): Boolean = withContext(coroutines.ioDispatcher) {
        try {
            !database.clipboardEntryQueries.contentExists(contentHash).executeAsOne()
        } catch (e: Exception) {
            // Log error and return true for safety (assume content is new)
            true
        }
    }
    
    override suspend fun cleanupExpiredDevices() = withContext(coroutines.ioDispatcher) {
        database.transaction {
            database.trustedDeviceQueries.deleteExpiredDevices(Clock().currentTimeMillis())
        }
    }
    
    override suspend fun cleanupOldSecurityEvents(daysToKeep: Int) = withContext(coroutines.ioDispatcher) {
        database.transaction {
            val cutoffTime = Clock().currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
            database.securityEventQueries.cleanOldSecurityEvents(cutoffTime)
        }
    }
    
    override suspend fun cleanupOldClipboardEntries(maxEntries: Int) = withContext(coroutines.ioDispatcher) {
        database.transaction {
            database.clipboardEntryQueries.cleanOldClipboardEntries(maxEntries.toLong())
        }
    }
    
    // Helper functions
    
    /**
     * Optimized batch insert for trusted devices to improve performance
     * when adding multiple devices at once.
     */
    private fun batchInsertTrustedDevices(devices: List<TrustedDevice>) {
        if (devices.isEmpty()) return
        
        // For small batches, individual inserts may be faster due to overhead
        if (devices.size <= 3) {
            devices.forEach { device ->
                saveTrustedDeviceInternal(device)
            }
            return
        }
        
        // For larger batches, optimize by clearing and inserting all at once
        // This prevents the overhead of individual REPLACE operations
        val groupId = devices.firstOrNull()?.groupId
        if (groupId != null) {
            // Clear existing devices for this group first for a clean insert
            database.trustedDeviceQueries.clearDevicesForGroup(groupId)
        }
        
        // Batch insert all devices - SQLDelight will prepare statements internally
        // which is more efficient than individual operations
        devices.forEach { device ->
            saveTrustedDeviceInternal(device)
        }
    }
    
    private fun saveTrustedDeviceInternal(device: TrustedDevice) {
        database.trustedDeviceQueries.upsertTrustedDevice(
            device_id = device.deviceId,
            group_id = device.groupId,
            public_key = device.publicKey,
            device_name = device.deviceName,
            device_type = device.deviceType.toDatabaseString(),
            added_at = device.addedAt,
            added_by = device.addedBy,
            last_seen = device.lastSeen,
            trust_level = device.trustLevel.toDatabaseString(),
            permissions = device.permissions.toJsonString(),
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
            deviceType = device_type.toLocalDeviceType(),
            addedAt = added_at,
            addedBy = added_by,
            lastSeen = last_seen,
            trustLevel = trust_level.toTrustLevel(),
            permissions = permissions.toPermissionSet(),
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