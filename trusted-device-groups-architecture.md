# Trusted Device Groups Architecture

## Overview
This document outlines the architecture for implementing trusted device groups in Klardrop, allowing users to create groups of their own devices that can share files and sync clipboards without requiring confirmation.

## Feature Goals
- Allow users to group their own devices together
- Enable automatic file transfers between trusted devices (no confirmation needed)
- Support automatic clipboard synchronization across trusted devices
- Ensure security - only legitimate devices can join a group
- Provide a seamless user experience

## Architecture Options

### Option 1: Cloud-Based Authentication (Google/Apple Sign-in)

**Pros:**
- Simple user experience - just sign in on each device
- Devices automatically discover each other across different networks
- Easy to manage devices (add/remove from web interface)
- Built-in security from OAuth providers

**Cons:**
- Requires internet connection
- Depends on third-party services
- Privacy concerns - user data goes through cloud
- Goes against the app's offline-first philosophy

**Implementation:**
1. User signs in with Google/Apple on each device
2. Device registers itself with a backend service
3. Backend maintains list of user's devices
4. Devices fetch trusted device list from backend
5. Local discovery still uses mDNS but validates against trusted list

### Option 2: Decentralized/Offline Approach (Recommended)

**Pros:**
- Works completely offline
- Privacy-preserving - no data leaves local network
- Aligns with Klardrop's philosophy
- No external dependencies

**Cons:**
- More complex initial pairing process
- Devices must be on same network for initial setup
- Need to handle key management carefully

**Implementation Overview:**
1. Each device generates a unique device keypair (public/private)
2. Groups share a symmetric group key for validation
3. Pairing process exchanges keys securely
4. All group members maintain a local database of trusted devices

## Proposed Architecture (Decentralized Approach)

### Key Components

#### 1. Device Identity
```kotlin
data class DeviceIdentity(
    val deviceId: String,           // Unique device ID
    val publicKey: ByteArray,       // Device's public key
    val deviceName: String,         // User-friendly name
    val deviceType: DeviceType,     // Android, iOS, Desktop, etc.
    val addedAt: Long              // Timestamp when added to group
)
```

#### 2. Trust Group
```kotlin
data class TrustGroup(
    val groupId: String,            // Unique group ID
    val groupKey: ByteArray,        // Shared symmetric key
    val devices: List<DeviceIdentity>,
    val createdAt: Long,
    val groupName: String,          // Optional user-defined name
    val protocolVersion: Int = 1    // Enable future protocol upgrades
)
```

### Pairing Process

#### Initial Group Creation (First Device)
1. User initiates "Create Trust Group" on Device A
2. Device A generates:
   - Group ID (UUID)
   - Group symmetric key (256-bit)
   - Its own device keypair
3. Device A becomes the first member of the group

#### Adding a New Device
1. **Discovery Phase:**
   - Device A (existing member) discovers Device B via mDNS
   - Device B shows up as "untrusted" in device list
   
2. **Pairing Initiation:**
   - User selects "Add to My Devices" on Device A
   - Device A generates a time-limited pairing token
   - Multiple pairing methods available:
     - **QR Code**: For visual scanning (with warning about photography risk)
     - **Proximity-based**: Using device vibration patterns or audio chirps
     - **Manual PIN**: 6-digit PIN as fallback option
     - **NFC tap**: For supported devices (Android/iOS)
   
3. **Secure Key Exchange with Forward Secrecy:**
   ```kotlin
   // Phase 1: Initial handshake with ephemeral keys
   Device A → Device B: PairingRequest {
     pairingToken: String,
     devicePublicKey: ByteArray,
     ephemeralPublicKey: ByteArray,  // Ephemeral key for this session
     challenge: ByteArray,
     timestamp: Long,                // Prevent replay attacks
     nonce: ByteArray               // Additional randomness
   }
   
   // Phase 2: Response with device info
   Device B → Device A: PairingResponse {
     devicePublicKey: ByteArray,
     ephemeralPublicKey: ByteArray,  // B's ephemeral key
     challengeResponse: ByteArray,
     deviceInfo: DeviceIdentity,
     timestamp: Long,
     signature: ByteArray           // Sign with device private key
   }
   
   // Phase 3: Two-phase commit - provisional membership
   Device A → Device B: ProvisionalAcceptance {
     encryptedGroupKey: ByteArray,  // Encrypted with shared ephemeral secret
     groupId: String,
     provisionalExpiry: Long,       // Time limit for other devices to verify
     existingMembers: List<DeviceIdentity>
   }
   
   // Phase 4: Full membership after verification
   Device A → Device B: FullMembership {
     status: MembershipStatus,      // ACCEPTED or REJECTED
     groupMembers: List<DeviceIdentity>
   }
   ```

4. **Confirmation:**
   - Both devices show confirmation dialog
   - User confirms on both devices
   - Device B is added to the group

