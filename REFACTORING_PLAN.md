# Comprehensive Refactoring Plan for Klardrop Trust Management System

## Executive Summary
The project has compilation errors due to mismatched model dependencies, over-engineered protobuf implementation, and UI/model coupling issues. This plan provides a phased approach to fix compilation, simplify the architecture, and ensure production readiness.

## Current Issues Identified

### Critical Issues
1. **Compilation Failures**: `common-ui:compileKotlinDesktopJvm` task fails with multiple errors
2. **Over-engineered Protobuf**: Using proto files instead of kotlinx.serialization
3. **Model Duplication**: Multiple definitions of same models (TrustLevel, Permission, etc.)
4. **Security Vulnerabilities**: Missing signature verification, hardcoded device info
5. **UI Errors**: Missing imports, syntax errors, type mismatches

### Code Quality Issues
1. Duplicate time formatting functions across 3 files
2. Stub implementations marked with TODO comments
3. Inefficient clipboard polling mechanism
4. String-based enum mapping in database
5. Silent exception handling without proper error propagation

## Phase 1: Remove Proto Dependencies & Fix Models (Priority: CRITICAL)

### 1.1 Remove trust_protocol.proto
**File**: `/protos/src/main/proto/trust_protocol.proto`
- **Action**: DELETE this file entirely
- **Reason**: We use kotlinx.serialization, not protobuf compiler
- **Impact**: Removes 168 lines of unnecessary proto definitions

### 1.2 Consolidate Trust Models
**File**: `/common/src/commonMain/kotlin/com/carlom/klardrop/common/trust/model/TrustModels.kt`

**Changes Required**:
```kotlin
// Simplify TrustLevel enum - only binary trust needed
enum class TrustLevel {
    TRUSTED,    // Device is in trust group
    UNTRUSTED   // Device is not in trust group
}

// Keep Permission enum as is
enum class Permission {
    FILE_SEND,
    FILE_RECEIVE,
    CLIPBOARD_SYNC
}

// Add @Serializable to all data classes for protobuf support
@Serializable
data class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType,
    val publicKey: ByteArray? = null
)

// Merge protocol message classes from ProtobufStubs.kt
@Serializable
data class DiscoveryAnnouncement(
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType,
    val publicKey: ByteArray,
    val isInTrustGroup: Boolean = false,
    val supportsAutoTrust: Boolean = false,
    val timestamp: Long = Clock().currentTimeMillis(),
    val signature: ByteArray = byteArrayOf()
)

// Continue with other protocol messages...
```

### 1.3 Remove TrustModelConverters
**File**: `/common/src/commonMain/kotlin/com/carlom/klardrop/common/trust/model/TrustModelConverters.kt`
- **Action**: DELETE entirely
- **Reason**: No longer needed without proto compilation

### 1.4 Remove UiStubs
**File**: `/common-ui/src/commonMain/kotlin/com/carlom/klardrop/common/trust/model/UiStubs.kt`
- **Action**: DELETE and update references
- **Replace**: Use models from `com.carlom.klardrop.common.trust.model` directly

## Phase 2: Fix UI Compilation Errors (Priority: CRITICAL)

### 2.1 Fix discovery_screen.kt
**File**: `/common-ui/src/commonMain/kotlin/com/carlom/klardrop/discovery_screen.kt`

**Line 111 Error**: `collectAsEffect` doesn't exist
```kotlin
// BEFORE (line 111):
discoveryController.actionsFlow.collectAsEffect {

// AFTER:
LaunchedEffect(discoveryController) {
    discoveryController.actionsFlow.collect {
```

**Line 138-140 Syntax Error**: Missing braces
```kotlin
// BEFORE:
}
sheetState = sheetState,
content = {

// AFTER:
},
sheetState = sheetState
) {
```

**Line 149-161 Composable Context Error**:
```kotlin
// Move LazyColumn inside Box composable
Box {
    LazyColumn(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
    ) {
        items(
            items = discoveryState.receivingMessages.toList(),
            key = { it.first }
        ) { item ->
            ReceiveNotification(
                modifier = Modifier.animateItem(),
                message = item.second,
                controller = discoveryController
            )
        }
    }
}
```

### 2.2 Create Shared Time Formatting Utility
**New File**: `/common-ui/src/commonMain/kotlin/com/carlom/klardrop/utils/TimeFormatUtils.kt`

* make sure to use kotlin libraries and methods and not java apis.
* Use Kotlin's Duration for better readability of the constants

