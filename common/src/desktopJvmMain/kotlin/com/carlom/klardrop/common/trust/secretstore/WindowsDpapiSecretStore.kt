package com.carlom.klardrop.common.trust.secretstore

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Stores the device private key on Windows by encrypting it with DPAPI
 * (current-user scope) and writing the encrypted blob to disk under
 * [appDir]. The plaintext key is never written.
 *
 * DPAPI ties the encryption key to the user's logon credentials, so the
 * blob can only be decrypted by the same user on the same machine —
 * equivalent in protection to Credential Manager for local user secrets.
 */
internal class WindowsDpapiSecretStore(private val appDir: File) : SecretStore {

    init {
        if (!appDir.exists()) appDir.mkdirs()
    }

    override fun get(account: String): ByteArray? {
        val file = blobFile(account)
        if (!file.exists()) return null
        val encrypted = runCatching { Files.readAllBytes(file.toPath()) }.getOrNull() ?: return null
        return runCatching { Crypt32Util.cryptUnprotectData(encrypted) }.getOrNull()
    }

    override fun put(account: String, value: ByteArray) {
        val encrypted = Crypt32Util.cryptProtectData(value)
        val file = blobFile(account)
        val tmp = File(file.parentFile, file.name + ".tmp")
        Files.write(tmp.toPath(), encrypted)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun delete(account: String) {
        blobFile(account).delete()
    }

    private fun blobFile(account: String): File =
        File(appDir, "${sanitize(account)}.dpapi")

    private fun sanitize(account: String): String =
        account.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
