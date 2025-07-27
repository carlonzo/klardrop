# Device Addition to Trust Group - Step-by-Step Flow

This document provides a detailed explanation of how devices are added to trusted groups in Klardrop, based on the implementation in PR #279.

## Overview

The device addition process is a secure, multi-phase protocol that ensures:
- Forward secrecy through ephemeral ECDH keys
- Two-phase commit for trust establishment
- Strong cryptographic guarantees
- User consent at every step
- Audit trail for security events

## Phase 1: Discovery and Initial Contact

### 1. mDNS Discovery with Trust Status
- Each device broadcasts its discovery announcement including trust group membership status
- `TrustProtocolHandler.createDiscoveryAnnouncement()` creates a signed announcement with device public key
- The announcement indicates if the device is in a trust group and supports auto-trust
- **Location**: `TrustProtocolHandler.kt:80-99`

### 2. Discovery Processing
- When Device A discovers Device B, it receives the discovery announcement
- `TrustProtocolHandler.handleDiscoveryAnnouncement()` verifies the ECDSA signature
- Device A checks if Device B is already trusted via `TrustStore.isDeviceTrusted()`
- **Location**: `TrustProtocolHandler.kt:101-144`

## Phase 2: Pairing Initiation

### 3. User Initiates Pairing
- User on Device A selects Device B for pairing
- `TrustManager.initiatePairing(deviceB_id)` is called
- Device A ensures it has a trust group (creates one if needed via `getOrCreateTrustGroup()`)
- **Location**: `TrustManager.kt:218-223`

### 4. ECDH Key Exchange Setup
- Device A generates ephemeral ECDH keypair for forward secrecy
- A `PairingSession` is created in the database with 5-minute expiration
- Device A encrypts its group ID with Device B's public key
- Sends `ECDHInitiation` message with ephemeral public key and encrypted group ID
- **Location**: `TrustProtocolHandler.kt:146-208`

**Database Changes:**
```sql
INSERT INTO pairing_sessions (session_id, device_id, ephemeral_public_key, expires_at, status, created_at)
VALUES (sessionId, deviceB_id, ephemeralPublicKey, expirationTime, 'PENDING', currentTime)
```

## Phase 3: Secure Key Exchange

### 5. Device B Receives Initiation
- `TrustProtocolHandler.handleECDHInitiation()` verifies the signature
- Device B generates its own ephemeral ECDH keypair
- Computes shared secret using ECDH: `computeECDHSecret(privateKey, peerPublicKey)`
- Derives encryption key using HKDF with the shared secret
- **Location**: `TrustProtocolHandler.kt:210-292`

**Cryptographic Operations:**
```kotlin
val sharedSecret = cryptoProvider.computeECDHSecret(ephemeralPrivateKey, peerEphemeralPublicKey)
val encryptionKey = cryptoProvider.deriveKey(
    secret = sharedSecret,
    salt = "klardrop-trust-v1".toByteArray(),
    info = sessionId.toByteArray()
)
```

### 6. Device B Responds
- Device B encrypts its device info (ID, name, type, capabilities) with the derived key
- Sends `ECDHResponse` with its ephemeral public key and encrypted device info
- Emits `TrustEvent.PairingRequest` for UI to show approval dialog
- **Location**: `TrustProtocolHandler.kt:293-315`

## Phase 4: Group Invitation and Trust Establishment

### 7. Device A Processes Response
- `handleECDHResponse()` decrypts Device B's info using the shared secret
- Device A encrypts its complete trust group information (group key, member list, etc.)
- Sends `GroupInvitation` with encrypted group data
- **Location**: `TrustProtocolHandler.kt:317-321`

### 8. User Approval on Device B
- Device B's UI shows pairing request with Device A's information
- User approves the pairing request
- `handleGroupInvitation()` decrypts the group information
- Device B saves the trust group to its database via `TrustStore.saveTrustGroup()`
- **Location**: `TrustProtocolHandler.kt:323-372`

**Database Changes on Device B:**
```sql
-- Save trust group
INSERT OR REPLACE INTO trust_groups (group_id, group_key, group_name, created_at, updated_at, protocol_version, is_active)
VALUES (groupId, groupKey, groupName, createdAt, updatedAt, protocolVersion, 1)

-- Save all existing members
INSERT OR REPLACE INTO trusted_devices (device_id, group_id, public_key, device_name, device_type, added_at, added_by, trust_level, permissions, is_active)
VALUES (memberId, groupId, memberPublicKey, memberName, memberType, memberAddedAt, memberAddedBy, trustLevel, permissions, 1)
```

### 9. Confirmation and Finalization
- Device B sends `JoinConfirmation(accepted=true)` to Device A
- Device A receives confirmation and adds Device B to its trusted devices list
- Database is updated with the new trusted device relationship
- **Location**: `TrustProtocolHandler.kt:374-414`