```kotlin
package com.carlom.klardrop.utils

import kotlin.time.Clock

object TimeFormatUtils {
    fun formatRelativeTime(timestamp: Long): String {
        val now = Clock.System.now().toEpochMilliseconds()
        val diff = now - timestamp
        
        return when {
            diff < 0 -> {
                // Handle future dates
                val futureDiff = -diff
                when {
                    futureDiff < 60_000 -> "in a moment"
                    futureDiff < 3600_000 -> "in ${futureDiff / 60_000} minutes"
                    futureDiff < 86400_000 -> "in ${futureDiff / 3600_000} hours"
                    else -> "in ${futureDiff / 86400_000} days"
                }
            }
            diff < 60_000 -> "just now"
            diff < 3600_000 -> "${diff / 60_000} minutes ago"
            diff < 86400_000 -> "${diff / 3600_000} hours ago"
            else -> "${diff / 86400_000} days ago"
        }
    }
}
```

**Update References**:
- `trust_group_settings.kt`: Lines 330, 331, 550 - Remove private function, import TimeFormatUtils
- `trust_management_screen.kt`: Lines 219, 226, 234, 515 - Remove private function, import TimeFormatUtils
- `clipboard_sync_settings.kt`: Lines 256, 321, 378-388 - Remove private function, import TimeFormatUtils

### 2.3 Fix Import Issues
**Files to Update**:
- `trust_management_screen.kt`
- `trust_indicators.kt`
- `trust_navigation.kt`

**Changes**:
```kotlin
// REMOVE these imports:
import com.carlom.klardrop.protos.trust.*

// ADD these imports:
import com.carlom.klardrop.common.trust.model.Permission
import com.carlom.klardrop.common.trust.model.TrustLevel
import com.carlom.klardrop.common.trust.model.TrustedDevice
```

## Phase 3: Implement Proper Trust Protocol (Priority: HIGH)

### 3.1 Fix Security Vulnerabilities in TrustProtocolHandlerImpl

**File**: `/common/src/commonMain/kotlin/com/carlom/klardrop/common/trust/protocol/TrustProtocolHandlerImpl.kt`

**Line 40 - Add Signature Verification**:
```kotlin
override suspend fun handleDiscoveryAnnouncement(
    announcement: DiscoveryAnnouncement, 
    senderAddress: String
) {
    try {
        // Verify ECDSA signature
        val isValidSignature = cryptoProvider.verifySignature(
            data = "${announcement.deviceId}:${announcement.timestamp}".toByteArray(),
            signature = announcement.signature,
            publicKey = announcement.publicKey
        )
        
        if (!isValidSignature) {
            log(TAG, "Invalid signature from ${announcement.deviceId}")
            trustStore.logSecurityEvent(
                SecurityEventType.AUTH_FAILED,
                announcement.deviceId,
                senderAddress
            )
            return
        }
        
        // Continue with trust check...
    } catch (e: Exception) {
        log(TAG, "Error handling discovery announcement", e)
    }
}
```

**Line 132 - Fix Device Information Extraction**:
```kotlin
// BEFORE:
deviceName = "Unknown Device",
deviceType = DeviceType.UNKNOWN

// AFTER:
deviceName = extractDeviceName(senderAddress) ?: announcement.deviceName,
deviceType = extractDeviceType(senderAddress) ?: announcement.deviceType

// Add helper functions:
private fun extractDeviceName(address: String): String? {
    // Parse device name from mDNS service name or network headers
    return parseMdnsServiceName(address)?.substringBefore(".")
}

private fun extractDeviceType(address: String): DeviceType? {
    // Determine device type from service type or user agent
    return when {
        address.contains("_android") -> DeviceType.MOBILE
        address.contains("_ios") -> DeviceType.MOBILE
        address.contains("_desktop") -> DeviceType.DESKTOP
        else -> null
    }
}
```

**Line 260 - Implement Clipboard Signature**:
```kotlin
// BEFORE:
signature = byteArrayOf() // Would be verified in real implementation

// AFTER:
val contentHash = cryptoProvider.hashContent(clipboardContent)
signature = cryptoProvider.signData(
    data = "$deviceId:$contentHash:$timestamp".toByteArray(),
    privateKey = deviceInfo().privateKey
)
```

### 3.2 Add Session Cleanup