### Security Measures

1. **Pairing Security:**
   - Time-limited pairing tokens (5 minutes)
   - Visual confirmation (device names/icons)
   - Optional PIN verification for extra security

2. **Group Key Management:**
   - AES-256 symmetric key for group (or ChaCha20-Poly1305 for mobile performance)
   - Key derivation using HKDF (HMAC-based Key Derivation Function) with salt
   - Automatic key rotation with versioning:
     ```kotlin
     data class GroupKeyVersion(
         val version: Int,
         val key: ByteArray,
         val validFrom: Long,
         val validUntil: Long?
     )
     ```
   - Keys stored in platform keychain/keystore
   - Old keys retained temporarily for message decryption during rotation

3. **Message Authentication:**
   - All messages between trusted devices include HMAC
   - Use group key for HMAC computation
   - Prevents spoofing attacks

### User Experience Flow

#### First-Time Setup
1. User opens Klardrop on their first device
2. App prompts: "Create a device group for easy sharing?"
3. User creates group (optionally names it)
4. Device is now ready to accept other devices

#### Adding Second Device
1. User opens Klardrop on second device
2. First device appears in discovery with a "trust" icon
3. User taps "Add to My Devices" on either device
4. QR code shown on one device, scanned by other
5. Both devices confirm pairing
6. Devices now trust each other

#### Daily Usage
- Trusted devices show with special badge/color
- File drops to trusted devices happen instantly
- Clipboard sync toggle in settings
- "My Devices" section in device list

### Implementation Plan

#### Phase 1: Core Infrastructure
- [ ] Create trust group data models
- [ ] Implement secure key generation
- [ ] Add persistence layer for trust data
- [ ] Create device identity management

#### Phase 2: Pairing Protocol
- [ ] Implement pairing message types
- [ ] Add QR code generation/scanning
- [ ] Create pairing UI flow
- [ ] Implement security validations

#### Phase 3: Trust Integration
- [ ] Modify discovery to identify trusted devices
- [ ] Update file transfer to skip confirmation
- [ ] Add trust indicators to UI
- [ ] Implement device management screen

#### Phase 4: Clipboard Sync
- [ ] Create clipboard sync protocol with configurable intervals (min 30s)
- [ ] Add sync preferences (manual vs automatic)
- [ ] Handle platform restrictions:
  - iOS: Request clipboard permission
  - Android 12+: Handle background clipboard restrictions
  - Desktop: Security software compatibility
- [ ] Implement conflict resolution with timestamps
- [ ] Start with manual sync button before automatic

### Data Storage

#### Local Database Schema
```sql
-- Trust Groups
CREATE TABLE trust_groups (
    group_id TEXT PRIMARY KEY,
    group_key BLOB NOT NULL,
    group_name TEXT,
    created_at INTEGER NOT NULL,
    is_active INTEGER DEFAULT 1
);

-- Trusted Devices
CREATE TABLE trusted_devices (
    device_id TEXT PRIMARY KEY,
    group_id TEXT NOT NULL,
    public_key BLOB NOT NULL,
    device_name TEXT NOT NULL,
    device_type TEXT NOT NULL,
    added_at INTEGER NOT NULL,
    last_seen INTEGER,
    FOREIGN KEY (group_id) REFERENCES trust_groups(group_id)
);

-- Pairing History (for security audit)
CREATE TABLE pairing_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    action TEXT NOT NULL, -- 'added', 'removed', 'failed'
    timestamp INTEGER NOT NULL,
    details TEXT
);

-- Security Events (for monitoring and analysis)
CREATE TABLE security_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_type TEXT NOT NULL, -- 'auth_failed', 'key_rotation', 'device_removed', 'suspicious_activity'
    device_id TEXT,
    ip_address TEXT,
    timestamp INTEGER NOT NULL,
    details TEXT
);

-- Key Versions (for key rotation management)
CREATE TABLE key_versions (
    version INTEGER PRIMARY KEY,
    group_id TEXT NOT NULL,
    key_hash TEXT NOT NULL,     -- Store hash, not actual key
    valid_from INTEGER NOT NULL,
    valid_until INTEGER,
    FOREIGN KEY (group_id) REFERENCES trust_groups(group_id)
);
```

### Platform Considerations

#### Android
- Use Android Keystore for key storage
- Consider using Nearby Connections API for pairing
- Handle Doze mode for clipboard sync
- Clipboard monitoring restrictions in Android 12+

#### iOS
- Use iOS Keychain for secure storage
- Implement proper background modes
- Consider iOS 16+ proximity permissions
- Clipboard access requires user permission

#### Desktop
- Platform-specific secure storage (Keychain/Credential Manager)
- System tray integration for background sync
- Handle sleep/wake for sync
- Consider security software blocking clipboard monitoring

### Performance Optimizations

