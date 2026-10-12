package com.carlom.klardrop.common.trust.secretstore

/**
 * Per-OS secret-storage abstraction backing LinuxTrustStorage device private key.
 */
internal interface SecretStore {
  fun get(account: String): ByteArray?
  fun put(account: String, value: ByteArray)
  fun delete(account: String)
}

internal const val SECRET_STORE_SERVICE = "com.carlom.klardrop"

/**
 * Pick the most-secure available [SecretStore] for Linux.
 * - SecretToolSecretStore if secret-tool and DBUS session are reachable.
 * - EncryptedFileSecretStore fallback otherwise.
 */
internal fun pickSecretStore(appDir: String): SecretStore {
  return SecretToolSecretStore.tryCreate()
    ?: EncryptedFileSecretStore.warnAndCreate(appDir)
}