**Add to TrustProtocolHandlerImpl**:
```kotlin
private val sessionCleanupJob = scope.launch {
    while (isActive) {
        delay(60_000) // Check every minute
        cleanupExpiredSessions()
    }
}

private suspend fun cleanupExpiredSessions() {
    val now = Clock().currentTimeMillis()
    trustStore.getExpiredPairingSessions(now).forEach { session ->
        trustStore.updatePairingSessionStatus(
            session.sessionId,
            PairingSessionStatus.EXPIRED
        )
        log(TAG, "Expired pairing session ${session.sessionId}")
    }
}
```

## Phase 4: Database & Storage Improvements (Priority: MEDIUM)

### 4.1 Optimize TrustStore Operations

**File**: `/common/src/commonMain/kotlin/com/carlom/klardrop/common/trust/storage/TrustStore.kt`

**Optimize saveTrustGroup**:
```kotlin
suspend fun saveTrustGroup(group: TrustGroup) = withContext(Dispatchers.IO) {
    database.transaction {
        // Batch insert all devices
        val deviceInserts = group.devices.values.map { device ->
            TrustedDeviceEntity(
                deviceId = device.deviceId,
                groupId = group.groupId,
                // ... other fields
            )
        }
        
        // Single batch operation
        database.trustedDeviceQueries.insertBatch(deviceInserts)
        
        // Update group
        database.trustGroupQueries.insertOrReplace(
            groupId = group.groupId,
            // ... other fields
        )
    }
}
```

**Replace String-based Enum Mapping**:
```kotlin
// Use enum ordinals or dedicated converters
private fun TrustLevel.toDatabaseValue(): Int = ordinal
private fun Int.toTrustLevel(): TrustLevel = TrustLevel.values()[this]
```

## Phase 5: Performance Optimizations (Priority: MEDIUM)

### 5.1 Fix Clipboard Polling

**File**: `/common/src/commonMain/kotlin/com/carlom/klardrop/common/trust/clipboard/TrustClipboardSyncManager.kt`

**Platform-specific Implementation**:
```kotlin
// Common interface
interface ClipboardMonitor {
    fun observeChanges(): Flow<String>
}

// Android implementation
class AndroidClipboardMonitor(context: Context) : ClipboardMonitor {
    override fun observeChanges(): Flow<String> = callbackFlow {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            trySend(clipboardManager.primaryClip?.getItemAt(0)?.text?.toString() ?: "")
        }
        clipboardManager.addPrimaryClipChangedListener(listener)
        awaitClose { clipboardManager.removePrimaryClipChangedListener(listener) }
    }
}

// Desktop implementation with polling fallback
class DesktopClipboardMonitor : ClipboardMonitor {
    override fun observeChanges(): Flow<String> = flow {
        var lastContent = ""
        while (currentCoroutineContext().isActive) {
            val current = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String ?: ""
            if (current != lastContent) {
                emit(current)
                lastContent = current
            }
            delay(1000) // Poll every second on desktop
        }
    }
}
```

### 5.2 Fix Permission Defaults

**File**: `/common/src/commonMain/kotlin/com/carlom/klardrop/common/trust/model/TrustModelConverters.kt`

```kotlin
// Change unknown permissions to minimal access
fun Permission?.toSafePermission(): Permission {
    return this ?: Permission.FILE_SEND // Minimal permission by default
}
```

## Phase 6: Testing Requirements

### 6.1 Unit Tests to Add

**Create Test Files**:
1. `TrustProtocolHandlerTest.kt` - Test message handling, signature verification
2. `CryptoProviderTest.kt` - Test cryptographic operations
3. `TrustStoreTest.kt` - Test database operations
4. `TimeFormatUtilsTest.kt` - Test time formatting with edge cases

### 6.2 Integration Tests

**Create Integration Test**:
```kotlin
class TrustPairingFlowTest {
    @Test
    fun testCompletePairingFlow() {
        // 1. Device A creates trust group
        // 2. Device B discovers Device A
        // 3. Device A initiates pairing
        // 4. Device B accepts
        // 5. Verify both devices have correct trust state
    }
}
```

## Phase 7: Production Readiness

### 7.1 Error Handling Improvements

**Replace Silent Catches**:
```kotlin
// BEFORE:
} catch (e: Exception) {
    log(TAG, "Error: ${e.message}")
}

// AFTER:
} catch (e: Exception) {
    log(TAG, "Error in operation X", e)
    errorFlow.emit(UserFriendlyError(
        message = "Failed to complete operation",
        details = e.localizedMessage,
        retry = { retryOperation() }
    ))
}
```

### 7.2 Security Event Logging

