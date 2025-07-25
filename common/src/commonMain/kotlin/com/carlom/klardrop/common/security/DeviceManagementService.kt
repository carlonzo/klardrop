package com.carlom.klardrop.common.security

import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.Random
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * Service for managing device registration, pairing, and trust relationships
 */
class DeviceManagementService(
    private val cryptoProvider: CryptoProvider,
    private val deviceRepository: DeviceRepository,
    private val clock: Clock
) {
    private val _trustedDevices = MutableStateFlow<List<RegisteredDevice>>(emptyList())
    val trustedDevices: StateFlow<List<RegisteredDevice>> = _trustedDevices.asStateFlow()
    
    private val _deviceGroups = MutableStateFlow<List<DeviceGroup>>(emptyList())
    val deviceGroups: StateFlow<List<DeviceGroup>> = _deviceGroups.asStateFlow()
    
    private val pairingMutex = Mutex()
    private val activePairingSessions = mutableMapOf<String, PairingSession>()
    
    /**
     * Generates a new device key pair for registration
     */
    suspend fun generateDeviceKeyPair(): DeviceKeyPair {
        val keyPair = cryptoProvider.generateEphemeralKeyPair()
        
        return DeviceKeyPair(
            privateKey = keyPair.privateKey,
            publicKey = keyPair.publicKey,
            algorithm = "ECDSA-P256"
        )
    }
    
    /**
     * Registers a device with the cloud service
     */
    suspend fun registerDevice(
        userToken: String,
        deviceInfo: DeviceInfo,
        publicKey: ByteArray
    ): RegistrationResult {
        // Generate device certificate
        val certificate = generateDeviceCertificate(deviceInfo, publicKey)
        
        // Register with backend
        val registeredDevice = deviceRepository.registerDevice(
            userToken = userToken,
            deviceInfo = deviceInfo,
            publicKey = publicKey,
            certificate = certificate
        )
        
        // Update local state
        _trustedDevices.value = _trustedDevices.value + registeredDevice
        
        return RegistrationResult(
            success = true,
            device = registeredDevice,
            certificate = certificate
        )
    }
    
    /**
     * Initiates device pairing process
     */
    suspend fun initiatePairing(
        targetDevice: DeviceInfo,
        method: PairingMethod
    ): PairingSession {
        val session = when (method) {
            PairingMethod.QR_CODE -> createQRPairingSession(targetDevice)
            PairingMethod.NUMERIC_CODE -> createNumericPairingSession(targetDevice)
            PairingMethod.PROXIMITY -> createProximityPairingSession(targetDevice)
        }
        
        pairingMutex.withLock {
            activePairingSessions[session.sessionId] = session
        }
        
        return session
    }
    
    /**
     * Completes a pairing session
     */
    suspend fun completePairing(
        sessionId: String,
        verificationData: PairingVerificationData
    ): PairingResult {
        val session = pairingMutex.withLock {
            activePairingSessions[sessionId]
                ?: throw SecurityException("Invalid pairing session")
        }
        
        // Verify the pairing data
        if (!verifyPairingData(session, verificationData)) {
            throw SecurityException("Pairing verification failed")
        }
        
        // Exchange device information
        val pairedDevice = RegisteredDevice(
            deviceId = session.targetDevice.deviceId,
            deviceName = session.targetDevice.name,
            deviceType = session.targetDevice.deviceType,
            publicKey = verificationData.publicKey,
            certificateFingerprint = calculateFingerprint(verificationData.certificate),
            addedAt = clock.currentTimeMillis(),
            lastSeen = clock.currentTimeMillis(),
            trustLevel = TrustLevel.VERIFIED
        )
        
        // Add to trusted devices
        addTrustedDevice(pairedDevice)
        
        // Create or join device group
        val deviceGroup = createOrJoinDeviceGroup(pairedDevice)
        
        // Clean up session
        pairingMutex.withLock {
            activePairingSessions.remove(sessionId)
        }
        
        return PairingResult(
            success = true,
            pairedDevice = pairedDevice,
            deviceGroup = deviceGroup
        )
    }
    
    /**
     * Adds a device to a specific group
     */
    suspend fun addDeviceToGroup(
        deviceId: String,
        groupId: String,
        addedBy: String
    ) {
        // Verify permissions
        val addingDevice = findTrustedDevice(addedBy)
            ?: throw SecurityException("Adding device not trusted")
        
        if (addingDevice.trustLevel != TrustLevel.VERIFIED) {
            throw SecurityException("Only verified devices can add others to groups")
        }
        
        // Find the device to add
        val deviceToAdd = findTrustedDevice(deviceId)
            ?: throw SecurityException("Device to add not found")
        
        // Update group
        _deviceGroups.value = _deviceGroups.value.map { group ->
            if (group.groupId == groupId) {
                group.copy(devices = group.devices + deviceToAdd)
            } else {
                group
            }
        }
        
        // Notify group members
        notifyGroupUpdate(groupId, DeviceGroupUpdate.DeviceAdded(deviceToAdd))
    }
    
    /**
     * Removes a device from trust
     */
    suspend fun revokeDevice(deviceId: String) {
        _trustedDevices.value = _trustedDevices.value.map { device ->
            if (device.deviceId == deviceId) {
                device.copy(trustLevel = TrustLevel.REVOKED)
            } else {
                device
            }
        }
        
        // Remove from all groups
        _deviceGroups.value = _deviceGroups.value.map { group ->
            group.copy(devices = group.devices.filter { it.deviceId != deviceId })
        }
        
        log("DeviceManagement", "Revoked device: $deviceId")
    }
    
    /**
     * Updates device last seen timestamp
     */
    suspend fun updateDeviceLastSeen(deviceId: String) {
        _trustedDevices.value = _trustedDevices.value.map { device ->
            if (device.deviceId == deviceId) {
                device.copy(lastSeen = clock.currentTimeMillis())
            } else {
                device
            }
        }
    }
    
    /**
     * Gets the shared encryption key for a device group
     */
    suspend fun getGroupKey(groupId: String): ByteArray? {
        return _deviceGroups.value.find { it.groupId == groupId }?.sharedKey
    }
    
    private suspend fun createQRPairingSession(targetDevice: DeviceInfo): PairingSession {
        val pairingSecret = Random.nextBytes(32)
        val sessionId = generateSessionId()
        
        return PairingSession(
            sessionId = sessionId,
            targetDevice = targetDevice,
            method = PairingMethod.QR_CODE,
            secret = pairingSecret,
            createdAt = clock.currentTimeMillis(),
            expiresAt = clock.currentTimeMillis() + (5 * 60 * 1000), // 5 minutes
            state = PairingState.WAITING_CONFIRMATION,
            qrData = QRPairingData(
                sessionId = sessionId,
                deviceId = targetDevice.deviceId,
                publicKeyHash = cryptoProvider.calculateHash(pairingSecret),
                timestamp = clock.currentTimeMillis()
            )
        )
    }
    
    private suspend fun createNumericPairingSession(targetDevice: DeviceInfo): PairingSession {
        val numericCode = generateNumericCode()
        val sessionId = generateSessionId()
        
        return PairingSession(
            sessionId = sessionId,
            targetDevice = targetDevice,
            method = PairingMethod.NUMERIC_CODE,
            secret = numericCode.toString().encodeToByteArray(),
            createdAt = clock.currentTimeMillis(),
            expiresAt = clock.currentTimeMillis() + (2 * 60 * 1000), // 2 minutes
            state = PairingState.WAITING_CONFIRMATION,
            numericCode = numericCode
        )
    }
    
    private suspend fun createProximityPairingSession(targetDevice: DeviceInfo): PairingSession {
        val sessionId = generateSessionId()
        val proximityToken = Random.nextBytes(16)
        
        return PairingSession(
            sessionId = sessionId,
            targetDevice = targetDevice,
            method = PairingMethod.PROXIMITY,
            secret = proximityToken,
            createdAt = clock.currentTimeMillis(),
            expiresAt = clock.currentTimeMillis() + (60 * 1000), // 1 minute
            state = PairingState.WAITING_CONFIRMATION
        )
    }
    
    private suspend fun verifyPairingData(
        session: PairingSession,
        verificationData: PairingVerificationData
    ): Boolean {
        // Check session expiry
        if (clock.currentTimeMillis() > session.expiresAt) {
            return false
        }
        
        // Verify based on pairing method
        return when (session.method) {
            PairingMethod.QR_CODE -> {
                val expectedHash = cryptoProvider.calculateHash(session.secret)
                verificationData.verificationProof.contentEquals(expectedHash)
            }
            PairingMethod.NUMERIC_CODE -> {
                val providedCode = verificationData.verificationProof.decodeToString()
                providedCode == session.numericCode.toString()
            }
            PairingMethod.PROXIMITY -> {
                // Verify proximity token signature
                cryptoProvider.verifySignature(
                    data = session.secret,
                    signature = verificationData.verificationProof,
                    publicKey = verificationData.publicKey
                )
            }
        }
    }
    
    private suspend fun addTrustedDevice(device: RegisteredDevice) {
        _trustedDevices.value = _trustedDevices.value + device
        deviceRepository.saveTrustedDevice(device)
    }
    
    private suspend fun createOrJoinDeviceGroup(device: RegisteredDevice): DeviceGroup {
        // Check if device belongs to an existing group
        val existingGroup = _deviceGroups.value.find { group ->
            group.devices.any { it.deviceId == device.deviceId }
        }
        
        if (existingGroup != null) {
            return existingGroup
        }
        
        // Create new group
        val newGroup = DeviceGroup(
            groupId = generateGroupId(),
            groupName = "My Devices",
            devices = listOf(device),
            sharedKey = cryptoProvider.generateGroupKey()
        )
        
        _deviceGroups.value = _deviceGroups.value + newGroup
        return newGroup
    }
    
    private fun findTrustedDevice(deviceId: String): RegisteredDevice? {
        return _trustedDevices.value.find { it.deviceId == deviceId }
    }
    
    private suspend fun notifyGroupUpdate(groupId: String, update: DeviceGroupUpdate) {
        // Notify all devices in the group about the update
        // This would publish to MQTT or send push notifications
        log("DeviceManagement", "Group $groupId updated: $update")
    }
    
    private fun generateSessionId(): String = Random.randomAlphanumeric(16)
    private fun generateGroupId(): String = Random.randomAlphanumeric(12)
    private fun generateNumericCode(): Int = Random.nextInt(100000, 999999)
    
    private suspend fun generateDeviceCertificate(
        deviceInfo: DeviceInfo,
        publicKey: ByteArray
    ): ByteArray {
        // In production, this would generate a proper X.509 certificate
        // For now, return a placeholder
        return "CERT:${deviceInfo.deviceId}:${publicKey.take(8).joinToString("")}".encodeToByteArray()
    }
    
    private fun calculateFingerprint(certificate: ByteArray): String {
        // Calculate SHA-256 fingerprint of certificate
        return certificate.take(16).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Device key pair for cryptographic operations
 */
data class DeviceKeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val algorithm: String
)

/**
 * Registered device information
 */
@Serializable
data class RegisteredDevice(
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType,
    val publicKey: ByteArray,
    val certificateFingerprint: String,
    val addedAt: Long,
    val lastSeen: Long,
    val trustLevel: TrustLevel
)

/**
 * Device group for sharing between trusted devices
 */
@Serializable
data class DeviceGroup(
    val groupId: String,
    val groupName: String,
    val devices: List<RegisteredDevice>,
    val sharedKey: ByteArray
)

/**
 * Trust level for devices
 */
@Serializable
enum class TrustLevel {
    VERIFIED,      // Manually verified by user
    TRUSTED,       // Added by trusted device
    PENDING,       // Awaiting verification
    REVOKED        // No longer trusted
}

/**
 * Pairing methods
 */
enum class PairingMethod {
    QR_CODE,
    NUMERIC_CODE,
    PROXIMITY
}

/**
 * Pairing session information
 */
data class PairingSession(
    val sessionId: String,
    val targetDevice: DeviceInfo,
    val method: PairingMethod,
    val secret: ByteArray,
    val createdAt: Long,
    val expiresAt: Long,
    val state: PairingState,
    val qrData: QRPairingData? = null,
    val numericCode: Int? = null
)

/**
 * Pairing state
 */
enum class PairingState {
    INITIATED,
    WAITING_CONFIRMATION,
    CONFIRMED,
    COMPLETED,
    FAILED
}

/**
 * QR code pairing data
 */
@Serializable
data class QRPairingData(
    val sessionId: String,
    val deviceId: String,
    val publicKeyHash: ByteArray,
    val timestamp: Long
)

/**
 * Pairing verification data
 */
data class PairingVerificationData(
    val publicKey: ByteArray,
    val certificate: ByteArray,
    val verificationProof: ByteArray
)

/**
 * Registration result
 */
data class RegistrationResult(
    val success: Boolean,
    val device: RegisteredDevice,
    val certificate: ByteArray
)

/**
 * Pairing result
 */
data class PairingResult(
    val success: Boolean,
    val pairedDevice: RegisteredDevice,
    val deviceGroup: DeviceGroup
)

/**
 * Device group updates
 */
sealed class DeviceGroupUpdate {
    data class DeviceAdded(val device: RegisteredDevice) : DeviceGroupUpdate()
    data class DeviceRemoved(val deviceId: String) : DeviceGroupUpdate()
    data class KeyRotated(val newKey: ByteArray) : DeviceGroupUpdate()
}

/**
 * Device repository interface
 */
interface DeviceRepository {
    suspend fun registerDevice(
        userToken: String,
        deviceInfo: DeviceInfo,
        publicKey: ByteArray,
        certificate: ByteArray
    ): RegisteredDevice
    
    suspend fun saveTrustedDevice(device: RegisteredDevice)
    suspend fun loadTrustedDevices(): List<RegisteredDevice>
    suspend fun saveDeviceGroups(groups: List<DeviceGroup>)
    suspend fun loadDeviceGroups(): List<DeviceGroup>
}

// Extension functions
suspend fun CryptoProvider.generateGroupKey(): ByteArray {
    return deriveKey(
        secret = Random.nextBytes(32),
        info = "klardrop-group-key".encodeToByteArray(),
        length = 32
    )
}

suspend fun CryptoProvider.calculateHash(data: ByteArray): ByteArray {
    // Platform-specific SHA-256 implementation
    return data // Placeholder
}