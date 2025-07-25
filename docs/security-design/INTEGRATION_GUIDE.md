# Klardrop Cloud Transfer Security Integration Guide

## Overview

This guide provides step-by-step instructions for integrating the cloud transfer security model into the existing Klardrop codebase.

## Integration Points

### 1. Dependency Injection Setup

Update the `CommonComponent` to include security services:

```kotlin
// common/src/commonMain/kotlin/com/carlom/klardrop/common/di/CommonComponent.kt

@Component(modules = [
    CommunicationModule::class,
    DiscoveryModule::class,
    StorageModule::class,
    UtilsModule::class,
    SecurityModule::class  // New module
])
interface CommonComponent {
    // Existing injections...
    
    // Security services
    fun authenticationService(): AuthenticationService
    fun encryptionService(): EncryptionService
    fun deviceManagementService(): DeviceManagementService
    fun mqttClient(): SecureMqttClient
    fun secureTransferProtocol(): SecureFileTransferProtocol
}
```

### 2. Security Module Configuration

```kotlin
// common/src/commonMain/kotlin/com/carlom/klardrop/common/security/di/SecurityModule.kt

@Module
class SecurityModule {
    @Provides
    @Singleton
    fun provideSecurityConfig(): SecurityConfig {
        return SecurityConfig(
            authEndpoint = BuildConfig.AUTH_ENDPOINT,
            mqttBroker = BuildConfig.MQTT_BROKER,
            certificatePinning = true
        )
    }
    
    @Provides
    @Singleton
    fun provideAuthenticationService(
        config: SecurityConfig,
        httpClient: HttpClient,
        cryptoProvider: CryptoProvider,
        clock: Clock
    ): AuthenticationService {
        return AuthenticationService(
            authEndpoint = config.authEndpoint,
            httpClient = httpClient,
            cryptoProvider = cryptoProvider,
            clock = clock
        )
    }
    
    @Provides
    @Singleton
    fun provideMqttClient(
        config: SecurityConfig,
        authService: AuthenticationService,
        cryptoProvider: CryptoProvider,
        clock: Clock
    ): SecureMqttClient {
        return SecureMqttClient(
            config = MqttConfig(brokerUrl = config.mqttBroker),
            authService = authService,
            cryptoProvider = cryptoProvider,
            clock = clock
        )
    }
}
```

### 3. Update Klardrop Main Class

```kotlin
// common/src/commonMain/kotlin/com/carlom/klardrop/common/Klardrop.kt

class Klardrop(
    private val server: Server,
    private val discoveryNetwork: DiscoveryNetwork,
    private val securityModule: KlardropSecurityModule,  // New
    private val mqttClient: SecureMqttClient  // New
) {
    suspend fun start() {
        // Initialize security
        securityModule.initialize()
        
        // Existing initialization...
        val serverConfig = server.startServer()
        discoveryNetwork.startDiscovery(serverConfig.port)
        
        // Connect to MQTT for cloud transfers
        if (securityModule.isCloudTransferEnabled()) {
            connectToCloud()
        }
    }
    
    private suspend fun connectToCloud() {
        val deviceCredentials = securityModule.getDeviceCredentials()
        mqttClient.connect(deviceCredentials)
        
        // Start listening for cloud transfers
        listenForCloudTransfers()
    }
}
```

### 4. Extend Discovery to Support Cloud Devices

```kotlin
// common/src/commonMain/kotlin/com/carlom/klardrop/common/discovery/DiscoveryNetwork.kt

class DiscoveryNetwork(
    // Existing dependencies...
    private val mqttClient: SecureMqttClient,
    private val deviceManager: DeviceManagementService
) {
    suspend fun discoverDevices(): Flow<DiscoveryDevice> = merge(
        discoverLocalDevices(),  // Existing mDNS discovery
        discoverCloudDevices()   // New cloud discovery
    )
    
    private fun discoverCloudDevices(): Flow<DiscoveryDevice> {
        return deviceManager.trustedDevices
            .map { devices ->
                devices.filter { it.trustLevel != TrustLevel.REVOKED }
                    .map { device ->
                        DiscoveryDevice(
                            deviceInfo = DeviceInfo(
                                deviceId = device.deviceId,
                                name = device.deviceName,
                                deviceType = device.deviceType
                            ),
                            deviceConnections = listOf(
                                DeviceConnection.CloudConnection(
                                    address = "cloud",
                                    port = 0,
                                    isOnline = checkDeviceOnline(device.deviceId)
                                )
                            )
                        )
                    }
            }
            .flatMapLatest { it.asFlow() }
    }
}
```

### 5. Update File Transfer Logic

```kotlin
// common/src/commonMain/kotlin/com/carlom/klardrop/common/communication/Client.kt

class Client(
    // Existing dependencies...
    private val secureTransferProtocol: SecureFileTransferProtocol
) {
    suspend fun sendFile(
        deviceId: String,
        file: PlatformFile
    ): MessengerSendProgress {
        val device = visibleDevices.getDevice(deviceId)
            ?: throw IllegalArgumentException("Device not found")
        
        return when {
            device.hasLocalConnection() -> {
                // Existing local transfer logic
                sendFileLocally(device, file)
            }
            device.hasCloudConnection() -> {
                // New cloud transfer logic
                sendFileViaCloud(device, file)
            }
            else -> throw IllegalStateException("No connection available")
        }
    }
    
    private suspend fun sendFileViaCloud(
        device: DiscoveryDevice,
        file: PlatformFile
    ): MessengerSendProgress {
        val registeredDevice = deviceManager.trustedDevices.value
            .find { it.deviceId == device.deviceInfo.deviceId }
            ?: throw SecurityException("Device not trusted for cloud transfer")
        
        val result = secureTransferProtocol.initiateTransfer(
            file = file,
            recipient = registeredDevice
        )
        
        return when (result) {
            is TransferResult.Success -> MessengerSendProgress.Completed
            is TransferResult.Failed -> MessengerSendProgress.Failed(result.reason)
            is TransferResult.Rejected -> MessengerSendProgress.Failed("Transfer rejected")
        }
    }
}
```

