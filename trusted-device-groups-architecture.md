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
    val groupName: String           // Optional user-defined name
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
   - Pairing token displayed as QR code or short code
   
3. **Secure Key Exchange:**
   ```
   Device A → Device B: {
     pairingToken: String,
     deviceAPublicKey: ByteArray,
     challenge: ByteArray
   }
   
   Device B → Device A: {
     deviceBPublicKey: ByteArray,
     challengeResponse: ByteArray,
     deviceInfo: DeviceIdentity
   }
   
   Device A → Device B: {
     groupKey: ByteArray,        // Encrypted with Device B's public key
     groupMembers: List<DeviceIdentity>,
     groupId: String
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
   - AES-256 symmetric key for group
   - Key rotation on device removal
   - Keys stored in platform keychain/keystore

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
- [ ] Create clipboard sync protocol
- [ ] Add sync preferences
- [ ] Implement background sync
- [ ] Handle conflicts/timestamps

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
```

### Platform Considerations

#### Android
- Use Android Keystore for key storage
- Consider using Nearby Connections API for pairing
- Handle Doze mode for clipboard sync

#### iOS
- Use iOS Keychain for secure storage
- Implement proper background modes
- Consider iOS 16+ proximity permissions

#### Desktop
- Platform-specific secure storage (Keychain/Credential Manager)
- System tray integration for background sync
- Handle sleep/wake for sync

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
   - Consider forward secrecy for future versions

2. **Device Theft:**
   - Remote device removal capability
   - Optional biometric lock for app
   - Encrypted storage of keys

3. **Network Attacks:**
   - Pairing only works on same network (reduces attack surface)
   - All traffic encrypted even on local network
   - Certificate pinning for any cloud features

## Next Steps

1. Decide on approach (recommend starting with decentralized)
2. Design detailed protocol specifications
3. Create UI mockups for pairing flow
4. Implement proof of concept
5. Security audit of implementation

## Open Questions

1. Should we support multiple trust groups per device?
2. How to handle group merging if user creates separate groups?
3. Should clipboard sync be opt-in or opt-out?
4. Do we need a "master device" or fully distributed?
5. How to handle device name conflicts?