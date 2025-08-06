# Trust Feature Implementation Plan for PR #305

This document provides a detailed plan to complete the trust feature implementation by replacing stub implementations and resolving compilation errors.

## Overview of Current Issues

Based on the analysis of PR #305 and the current branch state, the following compilation issues need to be resolved:

1. **TrustProtocolHandlerStub** - Currently a stub implementation that needs to be fully implemented
2. **TrustStore.kt** - Type conflicts between protobuf-generated types and local model types
3. **TrustClipboardSyncManager** - Missing property references and type mismatches
4. **DeviceInfoWithTrust** - Type conversion issues (this type doesn't exist in the codebase)

## Key Type Mapping Issues

### 1. DeviceType Conflict
- **Local Model**: `com.carlom.klardrop.common.utils.DeviceType` (enum with MOBILE, DESKTOP, UNKNOWN)
- **Protobuf**: `com.carlom.klardrop.protos.trust.DeviceType` (enum with DEVICE_TYPE_ANDROID, DEVICE_TYPE_IOS, etc.)
- **Solution**: Create conversion functions between the two types

### 2. Missing Protobuf Message Type Imports
The protobuf-generated types need to be properly imported:
- `com.carlom.klardrop.protos.trust.DiscoveryAnnouncement`
- `com.carlom.klardrop.protos.trust.ECDHInitiation`
- `com.carlom.klardrop.protos.trust.ECDHResponse`
- `com.carlom.klardrop.protos.trust.GroupInvitation`
- `com.carlom.klardrop.protos.trust.JoinConfirmation`
- `com.carlom.klardrop.protos.trust.MemberUpdate`
- `com.carlom.klardrop.protos.trust.ClipboardSync`
- `com.carlom.klardrop.protos.trust.TrustLevel`
- `com.carlom.klardrop.protos.trust.Permission`
- `com.carlom.klardrop.protos.trust.UpdateAction`

### 3. Model Structure Differences
- **Local TrustedDevice**: Flat structure with all properties
- **Protobuf TrustedDevice**: Contains nested `DeviceIdentity` object
- **Solution**: Create conversion functions between the two representations

## Detailed Implementation Tasks

### Task 1: Create Type Conversion Utilities

**File to create**: `common/src/commonMain/kotlin/com/carlom/klardrop/common/trust/model/TrustModelConverters.kt`

```kotlin
package com.carlom.klardrop.common.trust.model

import com.carlom.klardrop.common.utils.DeviceType as LocalDeviceType
import com.carlom.klardrop.protos.trust.*

// DeviceType conversions
fun LocalDeviceType.toProtoDeviceType(): DeviceType {
    return when (this) {
        LocalDeviceType.MOBILE -> DeviceType.DEVICE_TYPE_ANDROID // Default mobile to Android
        LocalDeviceType.DESKTOP -> DeviceType.DEVICE_TYPE_LINUX // Default desktop to Linux
        LocalDeviceType.UNKNOWN -> DeviceType.DEVICE_TYPE_UNKNOWN
    }
}

fun DeviceType.toLocalDeviceType(): LocalDeviceType {
    return when (this) {
        DeviceType.DEVICE_TYPE_ANDROID, DeviceType.DEVICE_TYPE_IOS -> LocalDeviceType.MOBILE
        DeviceType.DEVICE_TYPE_MACOS, DeviceType.DEVICE_TYPE_WINDOWS, DeviceType.DEVICE_TYPE_LINUX -> LocalDeviceType.DESKTOP
        DeviceType.DEVICE_TYPE_UNKNOWN, DeviceType.UNRECOGNIZED -> LocalDeviceType.UNKNOWN
    }
}

// TrustedDevice conversions
fun TrustedDevice.toProtoTrustedDevice(): com.carlom.klardrop.protos.trust.TrustedDevice {
    return com.carlom.klardrop.protos.trust.TrustedDevice(
        identity = DeviceIdentity(
            device_id = deviceId,
            public_key = okio.ByteString.of(*publicKey),
            device_name = deviceName,
            device_type = deviceType.toProtoDeviceType(),
            capabilities = permissions.map { it.toProtoPermission() }
        ),
        added_at = addedAt,
        added_by = addedBy,
        trust_level = trustLevel.toProtoTrustLevel(),
        permissions = permissions.map { it.toProtoPermission() },
        expires_at = expiresAt ?: 0
    )
}

fun com.carlom.klardrop.protos.trust.TrustedDevice.toLocalTrustedDevice(groupId: String): TrustedDevice {
    return TrustedDevice(
        deviceId = identity.device_id,
        groupId = groupId,
        publicKey = identity.public_key.toByteArray(),
        deviceName = identity.device_name,
        deviceType = identity.device_type.toLocalDeviceType(),
        addedAt = added_at,
        addedBy = added_by,
        lastSeen = null,
        trustLevel = trust_level.toLocalTrustLevel(),
        permissions = permissions.map { it.toLocalPermission() }.toSet(),
        expiresAt = if (expires_at > 0) expires_at else null,
        isActive = true
    )
}

// Add other conversion functions...
```

### Task 2: Update TrustProtocolHandler Implementation

**File to update**: `common/src/commonMain/kotlin/com/carlom/klardrop/common/trust/protocol/TrustProtocolHandlerStub.kt`

Rename to `TrustProtocolHandlerImpl.kt` and implement all methods properly:

```kotlin
package com.carlom.klardrop.common.trust.protocol

import com.carlom.klardrop.common.trust.crypto.CryptoProvider
import com.carlom.klardrop.common.trust.model.*
import com.carlom.klardrop.common.trust.storage.TrustStore
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.protos.trust.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class TrustProtocolHandlerImpl(
    private val trustStore: TrustStore,
    private val cryptoProvider: CryptoProvider,
    private val deviceInfo: suspend () -> DeviceKeypair,
    private val sendMessage: suspend (deviceId: String, message: com.carlom.klardrop.protos.trust.TrustMessage) -> Unit
) : TrustProtocolHandler {
    
    companion object {
        private const val TAG = "TrustProtocolHandler"
        private const val PAIRING_SESSION_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }
    
    private val _trustEvents = MutableSharedFlow<TrustEvent>()
    
    override suspend fun createDiscoveryAnnouncement(): DiscoveryAnnouncement {
        val device = deviceInfo()
        val trustGroup = trustStore.getTrustGroup()
        
        val announcement = DiscoveryAnnouncement(
            device_id = device.deviceId,
            public_key = okio.ByteString.of(*device.publicKey),
            is_in_trust_group = trustGroup != null,
            supports_auto_trust = true,
            timestamp = Clock().currentTimeMillis(),
            protocol_version = 1,
            signature = okio.ByteString.EMPTY
        )
        
        // Sign the announcement
        val dataToSign = buildString {
            append(announcement.device_id)
            append(announcement.public_key.hex())
            append(announcement.is_in_trust_group)
            append(announcement.supports_auto_trust)
            append(announcement.timestamp)
            append(announcement.protocol_version)
        }.encodeToByteArray()
        
        val signature = cryptoProvider.signECDSA(dataToSign, device.privateKey)
        
        return announcement.copy(signature = okio.ByteString.of(*signature))
    }
    
    override suspend fun handleDiscoveryAnnouncement(announcement: DiscoveryAnnouncement, senderAddress: String) {
        // Verify signature
        val dataToVerify = buildString {
            append(announcement.device_id)
            append(announcement.public_key.hex())
            append(announcement.is_in_trust_group)
            append(announcement.supports_auto_trust)
            append(announcement.timestamp)
            append(announcement.protocol_version)
        }.encodeToByteArray()
        
        val isValid = cryptoProvider.verifyECDSA(
            dataToVerify,
            announcement.signature.toByteArray(),
            announcement.public_key.toByteArray()
        )
        
        if (!isValid) {
            log(TAG, "Invalid signature in discovery announcement from ${announcement.device_id}")
            return
        }
        
        // Check if device is already trusted
        val isTrusted = trustStore.isDeviceTrusted(announcement.device_id)
        
        if (!isTrusted) {
            // Emit new device event
            _trustEvents.emit(
                TrustEvent.NewDeviceNearby(
                    device = DeviceIdentity(
                        deviceId = announcement.device_id,
                        deviceName = "Unknown Device", // Will be updated during pairing
                        deviceType = LocalDeviceType.UNKNOWN
                    )
                )
            )
        } else {
            // Update last seen
            trustStore.updateDeviceLastSeen(announcement.device_id)
        }
    }
    
    @OptIn(ExperimentalUuidApi::class)
    override suspend fun initiatePairing(deviceId: String): String {
        val sessionId = Uuid.random().toString()
        val device = deviceInfo()
        
        // Generate ephemeral key pair for ECDH
        val ephemeralKeypair = cryptoProvider.generateECDHKeypair()
        
        // Create pairing session
        val session = PairingSession(
            sessionId = sessionId,
            deviceId = deviceId,
            ephemeralPublicKey = ephemeralKeypair.publicKey,
            expiresAt = Clock().currentTimeMillis() + PAIRING_SESSION_TIMEOUT_MS
        )
        
        trustStore.createPairingSession(session)
        
        // Send ECDH initiation
        val initiation = ECDHInitiation(
            session_id = sessionId,
            device_id = device.deviceId,
            ephemeral_public_key = okio.ByteString.of(*ephemeralKeypair.publicKey),
            encrypted_group_id = okio.ByteString.EMPTY, // Will be set if we have a group
            timestamp = Clock().currentTimeMillis()
        )
        
        val message = com.carlom.klardrop.protos.trust.TrustMessage(
            type = TrustMessageType.TRUST_MESSAGE_TYPE_ECDH_INITIATION,
            ecdh_initiation = initiation
        )
        
        sendMessage(deviceId, message)
        
        return sessionId
    }
    
    // Continue implementing other methods...
}
```

### Task 3: Fix TrustStore Type Issues

**File to update**: `common/src/commonMain/kotlin/com/carlom/klardrop/common/trust/storage/TrustStore.kt`

Key changes needed:
1. Import the protobuf types
2. Fix the enum conversions in the database mapping functions
3. Update the type references

```kotlin
// Add imports at the top
import com.carlom.klardrop.protos.trust.DeviceType as ProtoDeviceType
import com.carlom.klardrop.protos.trust.TrustLevel as ProtoTrustLevel
import com.carlom.klardrop.protos.trust.Permission as ProtoPermission

// Update the conversion functions
private fun com.carlom.klardrop.common.database.Trusted_devices.toTrustedDevice(): TrustedDevice {
    return TrustedDevice(
        deviceId = device_id,
        groupId = group_id,
        publicKey = public_key,
        deviceName = device_name,
        deviceType = when (device_type) {
            "MOBILE" -> DeviceType.MOBILE
            "DESKTOP" -> DeviceType.DESKTOP
            else -> DeviceType.UNKNOWN
        },
        addedAt = added_at,
        addedBy = added_by,
        lastSeen = last_seen,
        trustLevel = when (trust_level) {
            "FULL" -> TrustLevel.FULL
            "LIMITED" -> TrustLevel.LIMITED
            "MINIMAL" -> TrustLevel.MINIMAL
            else -> TrustLevel.FULL
        },
        permissions = json.decodeFromString<List<String>>(permissions).map { 
            when (it) {
                "FILE_SEND" -> Permission.FILE_SEND
                "FILE_RECEIVE" -> Permission.FILE_RECEIVE
                "CLIPBOARD_SYNC" -> Permission.CLIPBOARD_SYNC
                else -> Permission.FILE_SEND
            }
        }.toSet(),
        expiresAt = expires_at,
        isActive = is_active == 1L
    )
}
```

### Task 4: Fix TrustClipboardSyncManager

**File to update**: `common/src/commonMain/kotlin/com/carlom/klardrop/common/trust/clipboard/TrustClipboardSyncManager.kt`

Key issues:
1. Import the protobuf Permission type correctly
2. Fix the clipboard manager flow property reference
3. Fix the TrustEvent import

```kotlin
// Update imports
import com.carlom.klardrop.common.trust.model.Permission
import com.carlom.klardrop.common.trust.protocol.TrustEvent

// Fix the clipboard monitoring
private fun startClipboardMonitoring() {
    clipboardMonitorJob?.cancel()
    
    clipboardMonitorJob = scope.launch {
        // Create a flow that polls clipboard periodically
        flow {
            while (true) {
                emit(clipboardManager.read())
                delay(DEBOUNCE_DELAY_MS)
            }
        }
        .distinctUntilChanged()
        .collect { content ->
            handleLocalClipboardChange(content)
        }
    }
    
    log(TAG, "Started clipboard monitoring")
}
```

### Task 5: Create Missing Type Aliases

**File to create**: `common/src/commonMain/kotlin/com/carlom/klardrop/common/trust/model/TrustTypeAliases.kt`

```kotlin
package com.carlom.klardrop.common.trust.model

// Type aliases for local trust enums to avoid conflicts with protobuf
enum class TrustLevel {
    MINIMAL,
    LIMITED,
    FULL
}

enum class Permission {
    FILE_SEND,
    FILE_RECEIVE,
    CLIPBOARD_SYNC
}

// Device identity for non-protobuf usage
data class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val deviceType: com.carlom.klardrop.common.utils.DeviceType
)
```

### Task 6: Implementation Order

To successfully implement these changes, follow this order:

1. **Create Type Aliases and Converters** (30 minutes)
   - Create `TrustTypeAliases.kt` with local enums
   - Create `TrustModelConverters.kt` with conversion functions
   - Test compilation of these new files

2. **Fix TrustStore Type Issues** (45 minutes)
   - Update imports to use local enums
   - Fix database conversion functions
   - Update method signatures where needed
   - Test that TrustStore compiles

3. **Implement TrustProtocolHandler** (2 hours)
   - Rename stub to `TrustProtocolHandlerImpl.kt`
   - Implement all interface methods properly
   - Add proper error handling and logging
   - Use the conversion functions for protobuf types

4. **Fix TrustClipboardSyncManager** (30 minutes)
   - Update imports to use local Permission enum
   - Fix clipboard flow monitoring
   - Update method signatures

5. **Integration Testing** (1 hour)
   - Compile the entire common module
   - Run unit tests
   - Fix any remaining compilation errors

## Testing Strategy

1. **Unit Tests**: Create tests for each converter function
2. **Integration Tests**: Test the full trust protocol flow
3. **Clipboard Sync Tests**: Test clipboard synchronization between devices

## Common Pitfalls to Avoid

1. **Type Confusion**: Always be clear whether you're using protobuf types or local model types
2. **Null Safety**: Many protobuf fields can be null/0, handle these cases
3. **ByteArray vs ByteString**: Protobuf uses `okio.ByteString`, local models use `ByteArray`
4. **Database Type Storage**: Store enums as strings in the database for clarity

## Success Criteria

1. All compilation errors in the common module are resolved
2. No stub implementations remain
3. Trust protocol handler properly implements all methods
4. Type conversions work correctly between protobuf and local models
5. Clipboard sync manager compiles and functions properly

## Next Steps After Implementation

1. Update the UI components to use the new trust implementation
2. Add comprehensive logging for debugging
3. Implement retry logic for failed trust operations
4. Add metrics/telemetry for trust feature usage