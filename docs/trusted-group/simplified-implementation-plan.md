# Simplified Trusted Device Groups Implementation Plan

## Executive Summary
This document provides a complete, simplified implementation plan for the Trusted Device Groups feature in Klardrop. The plan focuses on practical simplicity while maintaining security through ECDH key exchange and ECDSA message signatures. This approach replaces the over-engineered solution with a straightforward protocol that junior developers can implement.

## Table of Contents
1. [Overview](#overview)
2. [Core Requirements](#core-requirements)
3. [Technical Architecture](#technical-architecture)
4. [Implementation Phases](#implementation-phases)
5. [Detailed Protocol Specification](#detailed-protocol-specification)
6. [Code Organization](#code-organization)
7. [Security Considerations](#security-considerations)
8. [Testing Strategy](#testing-strategy)
9. [Implementation Guidelines](#implementation-guidelines)

## Overview

### Problem Statement
The current implementation is over-complicated with:
- Complex database schemas
- Multiple protocol layers
- Excessive cryptographic operations
- Difficult-to-maintain code structure

### Solution Approach
Simplify to the essential requirements:
- Use existing mDNS discovery
- Simple ECDH key exchange for pairing
- ECDSA signatures for message verification
- Key-value storage instead of complex databases
- Minimal UI changes

### Key Benefits
- **Simple**: Easy for junior developers to understand and implement
- **Secure**: Cryptographically sound with ECDH + ECDSA
- **Maintainable**: Clear separation of concerns, small focused files
- **Testable**: Each component can be unit tested independently

## Core Requirements

### Functional Requirements
1. **Device Pairing**: Users can designate specific devices as "trusted"
2. **Auto-Accept Transfers**: File transfers from trusted devices are automatically accepted
3. **Clipboard Synchronization**: Clipboard content syncs across trusted devices
4. **Trust Management**: Users can view and remove trusted devices

### Non-Functional Requirements
1. **Security**: Prevent impersonation and man-in-the-middle attacks
2. **Performance**: Signature verification < 50ms, clipboard sync < 100ms
3. **Reliability**: Trust relationships persist across app restarts
4. **Simplicity**: Implementation should be understandable by junior developers

## Technical Architecture

### System Architecture
```
┌─────────────────────────────────────────────────────────────┐
│                         Application Layer                      │
├─────────────────────────────────────────────────────────────┤
│  Discovery UI  │  Trust UI  │  File Transfer  │  Clipboard   │
├────────────────┴─────────────┴────────────────┴──────────────┤
│                      Trust Management Layer                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ TrustManager │  │ TrustCrypto  │  │ TrustStorage │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
├─────────────────────────────────────────────────────────────┤
│                    Communication Layer                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │UnifiedServer │  │ Message      │  │ Discovery    │       │
│  │              │  │ Handlers     │  │ (mDNS)       │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
├─────────────────────────────────────────────────────────────┤
│                      Network Layer (TCP)                       │
└─────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

#### TrustManager (Singleton)
- Orchestrates all trust operations
- Maintains list of trusted devices
- Handles pairing flow
- Verifies message signatures

#### TrustCrypto
- ECDH key generation and exchange
- ECDSA signature generation and verification
- Key derivation using HKDF
- Cryptographic utilities

#### TrustStorage
- Platform-specific key storage
- Persistence of trusted device public keys
- Simple key-value interface

### Technology Stack
- **Cryptography**: `dev.whyoleg.cryptography` library
- **Networking**: Existing Ktor TCP sockets
- **Discovery**: Existing mDNS implementation
- **Serialization**: Protocol Buffers (existing)
- **UI**: Compose Multiplatform (existing)

## Implementation Phases

### Phase 1: Core Infrastructure (Week 1)
1. Set up cryptography dependencies
2. Implement TrustCrypto with ECDH/ECDSA
3. Create TrustStorage interface and platform implementations
4. Define trust protocol messages

### Phase 2: Pairing Protocol (Week 2)
1. Implement pairing request/response flow
2. Add message handlers for trust messages
3. Create pairing UI components
4. Test key exchange between devices

### Phase 3: Message Verification (Week 3)
1. Add signature to all messages from trusted devices
2. Implement signature verification
3. Create trust verification utilities
4. Add trust status to device info

### Phase 4: Features Integration (Week 4)
1. Implement auto-accept for file transfers
2. Add clipboard synchronization
3. Create trust management UI
4. Add trust indicators to discovery screen

### Phase 5: Testing & Polish (Week 5)
1. Unit tests for all components
2. Integration tests for full flow
3. Security validation
4. Performance optimization

## Detailed Protocol Specification

### Message Types

#### 1. TrustPairingRequest
```protobuf
message TrustPairingRequest {
    string device_id = 1;
    string device_name = 2;
    bytes public_key = 3;      // ECDH public key
    int64 timestamp = 4;
    string device_type = 5;     // Android/iOS/Desktop
}
```

#### 2. TrustPairingResponse
```protobuf
message TrustPairingResponse {
    string device_id = 1;
    string device_name = 2;
    bytes public_key = 3;      // ECDH public key
    bool accepted = 4;
    int64 timestamp = 5;
}
```

#### 3. TrustedMessage
```protobuf
message TrustedMessage {
    bytes payload = 1;         // Actual message content
    int64 timestamp = 2;
    bytes nonce = 3;          // 16 random bytes
    bytes signature = 4;       // ECDSA signature
}
```

#### 4. ClipboardSyncMessage
```protobuf
message ClipboardSyncMessage {
    string content = 1;
    string mime_type = 2;
    int64 timestamp = 3;
    bytes signature = 4;
}
```

### Pairing Flow

#### Step 1: Discovery
- Device A discovers Device B via mDNS (existing functionality)
- Both devices show in discovery list

#### Step 2: Initiate Pairing
```kotlin
// User taps "Add to Trusted" on Device A
fun initiatePairing(targetDeviceId: String) {
    // Generate ECDH keypair
    val keyPair = trustCrypto.generateECDHKeyPair()
    
    // Store our keypair temporarily
    pairingSession[targetDeviceId] = keyPair
    
    // Create pairing request
    val request = TrustPairingRequest(
        device_id = getDeviceId(),
        device_name = getDeviceName(),
        public_key = keyPair.publicKey.encode(),
        timestamp = System.currentTimeMillis(),
        device_type = getPlatformType()
    )
    
    // Send to Device B
    sendMessage(targetDeviceId, request)
}
```

#### Step 3: Handle Pairing Request
```kotlin
// Device B receives pairing request
fun handlePairingRequest(request: TrustPairingRequest, senderAddress: String) {
    // Show approval dialog to user
    showPairingDialog(
        deviceName = request.device_name,
        deviceType = request.device_type,
        onAccept = {
            acceptPairing(request, senderAddress)
        },
        onReject = {
            rejectPairing(request.device_id)
        }
    )
}
```

#### Step 4: Accept Pairing
```kotlin
fun acceptPairing(request: TrustPairingRequest, senderAddress: String) {
    // Generate our ECDH keypair
    val ourKeyPair = trustCrypto.generateECDHKeyPair()
    
    // Compute shared secret
    val sharedSecret = trustCrypto.computeECDHSecret(
        privateKey = ourKeyPair.privateKey,
        peerPublicKey = request.public_key
    )
    
    // Store peer's public key for future signature verification
    trustStorage.storeTrustedDevice(
        deviceId = request.device_id,
        publicKey = request.public_key
    )
    
    // Send response
    val response = TrustPairingResponse(
        device_id = getDeviceId(),
        device_name = getDeviceName(),
        public_key = ourKeyPair.publicKey.encode(),
        accepted = true,
        timestamp = System.currentTimeMillis()
    )
    
    sendMessage(request.device_id, response)
}
```

#### Step 5: Complete Pairing
```kotlin
// Device A receives response
fun handlePairingResponse(response: TrustPairingResponse) {
    if (!response.accepted) {
        showRejectionMessage()
        return
    }
    
    // Retrieve our keypair from session
    val ourKeyPair = pairingSession[response.device_id]
    
    // Compute shared secret
    val sharedSecret = trustCrypto.computeECDHSecret(
        privateKey = ourKeyPair.privateKey,
        peerPublicKey = response.public_key
    )
    
    // Store peer's public key
    trustStorage.storeTrustedDevice(
        deviceId = response.device_id,
        publicKey = response.public_key
    )
    
    // Clear session
    pairingSession.remove(response.device_id)
    
    // Update UI
    showTrustEstablished(response.device_name)
}
```

### Message Signing and Verification

#### Signing Messages
```kotlin
fun signMessage(message: ByteArray): ByteArray {
    val timestamp = System.currentTimeMillis()
    val nonce = CryptographyRandom.nextBytes(16)
    
    // Create data to sign
    val dataToSign = message + timestamp.toByteArray() + nonce
    
    // Sign with our private key
    val signature = trustCrypto.signWithECDSA(
        privateKey = getOurPrivateKey(),
        data = dataToSign
    )
    
    return TrustedMessage(
        payload = message,
        timestamp = timestamp,
        nonce = nonce,
        signature = signature
    ).encode()
}
```

#### Verifying Signatures
```kotlin
fun verifyMessage(trustedMessage: TrustedMessage, senderId: String): Boolean {
    // Get sender's public key
    val senderPublicKey = trustStorage.getTrustedDeviceKey(senderId) 
        ?: return false
    
    // Check timestamp (prevent replay attacks)
    val currentTime = System.currentTimeMillis()
    if (abs(currentTime - trustedMessage.timestamp) > MAX_TIME_DIFF) {
        return false
    }
    
    // Reconstruct signed data
    val dataToVerify = trustedMessage.payload + 
                       trustedMessage.timestamp.toByteArray() + 
                       trustedMessage.nonce
    
    // Verify signature
    return trustCrypto.verifyECDSA(
        publicKey = senderPublicKey,
        data = dataToVerify,
        signature = trustedMessage.signature
    )
}
```

### Clipboard Synchronization

#### Broadcasting Clipboard Changes
```kotlin
class ClipboardSyncManager {
    fun onClipboardChanged(content: String) {
        val message = ClipboardSyncMessage(
            content = content,
            mime_type = "text/plain",
            timestamp = System.currentTimeMillis()
        )
        
        // Sign the message
        val signedMessage = trustManager.signMessage(message.encode())
        
        // Broadcast to all trusted devices
        trustManager.getTrustedDevices().forEach { device ->
            sendMessage(device.id, signedMessage)
        }
    }
}
```

#### Receiving Clipboard Updates
```kotlin
fun handleClipboardSync(message: ClipboardSyncMessage, senderId: String) {
    // Verify this is from a trusted device
    if (!trustManager.isTrusted(senderId)) {
        return
    }
    
    // Verify signature
    if (!trustManager.verifyMessage(message, senderId)) {
        log.warn("Invalid signature on clipboard sync from $senderId")
        return
    }
    
    // Update local clipboard
    clipboard.setText(message.content)
    
    // Show notification
    showNotification("Clipboard synced from ${getDeviceName(senderId)}")
}
```

## Code Organization

### File Structure
```
klardrop/
├── common/
│   ├── src/
│   │   ├── commonMain/
│   │   │   └── kotlin/
│   │   │       └── com/carlom/klardrop/common/
│   │   │           ├── trust/
│   │   │           │   ├── TrustManager.kt
│   │   │           │   ├── TrustCrypto.kt
│   │   │           │   ├── TrustStorage.kt
│   │   │           │   ├── models/
│   │   │           │   │   ├── TrustMessages.kt
│   │   │           │   │   └── TrustModels.kt
│   │   │           │   └── clipboard/
│   │   │           │       └── ClipboardSyncManager.kt
│   │   │           └── communication/
│   │   │               └── message/
│   │   │                   └── TrustMessages.kt
│   │   ├── androidMain/
│   │   │   └── kotlin/.../trust/
│   │   │       └── AndroidTrustStorage.kt
│   │   ├── iosMain/
│   │   │   └── kotlin/.../trust/
│   │   │       └── IosTrustStorage.kt
│   │   └── desktopMain/
│   │       └── kotlin/.../trust/
│   │           └── DesktopTrustStorage.kt
│   └── build.gradle.kts
├── common-ui/
│   └── src/commonMain/kotlin/com/carlom/klardrop/
│       ├── TrustIndicator.kt
│       ├── TrustPairingDialog.kt
│       └── TrustManagementScreen.kt
Note: No separate proto files needed - using @Serializable data classes for protobuf serialization
```

### Architectural Benefits of @Serializable Approach

**Benefits over Protocol Buffer files:**
1. **Simpler Build Process**: No protobuf compilation step required
2. **Type Safety**: Direct Kotlin types with compile-time validation  
3. **Better IDE Support**: Full IntelliJ support for refactoring and navigation
4. **Consistency**: Matches existing message patterns in codebase
5. **Maintenance**: Single source of truth in Kotlin code
6. **Developer Experience**: No need to learn separate proto syntax

**Implementation Details:**
- All trust message classes extend the sealed `Message` class
- Uses kotlinx.serialization with protobuf format for wire compatibility
- MessageSerializer automatically handles serialization/deserialization
- Proper equals() and hashCode() implementation for ByteArray fields

### Component Specifications

#### TrustManager.kt (~150 lines)
```kotlin
class TrustManager(
    private val crypto: TrustCrypto,
    private val storage: TrustStorage,
    private val messageHandler: MessageHandler
) {
    // Core API
    fun initiatePairing(deviceId: String)
    fun isTrusted(deviceId: String): Boolean
    fun getTrustedDevices(): List<TrustedDevice>
    fun removeTrust(deviceId: String)
    
    // Message handling
    fun signMessage(message: ByteArray): ByteArray
    fun verifyMessage(message: TrustedMessage, senderId: String): Boolean
    
    // Internal handlers
    private fun handlePairingRequest(request: TrustPairingRequest)
    private fun handlePairingResponse(response: TrustPairingResponse)
}
```

#### TrustCrypto.kt (~100 lines)
```kotlin
class TrustCrypto {
    private val provider = CryptographyProvider.Default
    private val ecdh = provider.get(ECDH)
    private val ecdsa = provider.get(ECDSA)
    
    fun generateECDHKeyPair(): ECDHKeyPair
    fun computeECDHSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray
    fun deriveKey(secret: ByteArray, salt: ByteArray, info: ByteArray): ByteArray
    
    fun generateECDSAKeyPair(): ECDSAKeyPair
    fun signWithECDSA(privateKey: ByteArray, data: ByteArray): ByteArray
    fun verifyECDSA(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
}
```

#### TrustStorage.kt (~50 lines interface)
```kotlin
interface TrustStorage {
    suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray)
    suspend fun getTrustedDeviceKey(deviceId: String): ByteArray?
    suspend fun getAllTrustedDevices(): Map<String, ByteArray>
    suspend fun removeTrustedDevice(deviceId: String)
    suspend fun clearAllTrustedDevices()
}
```

#### Platform Implementations

##### AndroidTrustStorage.kt
```kotlin
class AndroidTrustStorage(context: Context) : TrustStorage {
    private val prefs = context.getSharedPreferences("trust_keys", Context.MODE_PRIVATE)
    
    override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {
        prefs.edit()
            .putString("key_$deviceId", Base64.encodeToString(publicKey, Base64.DEFAULT))
            .apply()
    }
    
    override suspend fun getTrustedDeviceKey(deviceId: String): ByteArray? {
        return prefs.getString("key_$deviceId", null)?.let {
            Base64.decode(it, Base64.DEFAULT)
        }
    }
}
```

##### IosTrustStorage.kt
```kotlin
class IosTrustStorage : TrustStorage {
    override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {
        // Use Keychain Services
        val query = mapOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrAccount to "trust_$deviceId",
            kSecValueData to publicKey.toNSData()
        )
        SecItemAdd(query, null)
    }
}
```

##### DesktopTrustStorage.kt
```kotlin
class DesktopTrustStorage(private val appDir: File) : TrustStorage {
    private val trustFile = File(appDir, "trusted_devices.properties")
    
    override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {
        val props = Properties().apply {
            if (trustFile.exists()) {
                load(trustFile.inputStream())
            }
            setProperty(deviceId, Base64.getEncoder().encodeToString(publicKey))
        }
        props.store(trustFile.outputStream(), "Trusted Devices")
    }
}
```

## Security Considerations

### Threat Model
1. **Man-in-the-Middle Attack**: Prevented by ECDH key exchange with visual confirmation
2. **Replay Attack**: Prevented by timestamp and nonce in signed messages
3. **Impersonation**: Prevented by ECDSA signature verification
4. **Key Compromise**: Limited impact - only affects single device pair

### Security Requirements
1. **Key Generation**: Use cryptographically secure random number generator
2. **Key Storage**: Platform-specific secure storage (Keychain, encrypted SharedPreferences)
3. **Signature Verification**: Always verify before trusting message content
4. **Time Validation**: Reject messages with timestamps > 5 minutes old
5. **Nonce Uniqueness**: Track recent nonces to prevent replay

### Best Practices
1. Never transmit private keys
2. Always verify signatures before processing messages
3. Use constant-time comparison for signature verification
4. Clear sensitive data from memory after use
5. Log all security events (pairing, verification failures)

## Testing Strategy

### Unit Tests

#### TrustCrypto Tests
```kotlin
class TrustCryptoTest {
    @Test
    fun testECDHKeyExchange() {
        // Generate two keypairs
        val aliceKeys = crypto.generateECDHKeyPair()
        val bobKeys = crypto.generateECDHKeyPair()
        
        // Compute shared secrets
        val aliceSecret = crypto.computeECDHSecret(
            aliceKeys.privateKey, 
            bobKeys.publicKey
        )
        val bobSecret = crypto.computeECDHSecret(
            bobKeys.privateKey, 
            aliceKeys.publicKey
        )
        
        // Secrets should match
        assertEquals(aliceSecret, bobSecret)
    }
    
    @Test
    fun testECDSASignatureVerification() {
        val keyPair = crypto.generateECDSAKeyPair()
        val data = "test data".toByteArray()
        
        val signature = crypto.signWithECDSA(keyPair.privateKey, data)
        val valid = crypto.verifyECDSA(keyPair.publicKey, data, signature)
        
        assertTrue(valid)
    }
}
```

#### TrustManager Tests
```kotlin
class TrustManagerTest {
    @Test
    fun testPairingFlow() = runTest {
        val deviceA = createTestDevice("A")
        val deviceB = createTestDevice("B")
        
        // Device A initiates pairing
        deviceA.trustManager.initiatePairing(deviceB.id)
        
        // Device B receives and accepts
        deviceB.acceptPairing()
        
        // Both should now trust each other
        assertTrue(deviceA.trustManager.isTrusted(deviceB.id))
        assertTrue(deviceB.trustManager.isTrusted(deviceA.id))
    }
    
    @Test
    fun testMessageSignatureVerification() {
        val message = "test message".toByteArray()
        val signed = trustManager.signMessage(message)
        
        val valid = trustManager.verifyMessage(signed, senderId)
        assertTrue(valid)
    }
}
```

### Integration Tests
1. Full pairing flow between real devices
2. Clipboard synchronization across platforms
3. File transfer auto-accept behavior
4. Trust persistence across app restarts

### Security Tests
```kotlin
class SecurityTest {
    @Test
    fun testReplayAttackPrevention() {
        val message = createSignedMessage()
        
        // First verification should succeed
        assertTrue(trustManager.verifyMessage(message, senderId))
        
        // Modify timestamp to simulate replay
        Thread.sleep(MAX_TIME_DIFF + 1000)
        
        // Should now fail
        assertFalse(trustManager.verifyMessage(message, senderId))
    }
    
    @Test
    fun testImpersonationPrevention() {
        val message = createSignedMessage()
        
        // Try to verify with wrong sender ID
        assertFalse(trustManager.verifyMessage(message, wrongSenderId))
    }
}
```

### Performance Tests
```kotlin
class PerformanceTest {
    @Test
    fun testSignatureGenerationPerformance() {
        val data = ByteArray(1024)
        
        val time = measureTimeMillis {
            repeat(100) {
                crypto.signWithECDSA(privateKey, data)
            }
        }
        
        assertTrue(time / 100 < 50) // < 50ms per signature
    }
    
    @Test
    fun testClipboardSyncLatency() {
        val startTime = System.currentTimeMillis()
        
        deviceA.clipboard.setText("test")
        
        // Wait for sync
        await().atMost(Duration.ofMillis(100)).until {
            deviceB.clipboard.getText() == "test"
        }
    }
}
```

## Implementation Guidelines

### For Junior Developers

#### Getting Started
1. **Read the cryptography library docs**: https://whyoleg.github.io/cryptography-kotlin/
2. **Understand the existing codebase**: Review DiscoveryNetwork, UnifiedServer, MessageHandler
3. **Start with TrustCrypto**: Implement and test cryptographic operations first
4. **Build incrementally**: Complete each phase before moving to the next

#### Coding Standards
```kotlin
// Good: Clear, single responsibility
class TrustCrypto {
    fun generateECDHKeyPair(): ECDHKeyPair { 
        // Implementation
    }
}

// Bad: Mixed concerns
class TrustManager {
    fun generateKeysAndSendMessageAndUpdateUI() {
        // Too many responsibilities
    }
}
```

#### Error Handling
```kotlin
// Always handle cryptographic failures gracefully
fun verifyMessage(message: TrustedMessage, senderId: String): Boolean {
    return try {
        val publicKey = storage.getTrustedDeviceKey(senderId) ?: return false
        crypto.verifyECDSA(publicKey, message.data, message.signature)
    } catch (e: CryptographyException) {
        log.error("Signature verification failed", e)
        false
    }
}
```

#### Testing First
```kotlin
// Write test first
@Test
fun testTrustStoragePersistence() {
    val deviceId = "test-device"
    val publicKey = ByteArray(32)
    
    storage.storeTrustedDevice(deviceId, publicKey)
    val retrieved = storage.getTrustedDeviceKey(deviceId)
    
    assertEquals(publicKey, retrieved)
}

// Then implement
class AndroidTrustStorage : TrustStorage {
    override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {
        // Implementation to pass test
    }
}
```

### Common Pitfalls to Avoid

1. **Don't store private keys** - Only public keys of trusted devices
2. **Don't skip signature verification** - Always verify before trusting
3. **Don't use blocking I/O** - Use coroutines for all async operations
4. **Don't hardcode values** - Use constants for timeouts, key sizes
5. **Don't ignore platform differences** - Test on all platforms

### Debugging Tips

#### Logging
```kotlin
private val log = LoggerFactory.getLogger(TrustManager::class)

fun handlePairingRequest(request: TrustPairingRequest) {
    log.debug("Received pairing request from ${request.device_id}")
    log.trace("Public key: ${request.public_key.toHexString()}")
    // ...
}
```

#### Testing Individual Components
```kotlin
// Test crypto independently
fun main() {
    val crypto = TrustCrypto()
    val keyPair = crypto.generateECDHKeyPair()
    println("Generated keypair: ${keyPair.publicKey.toHexString()}")
}
```

## Success Metrics

### User Experience
- Pairing completes in < 3 taps
- Pairing process takes < 2 seconds
- Trust indicators clearly visible
- No false positives in trust verification

### Technical Metrics
- Signature generation: < 50ms
- Signature verification: < 50ms
- Clipboard sync latency: < 100ms
- Key storage persistence: 100% reliable

### Code Quality
- Unit test coverage: > 80%
- No security vulnerabilities
- Code review approval from senior developer
- Documentation complete

## Rollout Plan

### Phase 1: Development (Weeks 1-5)
- Implement core components
- Write comprehensive tests
- Internal testing on all platforms

### Phase 2: Beta Testing (Week 6)
- Deploy to small group of users
- Monitor for security issues
- Gather performance metrics

### Phase 3: Production Release (Week 7)
- Final security audit
- Performance optimization
- Full deployment

## Appendix

### A. Cryptography Library Examples

#### ECDH Key Exchange
```kotlin
import dev.whyoleg.cryptography.*
import dev.whyoleg.cryptography.algorithms.asymmetric.ECDH
import dev.whyoleg.cryptography.algorithms.asymmetric.EC

val provider = CryptographyProvider.Default
val ecdh = provider.get(ECDH)

// Generate keypair
val keyPairGenerator = ecdh.keyPairGenerator(EC.Curve.P256)
val keyPair = keyPairGenerator.generateKey()

// Compute shared secret
val sharedSecret = keyPair.privateKey.sharedSecretGenerator()
    .generateSharedSecret(peerPublicKey)
```

#### ECDSA Signatures
```kotlin
import dev.whyoleg.cryptography.algorithms.asymmetric.ECDSA
import dev.whyoleg.cryptography.algorithms.digest.SHA256

val ecdsa = provider.get(ECDSA)
val keyPairGenerator = ecdsa.keyPairGenerator(EC.Curve.P256)
val keyPair = keyPairGenerator.generateKey()

// Sign data
val signature = keyPair.privateKey
    .signatureGenerator(SHA256, ECDSA.SignatureFormat.DER)
    .generateSignature(data)

// Verify signature
val valid = keyPair.publicKey
    .signatureVerifier(SHA256, ECDSA.SignatureFormat.DER)
    .tryVerifySignature(data, signature)
```

### B. Trust Message Implementation Status

**All trust messages implemented as Kotlin data classes with @Serializable annotations:**

**Location:** `/common/src/commonMain/kotlin/com/carlom/klardrop/common/communication/message/TrustMessages.kt`

**Completed Messages:**
- ✅ `TrustPairingRequest` - Device pairing initiation
- ✅ `TrustPairingResponse` - Pairing acceptance/rejection  
- ✅ `TrustedMessage` - Signed message wrapper for secure communication
- ✅ `ClipboardSyncMessage` - Clipboard content synchronization
- ✅ `TrustRevocationMessage` - Trust relationship termination

**Architecture Benefits:**
- **Type Safety**: Compile-time validation and IntelliJ support
- **Simplicity**: No separate proto compilation or build steps
- **Integration**: Seamless integration with existing Message architecture
- **Maintenance**: Single source of truth in Kotlin code

### C. UI Mockups

#### Discovery Screen with Trust Indicators
```
┌─────────────────────────────┐
│ Nearby Devices              │
├─────────────────────────────┤
│ 🔒 John's MacBook           │
│    [Trusted Device]         │
│                             │
│ 📱 Sarah's iPhone           │
│    [Add to Trusted]         │
│                             │
│ 💻 Work Laptop              │
│    [Add to Trusted]         │
└─────────────────────────────┘
```

#### Pairing Dialog
```
┌─────────────────────────────┐
│ Trust Request               │
├─────────────────────────────┤
│ John's MacBook wants to     │
│ establish trust with this   │
│ device.                     │
│                             │
│ This will enable:           │
│ • Auto-accept files         │
│ • Clipboard sync            │
│                             │
│ [Reject]         [Accept]   │
└─────────────────────────────┘
```

#### Trust Management Screen
```
┌─────────────────────────────┐
│ Trusted Devices             │
├─────────────────────────────┤
│ John's MacBook              │
│ Added: 2 days ago           │
│ [Remove Trust]              │
├─────────────────────────────┤
│ Sarah's iPhone              │
│ Added: 1 week ago           │
│ [Remove Trust]              │
├─────────────────────────────┤
│ Settings                    │
│ ☑️ Enable clipboard sync    │
│ ☑️ Auto-accept files        │
└─────────────────────────────┘
```

### D. Troubleshooting Guide

#### Common Issues

1. **Pairing fails immediately**
   - Check network connectivity
   - Verify both devices are on same network
   - Check firewall settings

2. **Signature verification fails**
   - Ensure clocks are synchronized
   - Verify public keys are correctly stored
   - Check for key corruption

3. **Clipboard sync not working**
   - Verify trust is established
   - Check clipboard permissions
   - Ensure sync is enabled in settings

4. **Keys not persisting**
   - Check storage permissions
   - Verify platform-specific storage implementation
   - Look for storage exceptions in logs

### E. Glossary

- **ECDH**: Elliptic Curve Diffie-Hellman - Key exchange protocol
- **ECDSA**: Elliptic Curve Digital Signature Algorithm
- **HKDF**: HMAC-based Key Derivation Function
- **mDNS**: Multicast DNS - Used for device discovery
- **Nonce**: Number used once - Prevents replay attacks
- **Public Key**: Key that can be shared publicly for verification
- **Private Key**: Secret key used for signing
- **Shared Secret**: Key derived from ECDH exchange
- **Trust Group**: Set of devices that trust each other

---

This document provides a complete implementation guide for the Trusted Device Groups feature. The simplified approach focuses on security essentials while maintaining ease of implementation for junior developers.