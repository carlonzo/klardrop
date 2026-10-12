@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.utils.sharedNativeIoDispatcher
import com.carlom.klardrop.common.trust.secretstore.SecretStore
import com.carlom.klardrop.common.trust.secretstore.pickSecretStore
import com.carlom.klardrop.common.utils.LinuxPaths
import com.carlom.klardrop.common.utils.ensureDirectory0700
import com.carlom.klardrop.common.utils.readFileText
import com.carlom.klardrop.common.utils.writeFile0600
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.posix.unlink
import kotlin.io.encoding.Base64

/**
 * Linux implementation of TrustStorage.
 *
 * Peer ECDH/ECDSA public keys live in Properties files under [appDir].
 * The device's own ECDSA P-256 private key is delegated to [SecretStore]
 * (SecretToolSecretStore or EncryptedFileSecretStore fallback).
 * All properties files are stored with permissions 0600 and directories with 0700.
 */
class LinuxTrustStorage internal constructor(
  val appDir: String,
  private val secretStore: SecretStore,
) : TrustStorage {

  constructor(appDir: String = LinuxPaths.trustDir) : this(appDir, pickSecretStore(appDir))

  companion object {
    private const val TRUST_FILE_NAME = "trusted_devices.properties"
    private const val ECDSA_FILE_NAME = "ecdsa_keys.properties"
    private const val SHARED_SECRETS_FILE_NAME = "shared_secrets.properties"
    private const val DEVICE_KEY_FILE_NAME = "device_private_key.properties"
    private const val DEVICE_PUBLIC_KEY = "device_public_key"
    private const val SECRET_ACCOUNT_DEVICE_PRIVATE_KEY = "device-private-key"
  }

  private val trustFile = "$appDir/$TRUST_FILE_NAME"
  private val ecdsaFile = "$appDir/$ECDSA_FILE_NAME"
  private val sharedSecretsFile = "$appDir/$SHARED_SECRETS_FILE_NAME"
  private val deviceKeyFile = "$appDir/$DEVICE_KEY_FILE_NAME"
  private val fileMutex = Mutex()

  init {
    ensureDirectory0700(appDir)
  }

  private suspend fun loadProperties(filePath: String): MutableMap<String, String> = withContext(sharedNativeIoDispatcher) {
    val content = readFileText(filePath) ?: return@withContext mutableMapOf()
    parseProperties(content)
  }

  private suspend fun saveProperties(props: Map<String, String>, filePath: String, comment: String) = withContext(sharedNativeIoDispatcher) {
    ensureDirectory0700(appDir)
    val sb = StringBuilder()
    sb.append("# ").append(comment).append("\n")
    for ((key, value) in props) {
      val escapedKey = escapeProperty(key)
      val escapedValue = escapeProperty(value)
      sb.append(escapedKey).append("=").append(escapedValue).append("\n")
    }
    writeFile0600(filePath, sb.toString())
  }

  override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {
    fileMutex.withLock {
      val props = loadProperties(trustFile)
      val encodedKey = Base64.encode(publicKey)
      props[deviceId] = encodedKey
      saveProperties(props, trustFile, "Klardrop Trusted Devices - Do not manually edit this file")
    }
  }

  override suspend fun getTrustedDeviceKey(deviceId: String): ByteArray? {
    fileMutex.withLock {
      val props = loadProperties(trustFile)
      val encodedKey = props[deviceId] ?: return null
      return try {
        Base64.decode(encodedKey)
      } catch (_: Exception) {
        props.remove(deviceId)
        saveProperties(props, trustFile, "Klardrop Trusted Devices - Do not manually edit this file")
        null
      }
    }
  }

  override suspend fun getAllTrustedDevices(): Map<String, ByteArray> {
    fileMutex.withLock {
      val props = loadProperties(trustFile)
      val result = mutableMapOf<String, ByteArray>()
      for ((deviceId, encodedKey) in props) {
        try {
          result[deviceId] = Base64.decode(encodedKey)
        } catch (_: Exception) {
          continue
        }
      }
      return result
    }
  }

  override suspend fun removeTrustedDevice(deviceId: String) {
    fileMutex.withLock {
      val ecdhProps = loadProperties(trustFile)
      ecdhProps.remove(deviceId)
      saveProperties(ecdhProps, trustFile, "Klardrop Trusted Devices - Do not manually edit this file")

      val ecdsaProps = loadProperties(ecdsaFile)
      ecdsaProps.remove(deviceId)
      saveProperties(ecdsaProps, ecdsaFile, "Klardrop ECDSA Keys - Do not manually edit this file")

      val secretsProps = loadProperties(sharedSecretsFile)
      secretsProps.remove(deviceId)
      saveProperties(secretsProps, sharedSecretsFile, "Klardrop Shared Secrets - Do not manually edit this file")
    }
  }

  override suspend fun clearAllTrustedDevices() {
    fileMutex.withLock {
      saveProperties(emptyMap(), trustFile, "Klardrop Trusted Devices - Do not manually edit this file")
      saveProperties(emptyMap(), ecdsaFile, "Klardrop ECDSA Keys - Do not manually edit this file")
      saveProperties(emptyMap(), sharedSecretsFile, "Klardrop Shared Secrets - Do not manually edit this file")
    }
  }

  override suspend fun storeECDSAKey(deviceId: String, ecdsaPublicKey: ByteArray) {
    fileMutex.withLock {
      val props = loadProperties(ecdsaFile)
      val encodedKey = Base64.encode(ecdsaPublicKey)
      props[deviceId] = encodedKey
      saveProperties(props, ecdsaFile, "Klardrop ECDSA Keys - Do not manually edit this file")
    }
  }

  override suspend fun getECDSAKey(deviceId: String): ByteArray? {
    fileMutex.withLock {
      val props = loadProperties(ecdsaFile)
      val encodedKey = props[deviceId] ?: return null
      return try {
        Base64.decode(encodedKey)
      } catch (_: Exception) {
        props.remove(deviceId)
        saveProperties(props, ecdsaFile, "Klardrop ECDSA Keys - Do not manually edit this file")
        null
      }
    }
  }

  override suspend fun storeDevicePrivateKey(privateKey: ByteArray) {
    withContext(sharedNativeIoDispatcher) {
      secretStore.put(SECRET_ACCOUNT_DEVICE_PRIVATE_KEY, privateKey)
    }
  }

  override suspend fun getDevicePrivateKey(): ByteArray? = withContext(sharedNativeIoDispatcher) {
    secretStore.get(SECRET_ACCOUNT_DEVICE_PRIVATE_KEY)
  }

  override suspend fun hasDeviceKey(): Boolean = withContext(sharedNativeIoDispatcher) {
    secretStore.get(SECRET_ACCOUNT_DEVICE_PRIVATE_KEY) != null
  }

  override suspend fun deleteDevicePrivateKey() {
    withContext(sharedNativeIoDispatcher) {
      secretStore.delete(SECRET_ACCOUNT_DEVICE_PRIVATE_KEY)
    }
    fileMutex.withLock {
      unlink(deviceKeyFile)
    }
  }

  override suspend fun storeDevicePublicKey(publicKey: ByteArray) {
    fileMutex.withLock {
      val props = loadProperties(deviceKeyFile)
      val encodedKey = Base64.encode(publicKey)
      props[DEVICE_PUBLIC_KEY] = encodedKey
      saveProperties(props, deviceKeyFile, "Klardrop Device Identity - Do not manually edit this file")
    }
  }

  override suspend fun getDevicePublicKey(): ByteArray? {
    fileMutex.withLock {
      val props = loadProperties(deviceKeyFile)
      val encodedKey = props[DEVICE_PUBLIC_KEY] ?: return null
      return try {
        Base64.decode(encodedKey)
      } catch (_: Exception) {
        props.remove(DEVICE_PUBLIC_KEY)
        saveProperties(props, deviceKeyFile, "Klardrop Device Identity - Do not manually edit this file")
        null
      }
    }
  }

  override suspend fun storeSharedSecret(deviceId: String, sharedSecret: ByteArray) {
    fileMutex.withLock {
      val props = loadProperties(sharedSecretsFile)
      props[deviceId] = Base64.encode(sharedSecret)
      saveProperties(props, sharedSecretsFile, "Klardrop Shared Secrets - Do not manually edit this file")
    }
  }

  override suspend fun getSharedSecret(deviceId: String): ByteArray? {
    fileMutex.withLock {
      val props = loadProperties(sharedSecretsFile)
      val encoded = props[deviceId] ?: return null
      return try {
        Base64.decode(encoded)
      } catch (_: Exception) {
        props.remove(deviceId)
        saveProperties(props, sharedSecretsFile, "Klardrop Shared Secrets - Do not manually edit this file")
        null
      }
    }
  }
}

internal fun parseProperties(content: String): MutableMap<String, String> {
  val map = mutableMapOf<String, String>()
  for (rawLine in content.lines()) {
    val line = rawLine.trim()
    if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
    val sepIdx = line.indexOfFirst { it == '=' || it == ':' }
    if (sepIdx < 0) continue
    val key = unescapeProperty(line.substring(0, sepIdx).trim())
    val value = unescapeProperty(line.substring(sepIdx + 1).trim())
    map[key] = value
  }
  return map
}

private fun escapeProperty(s: String): String {
  return s.replace("\\", "\\\\").replace("=", "\\=").replace(":", "\\:")
}

private fun unescapeProperty(s: String): String {
  var res = s
  if (res.contains('\\')) {
    res = res.replace("\\=", "=").replace("\\:", ":").replace("\\\\", "\\")
  }
  return res
}
