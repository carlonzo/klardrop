# Klardrop Trust System Test Suite

This directory contains comprehensive tests for the Klardrop trusted device groups implementation.

## Test Coverage

### 1. CryptoProviderTest (`crypto/CryptoProviderTest.kt`)
Tests the cryptographic operations foundation:
- **Key Generation**: ECDSA, ECDH, and AES key generation
- **Digital Signatures**: ECDSA signing and verification with various edge cases
- **Key Exchange**: ECDH key exchange between two parties
- **Encryption**: AES-GCM encryption/decryption with authentication
- **Key Derivation**: HKDF key derivation with different inputs
- **Utilities**: Nonce generation, random bytes, SHA-256 hashing
- **Integration**: Combined workflows like ECDH + AES encryption

### 2. TrustStoreTest (`storage/TrustStoreTest.kt`)
Tests the persistence layer:
- **Device Keypair**: Storage and retrieval of device identity
- **Trust Groups**: Creation, updates, and cloud sync settings
- **Trusted Devices**: Add/remove/query operations with trust levels
- **Security Events**: Logging and retrieval of security-related events
- **Pairing Sessions**: Session management with expiration
- **Clipboard Sync**: Entry storage, sync tracking, and cleanup
- **Maintenance**: Cleanup of expired devices, old events, and entries

### 3. TrustProtocolHandlerTest (`protocol/TrustProtocolHandlerTest.kt`)
Tests the protocol message handling:
- **Discovery**: Announcement creation and verification
- **Pairing**: ECDH-based pairing initiation and response
- **Authentication**: Message signature verification
- **Member Updates**: Broadcasting and handling device additions/removals
- **Clipboard Sync**: Secure clipboard content distribution
- **Trust Events**: Event emission and handling

### 4. TrustManagerTest (`TrustManagerTest.kt`)
Tests the high-level trust management:
- **Initialization**: Device keypair generation and persistence
- **Trust Groups**: Creation and management
- **Device Management**: Trust status checks and updates
- **Discovery**: Announcement generation
- **Pairing**: Initiation workflow
- **Clipboard**: Sync coordination
- **Observables**: Reactive updates to trusted devices

### 5. Integration Tests (`integration/TrustIntegrationTest.kt`)
End-to-end workflow tests:
- **Full Pairing Workflow**: Complete device pairing from discovery to trust establishment
- **Clipboard Sync**: Multi-device clipboard synchronization
- **Member Propagation**: Trust group updates across multiple devices
- **Security Logging**: Event tracking during security-sensitive operations
- **Key Rotation**: Group key updates and distribution
- **Device Removal**: Revocation and cleanup
- **Expiration**: Automatic cleanup of expired devices

## Test Infrastructure

### Mock Implementations
- `FakeSecureKeyStorage`: In-memory secure key storage
- `FakeTrustDatabase`: In-memory database with full query support
- `FakeTrustStore`: Simplified trust store for isolated testing
- `TestDevice`: Complete device simulation for integration tests

### Test Utilities
- `TestCoroutines`: Coroutine test dispatcher and scope management
- `turbine`: Flow testing with assertions
- Protocol message builders and parsers

## Key Testing Patterns

1. **Isolation**: Each component is tested in isolation with mocked dependencies
2. **Integration**: Full workflow tests verify component interactions
3. **Security**: Extensive testing of cryptographic operations and attack scenarios
4. **Async**: Proper testing of coroutines and flows
5. **Edge Cases**: Handling of errors, timeouts, and malformed data

## Running Tests

To run the tests:
```bash
./gradlew :common:jvmTest -q
```

## Future Enhancements

1. **Performance Tests**: Measure crypto operations and sync performance
2. **Stress Tests**: Multiple devices, large clipboard entries
3. **Platform Tests**: iOS/Android specific implementations
4. **Network Tests**: Handling of network failures and retries