**Add to TrustStore**:
```kotlin
suspend fun logSecurityEvent(
    type: SecurityEventType,
    deviceId: String?,
    ipAddress: String?,
    details: Map<String, String>? = null
) {
    database.securityEventQueries.insert(
        eventType = type.name,
        deviceId = deviceId,
        ipAddress = ipAddress,
        timestamp = Clock().currentTimeMillis(),
        details = details?.let { Json.encodeToString(it) }
    )
}
```

## Implementation Schedule

### Day 1: Critical Fixes (8 hours)
- [ ] Morning (4h): Phase 1 - Remove proto dependencies, consolidate models
- [ ] Afternoon (4h): Phase 2 - Fix UI compilation errors, create TimeFormatUtils

### Day 2: Security & Core Logic (8 hours)
- [ ] Morning (4h): Phase 3.1-3.2 - Implement signature verification, fix device identity
- [ ] Afternoon (4h): Phase 3.3-3.4 - Add clipboard signatures, session cleanup

### Day 3: Storage & Performance (8 hours)
- [ ] Morning (4h): Phase 4 - Optimize database operations
- [ ] Afternoon (4h): Phase 5 - Fix clipboard polling, optimize permissions

### Day 4: Testing & Production (8 hours)
- [ ] Morning (4h): Phase 6 - Add unit and integration tests
- [ ] Afternoon (4h): Phase 7 - Error handling, security logging, final review

## Success Metrics

- ✅ `./gradlew :common-ui:compileKotlinDesktopJvm` passes without errors
- ✅ `./gradlew :common:test` - All tests pass
- ✅ No "TODO", "FIXME", or "stub" comments remain in production code
- ✅ Security review finds no critical vulnerabilities
- ✅ Trust pairing works end-to-end on all platforms
- ✅ Clipboard sync works without excessive CPU usage
- ✅ Code coverage > 80% for critical paths

## Risk Mitigation

1. **Create Feature Branch**: `terragon/trust-refactoring`
2. **Incremental Testing**: Test after each phase completion
3. **Platform Testing Matrix**:
   - Android: API 26+
   - iOS: 14.0+
   - Desktop: JVM 17+
4. **Rollback Plan**: Keep original branch intact until all tests pass
5. **Code Review**: Security team review before merge

## Files to be Modified/Deleted

### Files to Delete:
- `/protos/src/main/proto/trust_protocol.proto`
- `/common/src/commonMain/kotlin/com/carlom/klardrop/common/trust/model/TrustModelConverters.kt`
- `/common-ui/src/commonMain/kotlin/com/carlom/klardrop/common/trust/model/UiStubs.kt`

### Files to Create:
- `/common-ui/src/commonMain/kotlin/com/carlom/klardrop/utils/TimeFormatUtils.kt`
- Test files for each major component

### Files to Modify (Major Changes):
- `/common/src/commonMain/kotlin/com/carlom/klardrop/common/trust/model/TrustModels.kt`
- `/common/src/commonMain/kotlin/com/carlom/klardrop/common/trust/model/ProtobufStubs.kt`
- `/common-ui/src/commonMain/kotlin/com/carlom/klardrop/discovery_screen.kt`
- `/common-ui/src/commonMain/kotlin/com/carlom/klardrop/trust_management_screen.kt`
- `/common-ui/src/commonMain/kotlin/com/carlom/klardrop/trust_group_settings.kt`
- `/common-ui/src/commonMain/kotlin/com/carlom/klardrop/clipboard_sync_settings.kt`
- `/common/src/commonMain/kotlin/com/carlom/klardrop/common/trust/protocol/TrustProtocolHandlerImpl.kt`

## Notes for Implementation Team

1. **Order is Critical**: Phase 1 & 2 must be completed first to fix compilation
2. **Test Continuously**: Run `./gradlew :common-ui:compileKotlinDesktopJvm` after each major change
3. **Document Changes**: Update CLAUDE.md with any architectural decisions
4. **Coordinate with Team**: Security changes in Phase 3 may affect other components
5. **Performance Testing**: Monitor clipboard sync CPU usage after Phase 5

## Appendix: Common Gradle Commands

```bash
# Check compilation
./gradlew :common-ui:compileKotlinDesktopJvm -q
./gradlew :common:compileKotlinJvm -q

# Run tests
./gradlew :common:test -q
./gradlew :common-ui:test -q

# Run desktop app for manual testing
./gradlew :desktop:run -q

# Clean and rebuild
./gradlew clean build -q
```