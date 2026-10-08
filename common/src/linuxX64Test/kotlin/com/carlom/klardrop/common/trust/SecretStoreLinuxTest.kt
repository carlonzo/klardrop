@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.trust.secretstore.EncryptedFileSecretStore
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.*
import kotlin.random.Random
import kotlin.test.*

class SecretStoreLinuxTest {

  private fun createTempDir(): String {
    val tempDir = "/tmp/klardrop_test_${Random.nextLong()}"
    mkdir(tempDir, (S_IRWXU).toUInt())
    return tempDir
  }

  private fun removeDir(dir: String) {
    val dirPtr = opendir(dir) ?: return
    try {
      while (true) {
        val entry = readdir(dirPtr) ?: break
        val name = entry.pointed.d_name.toKString()
        if (name != "." && name != "..") {
          unlink("$dir/$name")
        }
      }
    } finally {
      closedir(dirPtr)
      rmdir(dir)
    }
  }

  @Test
  fun testEncryptedFileSecretStoreRoundTripAndPermissions() {
    val tempDir = createTempDir()
    try {
      val store = EncryptedFileSecretStore(tempDir)
      val secret = "super-secret-key-material-12345".encodeToByteArray()
      val account = "device-private-key"

      store.put(account, secret)

      val retrieved = store.get(account)
      assertNotNull(retrieved)
      assertTrue(secret.contentEquals(retrieved), "Retrieved secret must match stored secret")

      // Verify file permissions 0600 (0x180 = 0600 octal)
      val filePath = "$tempDir/$account.aes"
      memScoped {
        val fileStat = alloc<stat>()
        assertEquals(0, stat(filePath, fileStat.ptr), "Stat on secret file must succeed")
        val fileMode = fileStat.st_mode and 0x1FFu
        assertEquals(0x180u, fileMode, "Secret file permissions must be 0600 (got ${fileMode.toString(8)})")

        // Verify directory permissions 0700 (0x1C0 = 0700 octal)
        val dirStat = alloc<stat>()
        assertEquals(0, stat(tempDir, dirStat.ptr), "Stat on directory must succeed")
        val dirMode = dirStat.st_mode and 0x1FFu
        assertEquals(0x1C0u, dirMode, "Directory permissions must be 0700 (got ${dirMode.toString(8)})")
      }

      // Test deletion
      store.delete(account)
      assertNull(store.get(account))
    } finally {
      removeDir(tempDir)
    }
  }

