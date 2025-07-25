# Trusted Device Groups - Implementation Strategy

## Overview
This document provides a detailed implementation strategy for the trusted device groups feature using ECDH (Elliptic Curve Diffie-Hellman) for secure key exchange and ECDSA (Elliptic Curve Digital Signature Algorithm) for payload signing. The implementation follows an offline-first approach with provisions for future cloud sync capabilities.

## Cryptographic Foundation

### Library Integration
- **Primary Library**: [cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin)
  - Provides ECDH support for key exchange
  - ECDSA implementation for signatures
  - Cross-platform Kotlin Multiplatform support
  - Hardware security module support where available

### Cryptographic Primitives
1. **ECDSA (Elliptic Curve Digital Signature Algorithm)**
   - Curve: P-256 (secp256r1) for compatibility
   - Used for: Device identity, message authentication
   - Key size: 256-bit

2. **ECDH (Elliptic Curve Diffie-Hellman)**
   - Curve: P-256 (secp256r1) matching ECDSA
   - Used for: Secure group key exchange
   - Provides forward secrecy

3. **AES-GCM**
   - Key size: 256-bit
   - Used for: Group message encryption
   - Provides authenticated encryption

4. **HKDF (HMAC-based Key Derivation)**
   - Hash: SHA-256
   - Used for: Deriving encryption keys from ECDH shared secrets

## Protocol Design

### Device Identity
```kotlin
data class DeviceIdentity(
    val deviceId: String,                    // UUID v4
    val publicKey: ECDSAPublicKey,          // Device's ECDSA public key
    val deviceName: String,                  // User-friendly name
    val deviceType: DeviceType,              // Android, iOS, Desktop
    val capabilities: Set<Capability>        // Supported features
)

data class DeviceKeypair(
    val identity: DeviceIdentity,
    val privateKey: ECDSAPrivateKey,        // Stored in platform keychain
    val createdAt: Long
)
```

### Trust Group Structure
```kotlin
data class TrustGroup(
    val groupId: String,                     // UUID v4
    val groupKey: AESKey,                    // 256-bit AES key
    val devices: Map<String, TrustedDevice>, // DeviceId -> TrustedDevice
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int,                        // For future protocol upgrades
    val cloudSyncEnabled: Boolean = false    // Future feature flag
)

data class TrustedDevice(
    val identity: DeviceIdentity,
    val addedAt: Long,
    val addedBy: String,                     // DeviceId that added this device
    val trustLevel: TrustLevel = TrustLevel.FULL,
    val permissions: Set<Permission>
)
```

### Automatic Pairing Protocol

#### Phase 1: Discovery & Initial Contact
```kotlin
// Device A (existing group member) discovers Device B
data class DiscoveryAnnouncement(
    val deviceId: String,
    val publicKey: ECDSAPublicKey,
    val isInTrustGroup: Boolean,
    val supportsAutoTrust: Boolean,
    val timestamp: Long,
    val signature: ByteArray  // ECDSA signature of all fields
)

// Device B responds with capability check
data class CapabilityResponse(
    val deviceId: String,
    val publicKey: ECDSAPublicKey,
    val supportedVersion: Int,
    val timestamp: Long,
    val signature: ByteArray
)
```

#### Phase 2: ECDH Key Agreement
```kotlin
// Device A initiates ECDH exchange
data class ECDHInitiation(
    val sessionId: String,                   // Unique session identifier
    val deviceId: String,
    val ephemeralPublicKey: ECDHPublicKey,   // Ephemeral key for this session
    val groupId: String,                     // Encrypted with Device B's public key
    val timestamp: Long,
    val nonce: ByteArray,
    val signature: ByteArray                 // ECDSA signature
)

// Device B completes ECDH exchange
data class ECDHResponse(
    val sessionId: String,
    val deviceId: String,
    val ephemeralPublicKey: ECDHPublicKey,   // B's ephemeral key
    val encryptedDeviceInfo: ByteArray,      // Encrypted with shared secret
    val timestamp: Long,
    val signature: ByteArray
)
```