1. **Discovery Performance:**
   - Implement async trust validation to avoid blocking mDNS discovery
   - Cache trust status for faster subsequent connections
   - Use connection pooling for trusted devices

2. **Cryptographic Performance:**
   - Consider ChaCha20-Poly1305 for better mobile performance than AES-256
   - Cache HMAC computations for repeated device interactions
   - Use hardware acceleration where available

3. **Battery Optimization:**
   - Configurable clipboard sync intervals (default: 30 seconds minimum)
   - Batch sync operations to reduce wake-ups
   - Use platform-specific power-efficient APIs

## Alternative: Hybrid Approach

We could also implement both options:
1. Start with decentralized approach (works offline)
2. Add optional cloud sync later
3. Cloud sync would backup group keys (encrypted)
4. Allows device recovery and cross-network sync

## Security Considerations

1. **Key Compromise:**
   - If group key is compromised, need to rotate
   - Implement "remove device" that triggers key rotation
   - Forward secrecy implemented through ephemeral keys in pairing

2. **Device Theft:**
   - Remote device removal capability
   - Optional biometric lock for app
   - Encrypted storage of keys
   - Time-based access expiry for extra security

3. **Network Attacks:**
   - Pairing only works on same network (reduces attack surface)
   - All traffic encrypted even on local network
   - Certificate pinning for any cloud features
   - Rate limiting on pairing attempts to prevent spam

## Backup and Recovery

### Export/Import Functionality
1. **Secure Export:**
   - Export group keys encrypted with user-chosen password
   - Use PBKDF2 for password-based encryption
   - Include device list and trust relationships
   - Format: Encrypted JSON with version info

2. **Recovery Options:**
   - Import from encrypted backup file
   - Recovery codes (one-time use) for emergency access
   - Optional cloud backup (encrypted) in hybrid approach

3. **Recovery Process:**
   ```kotlin
   data class TrustBackup(
       val version: Int,
       val exportDate: Long,
       val encryptedData: ByteArray,  // Contains group keys and device list
       val salt: ByteArray,
       val iterations: Int = 100000   // PBKDF2 iterations
   )
   ```

## Error Handling Strategy

### Network Failures
- Retry logic with exponential backoff for pairing
- Offline queueing for pending operations
- Clear error messages for users

### Corrupted Data
- Integrity checks on trust database
- Automatic backup before modifications
- Recovery mode for corrupted databases

### Key Conflicts
- Version-based conflict resolution
- Automatic key rotation on conflicts
- User notification for manual intervention

### Partial Group Lists
- Eventual consistency model
- Periodic sync with other group members
- Conflict resolution based on timestamps

## Testing Strategy

### Security Testing
1. **Penetration Testing:**
   - Test pairing protocol against MITM attacks
   - Attempt replay attacks with captured tokens
   - Test key extraction from device storage

2. **Cryptographic Validation:**
   - Verify proper random number generation
   - Test key derivation functions
   - Validate HMAC implementations

3. **Fuzzing:**
   - Fuzz message parsing logic
   - Test malformed pairing requests
   - Validate input sanitization

### Functional Testing
1. **Cross-Platform Pairing:**
   - Test all platform combinations (Android↔iOS, Desktop↔Mobile, etc.)
   - Verify UI consistency across platforms
   - Test different pairing methods (QR, PIN, NFC)

2. **Network Resilience:**
   - Test pairing with packet loss
   - Verify timeout handling
   - Test concurrent pairing attempts

3. **Performance Testing:**
   - Large group handling (10+ devices)
   - Rapid key rotation scenarios
   - Clipboard sync under load

4. **Edge Cases:**
   - Device name conflicts
   - Clock synchronization issues
   - Storage limitations

## User Control and Permissions

### Granular Permissions
1. **Trust Levels:**
   - Full trust: File sharing + clipboard sync
   - File sharing only
   - Read-only access (receive files but not send)

2. **Temporary Trust:**
   - Time-limited device access (1 hour, 1 day, 1 week)
   - Automatic removal after expiry
   - Option to make permanent

3. **Per-Device Settings:**
   ```kotlin
   data class DeviceTrustSettings(
       val deviceId: String,
       val trustLevel: TrustLevel,
       val permissions: Set<Permission>,
       val expiresAt: Long?,
       val clipboardSyncEnabled: Boolean
   )
   ```

## Next Steps

1. Decide on approach (recommend starting with decentralized)
2. Design detailed protocol specifications with security review
3. Create UI mockups for pairing flow with UX testing
4. Implement proof of concept with core security features
5. Security audit of implementation before production
6. Phased rollout with monitoring

## Open Questions

1. Should we support multiple trust groups per device?
2. How to handle group merging if user creates separate groups?
3. Should clipboard sync be opt-in or opt-out?
4. Do we need a "master device" or fully distributed?
5. How to handle device name conflicts?
6. Should we implement trust levels from the start or add later?
7. What's the maximum group size we should support?