  @Test
  fun testLinuxTrustStorageRoundTrip() = kotlinx.coroutines.runBlocking {
    val tempDir = createTempDir()
    try {
      val secretStore = EncryptedFileSecretStore(tempDir)
      val storage = LinuxTrustStorage(tempDir, secretStore)

      val deviceId = "device-abc-123"
      val ecdhKey = "ecdh-public-key-bytes".encodeToByteArray()
      val ecdsaKey = "ecdsa-public-key-bytes".encodeToByteArray()
      val sharedSecret = "shared-secret-bytes-32".encodeToByteArray()
      val privKey = "device-private-key-bytes".encodeToByteArray()
      val pubKey = "device-public-key-bytes".encodeToByteArray()

      // Device identity
      storage.storeDevicePrivateKey(privKey)
      assertTrue(storage.hasDeviceKey())
      val retrievedPrivKey = storage.getDevicePrivateKey()
      assertNotNull(retrievedPrivKey)
      assertTrue(privKey.contentEquals(retrievedPrivKey))

      storage.storeDevicePublicKey(pubKey)
      val retrievedPubKey = storage.getDevicePublicKey()
      assertNotNull(retrievedPubKey)
      assertTrue(pubKey.contentEquals(retrievedPubKey))

      // Trusted device & keys
      storage.storeTrustedDevice(deviceId, ecdhKey)
      storage.storeECDSAKey(deviceId, ecdsaKey)
      storage.storeSharedSecret(deviceId, sharedSecret)

      val retrievedEcdh = storage.getTrustedDeviceKey(deviceId)
      assertNotNull(retrievedEcdh)
      assertTrue(ecdhKey.contentEquals(retrievedEcdh))

      val retrievedEcdsa = storage.getECDSAKey(deviceId)
      assertNotNull(retrievedEcdsa)
      assertTrue(ecdsaKey.contentEquals(retrievedEcdsa))

      val retrievedShared = storage.getSharedSecret(deviceId)
      assertNotNull(retrievedShared)
      assertTrue(sharedSecret.contentEquals(retrievedShared))

      val allDevices = storage.getAllTrustedDevices()
      assertEquals(1, allDevices.size)
      assertTrue(ecdhKey.contentEquals(allDevices[deviceId]))

      // Check properties files permissions 0600
      memScoped {
        val s = alloc<stat>()
        for (f in listOf("trusted_devices.properties", "ecdsa_keys.properties", "shared_secrets.properties", "device_private_key.properties")) {
          assertEquals(0, stat("$tempDir/$f", s.ptr))
          val mode = s.st_mode and 0x1FFu
          assertEquals(0x180u, mode, "$f permissions must be 0600 (got ${mode.toString(8)})")
        }
      }

      // Remove device
      storage.removeTrustedDevice(deviceId)
      assertNull(storage.getTrustedDeviceKey(deviceId))
      assertNull(storage.getECDSAKey(deviceId))
      assertNull(storage.getSharedSecret(deviceId))

      // Delete device key
      storage.deleteDevicePrivateKey()
      assertFalse(storage.hasDeviceKey())
      assertNull(storage.getDevicePrivateKey())
      assertNull(storage.getDevicePublicKey())
    } finally {
      removeDir(tempDir)
    }
  }

  @Test
  fun testCrossImplementationEncryptedFileSecretStoreCompatibility() {
    val tempDir = createTempDir()
    try {
      // Produced by desktopJvm EncryptedFileSecretStore with passphrase "alice\u0000Linux"
      // (NUL separator — matching the JVM defaultPassphrase() at HEAD).
      // Plaintext: "klardrop-compat-test-secret"
      val jvmKaesHex = "4b41455301edb88d1788c4baebf730a2602c39b48015c02634492ccc5cd314adbc0745cdd5ab05dc5fd5eab21ab236e3ffbbf20817d6cf10f2ab9c141f7a501b020939e4f746fb8e3b849e02"
      val blobBytes = hexToByteArray(jvmKaesHex)
      val filePath = "$tempDir/compat-secret.aes"
      com.carlom.klardrop.common.utils.writeFile0600(filePath, blobBytes)

      val store = EncryptedFileSecretStore(tempDir, passphrase = "alice\u0000Linux")
      val decrypted = store.get("compat-secret")
      assertNotNull(decrypted, "Decryption of JVM KAES blob must succeed")
      assertEquals(
        "klardrop-compat-test-secret",
        decrypted.decodeToString(),
        "Decrypted plaintext must match the original JVM plaintext",
      )
    } finally {
      removeDir(tempDir)
    }
  }