### 6. Platform-Specific Crypto Implementation

```kotlin
// common/src/androidMain/kotlin/com/carlom/klardrop/common/security/AndroidCryptoProvider.kt

class AndroidCryptoProvider : CryptoProvider {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    
    override suspend fun generateEphemeralKeyPair(): EphemeralKeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore"
        )
        
        val parameterSpec = KeyGenParameterSpec.Builder(
            "klardrop_ephemeral_${System.currentTimeMillis()}",
            KeyProperties.PURPOSE_AGREE_KEY
        ).apply {
            setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            setUserAuthenticationRequired(false)
        }.build()
        
        keyPairGenerator.initialize(parameterSpec)
        val keyPair = keyPairGenerator.generateKeyPair()
        
        return EphemeralKeyPair(
            privateKey = keyPair.private.encoded,
            publicKey = keyPair.public.encoded
        )
    }
    
    override suspend fun createAESGCMCipher(key: ByteArray): AESGCMCipher {
        return AndroidAESGCMCipher(key)
    }
}
```

### 7. UI Integration

```kotlin
// common-ui/src/commonMain/kotlin/com/carlom/klardrop/discovery_screen.kt

@Composable
fun DiscoveryScreen(
    // Existing parameters...
    onDevicePairingRequested: (DeviceInfo, PairingMethod) -> Unit,
    onCloudTransferToggled: (Boolean) -> Unit
) {
    Column {
        // Cloud transfer toggle
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Enable Cloud Transfers")
            Spacer(Modifier.weight(1f))
            Switch(
                checked = isCloudTransferEnabled,
                onCheckedChange = onCloudTransferToggled
            )
        }
        
        // Device list with cloud status
        LazyColumn {
            items(devices) { device ->
                DeviceCard(
                    device = device,
                    isCloudDevice = device.hasCloudConnection(),
                    isOnline = device.isOnline(),
                    onPairClick = {
                        if (!device.isTrusted()) {
                            showPairingDialog(device)
                        }
                    }
                )
            }
        }
    }
}
```

### 8. Migration Strategy

#### Phase 1: Foundation (Week 1-2)
1. Implement platform-specific `CryptoProvider`
2. Set up authentication service
3. Add device management without UI

#### Phase 2: MQTT Integration (Week 3-4)
1. Integrate MQTT client library
2. Implement secure connection
3. Add presence tracking

#### Phase 3: File Transfer (Week 5-6)
1. Implement secure transfer protocol
2. Add progress tracking
3. Handle error cases

#### Phase 4: UI and Testing (Week 7-8)
1. Update UI for cloud features
2. Add pairing flows
3. Comprehensive testing

## Testing Strategy

### Unit Tests

```kotlin
class SecureFileTransferProtocolTest {
    @Test
    fun testKeyExchange() = runTest {
        val alice = createTestDevice("Alice")
        val bob = createTestDevice("Bob")
        
        val protocol = SecureFileTransferProtocol(
            mockMqttClient,
            mockEncryption,
            mockDeviceManager,
            mockFileManager,
            mockCrypto,
            mockClock
        )
        
        val result = protocol.initiateTransfer(
            file = createTestFile(),
            recipient = bob,
            options = TransferOptions()
        )
        
        assertTrue(result is TransferResult.Success)
    }
}
```

### Integration Tests

```kotlin
class CloudTransferIntegrationTest {
    @Test
    fun testEndToEndCloudTransfer() = runTest {
        // Set up two devices
        val sender = createAndAuthenticateDevice("Sender")
        val receiver = createAndAuthenticateDevice("Receiver")
        
        // Pair devices
        val pairingResult = sender.pairWith(receiver)
        assertTrue(pairingResult.success)
        
        // Transfer file
        val file = createLargeTestFile(10 * 1024 * 1024) // 10MB
        val result = sender.transferFile(file, receiver)
        
        assertEquals(TransferResult.Success::class, result::class)
        
        // Verify file received
        val receivedFile = receiver.getReceivedFile()
        assertEquals(file.size(), receivedFile.size())
        assertArrayEquals(file.checksum(), receivedFile.checksum())
    }
}
```

## Security Checklist

- [ ] All connections use TLS 1.3
- [ ] JWT tokens expire after 15 minutes
- [ ] Refresh tokens are stored securely
- [ ] Device private keys are hardware-backed where available
- [ ] Certificate pinning is enabled
- [ ] Rate limiting is implemented
- [ ] Audit logging is enabled
- [ ] Encryption keys are properly derived
- [ ] Perfect forward secrecy is ensured
- [ ] No sensitive data in logs
- [ ] Proper error handling without information leakage
- [ ] Regular security updates scheduled

## Monitoring and Observability

```kotlin
class SecurityMetrics {
    // Track authentication attempts
    val authAttempts = Counter("klardrop.auth.attempts")
    val authFailures = Counter("klardrop.auth.failures")
    
    // Track transfers
    val transfersInitiated = Counter("klardrop.transfers.initiated")
    val transfersCompleted = Counter("klardrop.transfers.completed")
    val transfersFailed = Counter("klardrop.transfers.failed")
    
    // Track encryption performance
    val encryptionDuration = Histogram("klardrop.encryption.duration")
    val decryptionDuration = Histogram("klardrop.decryption.duration")
}
```

This integration guide provides a clear path for implementing the security model while maintaining compatibility with the existing Klardrop architecture.