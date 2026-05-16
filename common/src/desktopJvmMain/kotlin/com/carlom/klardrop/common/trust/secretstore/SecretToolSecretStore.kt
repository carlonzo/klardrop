package com.carlom.klardrop.common.trust.secretstore

import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Linux secret-storage path that shells out to `secret-tool`, the CLI
 * shipped with libsecret. Talks to the user's session keyring (GNOME
 * Keyring, KWallet via secret service bridge, …) over D-Bus.
 *
 * The CLI is preferred over JNA libsecret bindings because libsecret's
 * variadic API is awkward to bind from JVM and the CLI is widely
 * preinstalled. If `secret-tool` is missing, the caller should fall
 * back to [EncryptedFileSecretStore].
 */
internal class SecretToolSecretStore private constructor() : SecretStore {

    override fun get(account: String): ByteArray? {
        val result = run(input = null, "lookup", "service", SECRET_STORE_SERVICE, "account", account)
        if (result.exitCode != 0) return null
        val encoded = result.stdout.trim()
        if (encoded.isEmpty()) return null
        return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
    }

    override fun put(account: String, value: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(value)
        // secret-tool reads the password from stdin when -e is not given;
        // it expects a single line followed by the end of stream.
        val result = run(
            input = encoded,
            "store",
            "--label=Klardrop device identity",
            "service", SECRET_STORE_SERVICE,
            "account", account
        )
        if (result.exitCode != 0) {
            throw IllegalStateException("secret-tool store failed: ${result.stderr.trim()}")
        }
    }

    override fun delete(account: String) {
        // Best-effort; non-zero exit when entry doesn't exist is fine.
        run(input = null, "clear", "service", SECRET_STORE_SERVICE, "account", account)
    }

    private fun run(input: String?, vararg args: String): CommandResult {
        val process = ProcessBuilder("secret-tool", *args)
            .redirectErrorStream(false)
            .start()
        if (input != null) {
            process.outputStream.use { it.write((input + "\n").toByteArray(Charsets.UTF_8)) }
        } else {
            process.outputStream.close()
        }
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("secret-tool timed out")
        }
        return CommandResult(process.exitValue(), stdout, stderr)
    }

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

    companion object {
        /**
         * Attempts to verify the `secret-tool` binary is on PATH and the
         * session bus is reachable. Returns null if either fails — the
         * caller should fall back to [EncryptedFileSecretStore].
         */
        fun tryCreate(): SecretToolSecretStore? {
            // Many headless / CI Linux runs have no D-Bus session.
            if (System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) return null
            return try {
                val process = ProcessBuilder("secret-tool", "--help").redirectErrorStream(true).start()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return null
                }
                if (process.exitValue() == 0) SecretToolSecretStore() else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