  @Test
  fun testCrossImplementationDesktopTrustStorageCompatibility() = kotlinx.coroutines.runBlocking {
    val tempDir = createTempDir()
    try {
      val trustedDevicesContent = """
        #Klardrop Trusted Devices - Do not manually edit this file
        #Fri Sep 25 21:05:12 GMT+04:00 2026
        device-jvm-fixture-42=Zml4dHVyZS1lY2RoLWtleS1tYXRlcmlhbC1ieXRlcy0x
      """.trimIndent()
      val ecdsaKeysContent = """
        #Klardrop ECDSA Keys - Do not manually edit this file
        #Fri Sep 25 21:05:12 GMT+04:00 2026
        device-jvm-fixture-42=Zml4dHVyZS1lY2RzYS1rZXktbWF0ZXJpYWwtYnl0ZXMtMg\=\=
      """.trimIndent()
      val sharedSecretsContent = """
        #Klardrop Shared Secrets - Do not manually edit this file
        #Fri Sep 25 21:05:12 GMT+04:00 2026
        device-jvm-fixture-42=Zml4dHVyZS1zaGFyZWQtc2VjcmV0LWJ5dGVzLTM\=
      """.trimIndent()
      val deviceKeyPropsContent = """
        #Klardrop Device Identity - Do not manually edit this file
        #Fri Sep 25 21:05:12 GMT+04:00 2026
        device_public_key=Zml4dHVyZS1kZXYtcHVia2V5LWJ5dGVzLTQ\=
      """.trimIndent()
      // Produced by desktopJvm EncryptedFileSecretStore with passphrase "alice\u0000Linux"
      // (NUL separator — matching the JVM defaultPassphrase() at HEAD).
      val devPrivKeyHex = "4b414553014a37d65b33a9db0eeaae70932f3e0de91e53f05d7ae168a322ec2ba587d1c9655348a3e81b4fd75e065fe7d46706b813296741e912b040fbd1a7f6b0d6eb1e09a183d7da286b92"

      com.carlom.klardrop.common.utils.writeFile0600("$tempDir/trusted_devices.properties", trustedDevicesContent)
      com.carlom.klardrop.common.utils.writeFile0600("$tempDir/ecdsa_keys.properties", ecdsaKeysContent)
      com.carlom.klardrop.common.utils.writeFile0600("$tempDir/shared_secrets.properties", sharedSecretsContent)
      com.carlom.klardrop.common.utils.writeFile0600("$tempDir/device_private_key.properties", deviceKeyPropsContent)
      com.carlom.klardrop.common.utils.writeFile0600("$tempDir/device-private-key.aes", hexToByteArray(devPrivKeyHex))

      val secretStore = EncryptedFileSecretStore(tempDir, passphrase = "alice\u0000Linux")
      val storage = LinuxTrustStorage(tempDir, secretStore)

      val deviceId = "device-jvm-fixture-42"
      val expectedEcdh = "fixture-ecdh-key-material-bytes-1".encodeToByteArray()
      val expectedEcdsa = "fixture-ecdsa-key-material-bytes-2".encodeToByteArray()
      val expectedShared = "fixture-shared-secret-bytes-3".encodeToByteArray()
      val expectedPubKey = "fixture-dev-pubkey-bytes-4".encodeToByteArray()
      val expectedPrivKey = "fixture-dev-privkey-bytes-5".encodeToByteArray()

      // Verify trusted device and keys loaded identically
      val loadedEcdh = storage.getTrustedDeviceKey(deviceId)
      assertNotNull(loadedEcdh)
      assertContentEquals(expectedEcdh, loadedEcdh)

      val allDevices = storage.getAllTrustedDevices()
      assertEquals(1, allDevices.size)
      assertContentEquals(expectedEcdh, allDevices[deviceId])

      val loadedEcdsa = storage.getECDSAKey(deviceId)
      assertNotNull(loadedEcdsa)
      assertContentEquals(expectedEcdsa, loadedEcdsa)

      val loadedShared = storage.getSharedSecret(deviceId)
      assertNotNull(loadedShared)
      assertContentEquals(expectedShared, loadedShared)

      // Verify device identity loaded identically
      val loadedPubKey = storage.getDevicePublicKey()
      assertNotNull(loadedPubKey)
      assertContentEquals(expectedPubKey, loadedPubKey)

      assertTrue(storage.hasDeviceKey())
      val loadedPrivKey = storage.getDevicePrivateKey()
      assertNotNull(loadedPrivKey)
      assertContentEquals(expectedPrivKey, loadedPrivKey)
    } finally {
      removeDir(tempDir)
    }
  }

  private fun hexToByteArray(hex: String): ByteArray {
    val cleanHex = hex.trim()
    val result = ByteArray(cleanHex.length / 2)
    for (i in result.indices) {
      result[i] = cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return result
  }
}
