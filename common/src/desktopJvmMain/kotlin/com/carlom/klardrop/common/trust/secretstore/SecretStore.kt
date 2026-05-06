package com.carlom.klardrop.common.trust.secretstore

import java.io.File

/**
 * Per-OS secret-storage abstraction backing [DesktopTrustStorage]'s device
 * private key. Implementations talk to the system credential vault
 * (macOS Keychain, Windows DPAPI, Linux libsecret) so the device's
 * ECDSA private key is not readable as plaintext on disk.
 *
 * The fallback for Linux installs without a secret service is an
 * AES-GCM encrypted file. That fallback is clearly weaker than a real
 * secret service and logs a WARN at startup.
 */
internal interface SecretStore {
    fun get(account: String): ByteArray?
    fun put(account: String, value: ByteArray)
    fun delete(account: String)
}

internal const val SECRET_STORE_SERVICE = "com.carlom.klardrop"

/**
 * Pick the most-secure available [SecretStore] for the current OS.
 *
 * - macOS → [MacKeychainSecretStore] via the `security` CLI.
 * - Windows → [WindowsDpapiSecretStore] via JNA + DPAPI.
 * - Linux with `secret-tool` → [SecretToolSecretStore].
 * - Otherwise → [EncryptedFileSecretStore] with a WARN log.
 *
 * @param appDir directory where blob-on-disk fallbacks (Windows DPAPI,
 *   AES-GCM file) are written.
 */
internal fun pickSecretStore(appDir: File): SecretStore {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        osName.contains("mac") || osName.contains("darwin") -> MacKeychainSecretStore()
        osName.contains("windows") -> WindowsDpapiSecretStore(appDir)
        osName.contains("linux") -> SecretToolSecretStore.tryCreate()
            ?: EncryptedFileSecretStore.warnAndCreate(appDir)
        else -> EncryptedFileSecretStore.warnAndCreate(appDir)
    }
}