#### Phase 3: Trust Establishment
```kotlin
// Shared secret derivation
fun deriveSharedSecret(
    privateKey: ECDHPrivateKey,
    peerPublicKey: ECDHPublicKey
): ByteArray {
    val sharedSecret = ECDH.computeSharedSecret(privateKey, peerPublicKey)
    return HKDF.derive(
        secret = sharedSecret,
        salt = "klardrop-trust-v1".toByteArray(),
        info = sessionId.toByteArray(),
        length = 32
    )
}

// Device A sends group information
data class GroupInvitation(
    val sessionId: String,
    val encryptedPayload: EncryptedPayload   // Contains group key and member list
)

data class EncryptedPayload(
    val ciphertext: ByteArray,               // AES-GCM encrypted
    val nonce: ByteArray,
    val tag: ByteArray                       // Authentication tag
)
```

#### Phase 4: Confirmation & Propagation
```kotlin
// Device B confirms joining
data class JoinConfirmation(
    val sessionId: String,
    val deviceId: String,
    val accepted: Boolean,
    val timestamp: Long,
    val signature: ByteArray
)

// Propagate to other group members
data class MemberUpdate(
    val groupId: String,
    val action: UpdateAction,                // ADD, REMOVE, UPDATE
    val device: TrustedDevice,
    val version: Int,                        // Group version number
    val timestamp: Long,
    val signature: ByteArray
)
```

### Automatic Trust Flow
1. **Visual Confirmation Only**: When devices discover each other, show a brief notification
2. **Proximity Validation**: Use signal strength or network latency as additional validation
3. **Time-Limited Decision**: Auto-decline if no user interaction within 30 seconds
4. **One-Tap Approval**: Single tap to approve, swipe to dismiss

## Implementation Phases

### Phase 1: Core Infrastructure (Week 1-2)
```kotlin
// 1. Cryptography setup
interface CryptoProvider {
    fun generateECDSAKeypair(): KeyPair<ECDSAPublicKey, ECDSAPrivateKey>
    fun generateECDHKeypair(): KeyPair<ECDHPublicKey, ECDHPrivateKey>
    fun signECDSA(data: ByteArray, privateKey: ECDSAPrivateKey): ByteArray
    fun verifyECDSA(data: ByteArray, signature: ByteArray, publicKey: ECDSAPublicKey): Boolean
    fun computeECDHSecret(privateKey: ECDHPrivateKey, publicKey: ECDHPublicKey): ByteArray
    fun encryptAESGCM(data: ByteArray, key: AESKey): EncryptedPayload
    fun decryptAESGCM(payload: EncryptedPayload, key: AESKey): ByteArray
}

// 2. Storage layer
interface TrustStore {
    suspend fun getDeviceKeypair(): DeviceKeypair?
    suspend fun saveDeviceKeypair(keypair: DeviceKeypair)
    suspend fun getTrustGroup(): TrustGroup?
    suspend fun saveTrustGroup(group: TrustGroup)
    suspend fun addTrustedDevice(device: TrustedDevice)
    suspend fun removeTrustedDevice(deviceId: String)
}

// 3. Protocol handler
interface TrustProtocolHandler {
    suspend fun handleDiscovery(announcement: DiscoveryAnnouncement)
    suspend fun initiatePairing(deviceId: String)
    suspend fun handleECDHInitiation(initiation: ECDHInitiation)
    suspend fun handleGroupInvitation(invitation: GroupInvitation)
    suspend fun propagateUpdate(update: MemberUpdate)
}
```