**Database Changes on Device A:**
```sql
-- Add Device B as trusted
INSERT OR REPLACE INTO trusted_devices (device_id, group_id, public_key, device_name, device_type, added_at, added_by, trust_level, permissions, is_active)
VALUES (deviceB_id, groupId, deviceB_publicKey, deviceB_name, deviceB_type, currentTime, deviceA_id, 'FULL', permissions, 1)

-- Update pairing session status
UPDATE pairing_sessions SET status = 'ACCEPTED' WHERE session_id = ?
```

## Phase 5: Group Synchronization

### 10. Member Update Propagation
- Device A broadcasts `MemberUpdate(ADD, deviceB)` to all existing group members
- Each existing member receives the update and adds Device B to their trusted devices
- This ensures the entire group knows about the new member
- **Location**: `TrustProtocolHandler.kt:466-501`

### 11. State Synchronization
- All devices update their local state flows (`_trustedDevices`, `_currentTrustGroup`)
- UI components are notified of the new trust relationships
- Device B now has access to auto-accept file transfers and clipboard sync
- **Location**: `TrustManager.kt:330-347`

## Key Security Features

### Forward Secrecy
- Ephemeral ECDH keys ensure past communications remain secure even if long-term keys are compromised
- Keys are generated fresh for each pairing session and discarded after use

### Two-Phase Commit
- Both devices must explicitly agree before trust is established
- Either device can abort the process at any time
- No partial trust states exist

### Signature Verification
- All messages are signed with ECDSA to prevent impersonation
- Public keys are verified against device identity
- Replay attacks prevented with timestamps and nonces

### Encryption
- Group information is encrypted during transmission using AES-GCM
- Shared secrets derived using HKDF for key separation
- Different keys used for different purposes

### Audit Trail
- All security events are logged in the `security_events` table
- Failed authentication attempts are recorded
- Pairing attempts and outcomes are tracked

### Expiration
- Pairing sessions expire after 5 minutes to prevent stale connections
- Expired sessions are automatically cleaned up
- Database maintenance prevents resource leaks

## Database Schema Changes During Addition

### Device A's Database
```sql
-- Update trusted devices
INSERT INTO trusted_devices (...) VALUES (deviceB_info)

-- Log security events
INSERT INTO security_events (event_type, device_id, timestamp, details)
VALUES ('DEVICE_ADDED', deviceB_id, currentTime, deviceB_details)

-- Update pairing session
UPDATE pairing_sessions SET status = 'ACCEPTED' WHERE session_id = sessionId
```

### Device B's Database
```sql
-- Save trust group
INSERT INTO trust_groups (...) VALUES (groupInfo)

-- Add all existing members
INSERT INTO trusted_devices (...) VALUES (member1_info), (member2_info), ...

-- Log pairing event
INSERT INTO security_events (event_type, device_id, timestamp, details)
VALUES ('JOINED_GROUP', deviceA_id, currentTime, groupInfo)
```

### Other Group Members' Databases
```sql
-- Add new member
INSERT INTO trusted_devices (...) VALUES (deviceB_info)

-- Update last seen for active devices
UPDATE trusted_devices SET last_seen = currentTime WHERE device_id IN (activeDevices)
```

## Error Handling

### Network Failures
- Pairing sessions have built-in timeouts
- Retry mechanisms for critical messages
- Graceful degradation when devices go offline

### Cryptographic Failures
- Signature verification failures are logged and ignored
- Decryption failures abort the pairing process
- Invalid key exchanges are rejected

### Database Conflicts
- Upsert operations handle concurrent updates
- Foreign key constraints ensure data integrity
- Transaction rollback on partial failures

## Performance Considerations

### Lazy Initialization
- Cryptographic operations are performed only when needed
- Database connections are pooled and reused
- UI updates are debounced to prevent flickering

### Efficient Propagation
- Member updates are batched when possible
- Only active devices receive broadcasts
- Compression used for large group information

### Resource Management
- Ephemeral keys are cleared from memory after use
- Database cleanup runs periodically
- Connection pooling prevents resource exhaustion

## Testing Requirements

### Unit Tests
- Cryptographic operation correctness
- Database schema migrations
- Message serialization/deserialization
- Error handling paths

### Integration Tests
- Cross-platform device pairing
- Network failure scenarios
- Concurrent pairing attempts
- Large group management

### Security Tests
- Key compromise scenarios
- Replay attack prevention
- Man-in-the-middle resistance
- Timing attack mitigation

This comprehensive flow ensures that device addition to trust groups is secure, reliable, and user-friendly while maintaining strong cryptographic guarantees throughout the process.