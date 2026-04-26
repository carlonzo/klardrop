package com.carlom.klardrop.common.mqtt

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * File-based SecureKeyStore intended for desktop JVM and as a fallback on
 * Android (a Keystore-backed implementation will replace it on Android in
 * a follow-up commit).
 *
 * Layout under [directory]:
 *   ed25519.key   — 64 bytes: [32-byte seed][32-byte public key].
 *
 * On POSIX systems we set 0600 on the file. We deliberately do NOT add a
 * passphrase — a side-project on a single-user machine relies on OS user
 * permissions, and we'd rather not prompt for unlock at every boot.
 *
 * The file is written atomically (write to `.tmp`, fsync, rename) so a
 * crash mid-generation can't leave a partial key.
 */
class FileSystemSecureKeyStore(directory: File) : SecureKeyStore {

    private val file: File = File(directory, FILE_NAME)
    private val lock = Any()

    init {
        if (!directory.exists() && !directory.mkdirs()) {
            error("Could not create SecureKeyStore directory: $directory")
        }
    }

    override fun loadOrGenerate(): Ed25519KeyPair = synchronized(lock) {
        readExisting()?.let { return it }
        val fresh = generateEd25519KeyPair()
        writeAtomically(fresh)
        return fresh
    }

    override fun clear() = synchronized(lock) {
        if (file.exists() && !file.delete()) {
            error("Failed to delete SecureKeyStore file: $file")
        }
    }

    private fun readExisting(): Ed25519KeyPair? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.size != 64) {
            // Corrupt — refuse to use it. Caller will regenerate via clear+load.
            return null
        }
        val seed = bytes.copyOfRange(0, 32)
        val pub = bytes.copyOfRange(32, 64)
        return Ed25519KeyPair(privateKeySeed = seed, publicKey = pub)
    }

    private fun writeAtomically(pair: Ed25519KeyPair) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val combined = ByteArray(64).apply {
            pair.privateKeySeed.copyInto(this, 0)
            pair.publicKey.copyInto(this, 32)
        }
        Files.newOutputStream(
            tmp.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC
        ).use { it.write(combined) }
        applyOwnerOnlyPermissions(tmp)
        // Move into place — atomic on POSIX, best-effort on Windows.
        Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun applyOwnerOnlyPermissions(target: File) {
        runCatching {
            Files.setPosixFilePermissions(
                target.toPath(),
                PosixFilePermissions.fromString("rw-------")
            )
        }
        // On non-POSIX (Windows): rely on parent directory ACL + filesystem.
        // Add Java's owner-only writability hint.
        target.setReadable(false, false)
        target.setReadable(true, true)
        target.setWritable(false, false)
        target.setWritable(true, true)
    }

    @Suppress("unused")
    private fun debugPermsString(perms: Set<PosixFilePermission>): String =
        PosixFilePermissions.toString(perms)

    companion object {
        private const val FILE_NAME = "ed25519.key"
    }
}