### Phase 2: Discovery Integration (Week 3)
```kotlin
// Enhance existing mDNS discovery
class EnhancedDiscoveryNetwork {
    fun announceWithTrust(trustEnabled: Boolean) {
        val announcement = DiscoveryAnnouncement(
            deviceId = getDeviceId(),
            publicKey = getPublicKey(),
            isInTrustGroup = hasGroup(),
            supportsAutoTrust = true,
            timestamp = currentTime(),
            signature = sign(...)
        )
        broadcast(announcement)
    }
    
    fun onDeviceDiscovered(device: DiscoveredDevice) {
        if (device.supportsAutoTrust && isInMyGroup(device)) {
            showTrustedDeviceUI(device)
        } else if (device.supportsAutoTrust) {
            showPairingOpportunity(device)
        }
    }
}
```

### Phase 3: UI Integration (Week 4)
```kotlin
// Minimal UI for automatic pairing
sealed class TrustNotification {
    data class NewDeviceNearby(
        val device: DeviceIdentity,
        val onAccept: () -> Unit,
        val onDecline: () -> Unit,
        val timeout: Duration = 30.seconds
    ) : TrustNotification()
    
    data class DeviceJoined(
        val device: DeviceIdentity
    ) : TrustNotification()
    
    data class TrustedDeviceOnline(
        val device: DeviceIdentity
    ) : TrustNotification()
}

// Trust indicators in device list
data class DeviceListItem(
    val device: DiscoveredDevice,
    val trustStatus: TrustStatus,
    val lastSeen: Long
)

enum class TrustStatus {
    TRUSTED,           // Green checkmark
    UNTRUSTED,        // No indicator
    PENDING_TRUST,    // Loading spinner
    TRUST_EXPIRED     // Clock icon
}
```

### Phase 4: File Transfer Integration (Week 5)
```kotlin
// Modify existing transfer logic
class TrustedTransferHandler {
    suspend fun handleIncomingFile(
        sender: DeviceIdentity,
        file: FileTransferRequest
    ) {
        val trustStatus = trustStore.getDeviceTrustStatus(sender.deviceId)
        
        when (trustStatus) {
            TrustStatus.TRUSTED -> {
                // Auto-accept without confirmation
                acceptTransfer(file)
                showNotification("Receiving file from ${sender.deviceName}")
            }
            else -> {
                // Existing confirmation flow
                showConfirmationDialog(sender, file)
            }
        }
    }
}
```

### Phase 5: Clipboard Sync (Week 6-7)
```kotlin
// Secure clipboard synchronization
class ClipboardSyncManager {
    data class ClipboardEntry(
        val content: String,
        val timestamp: Long,
        val deviceId: String,
        val signature: ByteArray
    )
    
    suspend fun syncClipboard() {
        val currentContent = getClipboardContent()
        val encrypted = encryptForGroup(currentContent)
        
        trustGroup.devices.forEach { device ->
            sendClipboardUpdate(device, encrypted)
        }
    }
    
    suspend fun handleClipboardUpdate(update: ClipboardEntry) {
        if (verifySignature(update) && isNewer(update)) {
            updateLocalClipboard(update.content)
        }
    }
}
```

### Phase 6: Cloud Sync Preparation (Week 8)
```kotlin
// Future cloud sync interfaces
interface CloudSyncProvider {
    suspend fun authenticate(): AuthToken
    suspend fun uploadTrustGroup(group: EncryptedTrustGroup)
    suspend fun downloadTrustGroup(): EncryptedTrustGroup?
    suspend fun subscribeToUpdates(onUpdate: (TrustUpdate) -> Unit)
}

data class EncryptedTrustGroup(
    val encryptedData: ByteArray,    // E2E encrypted
    val metadata: CloudMetadata,
    val version: Int
)

// Hybrid mode configuration
data class TrustConfiguration(
    val mode: TrustMode = TrustMode.OFFLINE,
    val cloudProvider: CloudProvider? = null,
    val syncInterval: Duration = 5.minutes
)

enum class TrustMode {
    OFFLINE,      // Local only
    HYBRID,       // Local + cloud backup
    CLOUD_PRIMARY // Cloud with local cache
}
```

## Security Considerations

### Key Storage
```kotlin
// Platform-specific secure storage
interface SecureKeyStorage {
    // Android: Android Keystore
    // iOS: iOS Keychain with Secure Enclave
    // Desktop: OS Keychain (Keychain Access, Windows Credential Store)
    
    suspend fun storePrivateKey(alias: String, key: ECDSAPrivateKey)
    suspend fun retrievePrivateKey(alias: String): ECDSAPrivateKey?
    suspend fun deletePrivateKey(alias: String)
}
```

### Protocol Security
1. **Perfect Forward Secrecy**: Ephemeral ECDH keys for each pairing session
2. **Replay Protection**: Timestamps and nonces in all messages
3. **Man-in-the-Middle Prevention**: ECDSA signatures on all exchanges
4. **Group Key Rotation**: Automatic rotation when devices are removed

### Privacy Protections
1. **No Persistent Identifiers**: Device IDs regenerated periodically
2. **Local Processing**: All crypto operations performed on-device
3. **Minimal Metadata**: Only essential information in discovery
4. **Encrypted Storage**: All trust data encrypted at rest

## Testing Strategy

### Unit Tests
```kotlin
class TrustProtocolTest {
    @Test
    fun testECDHKeyExchange() {
        val aliceKeys = cryptoProvider.generateECDHKeypair()
        val bobKeys = cryptoProvider.generateECDHKeypair()
        
        val aliceSecret = cryptoProvider.computeECDHSecret(
            aliceKeys.private, bobKeys.public
        )
        val bobSecret = cryptoProvider.computeECDHSecret(
            bobKeys.private, aliceKeys.public
        )
        
        assertArrayEquals(aliceSecret, bobSecret)
    }
    
    @Test
    fun testSignatureVerification() {
        val keypair = cryptoProvider.generateECDSAKeypair()
        val data = "test message".toByteArray()
        val signature = cryptoProvider.signECDSA(data, keypair.private)
        
        assertTrue(cryptoProvider.verifyECDSA(data, signature, keypair.public))
    }
}
```

### Integration Tests
1. Cross-platform pairing tests
2. Network failure scenarios
3. Concurrent pairing attempts
4. Key rotation under load

### Security Tests
1. Invalid signature rejection
2. Replay attack prevention
3. Tampered message detection
4. Timing attack resistance

## Migration Path

### Initial Release (Offline Only)
1. Core trust protocol implementation
2. Automatic pairing without manual steps
3. File transfer auto-accept for trusted devices
4. Basic clipboard sync (opt-in)

### Future Enhancement (Hybrid Mode)
1. Add cloud authentication options
2. Implement encrypted cloud backup
3. Cross-network device sync
4. Web dashboard for device management

## Performance Optimizations

1. **Lazy Crypto Operations**: Generate ephemeral keys only when needed
2. **Connection Pooling**: Reuse connections to trusted devices
3. **Batch Updates**: Aggregate multiple updates in single message
4. **Selective Sync**: Only sync clipboard when content changes

## Success Metrics

1. **Security**: Zero protocol vulnerabilities in security audit
2. **Performance**: < 2 seconds for pairing completion
3. **Reliability**: 99.9% success rate for local pairing
4. **User Experience**: < 3 taps to establish trust

## Timeline

- **Weeks 1-2**: Core infrastructure and crypto implementation
- **Week 3**: Discovery integration
- **Week 4**: UI implementation
- **Week 5**: File transfer integration
- **Weeks 6-7**: Clipboard sync
- **Week 8**: Cloud sync preparation and testing
- **Week 9**: Security audit and fixes
- **Week 10**: Release preparation

## Next Steps

1. Review and approve implementation strategy
2. Set up cryptography-kotlin dependency
3. Create detailed API specifications
4. Begin Phase 1 implementation
5. Establish security review checkpoints