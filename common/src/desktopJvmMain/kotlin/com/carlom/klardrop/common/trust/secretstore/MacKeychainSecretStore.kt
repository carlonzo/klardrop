package com.carlom.klardrop.common.trust.secretstore

import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Stores the device private key in the macOS login Keychain via the
 * `security` CLI. Generic-password items are scoped per-app via the
 * service name [SECRET_STORE_SERVICE]; Keychain ACL keeps them readable
 * only by the running binary.
 *
 * Bytes are Base64-encoded before being written to the Keychain so the
 * shell-friendly `security` CLI can round-trip arbitrary binary data.
 */
internal class MacKeychainSecretStore : SecretStore {

    override fun get(account: String): ByteArray? {
        val result = run("find-generic-password", "-s", SECRET_STORE_SERVICE, "-a", account, "-w")
        if (result.exitCode != 0) return null
        val encoded = result.stdout.trim()
        if (encoded.isEmpty()) return null
        return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
    }

    override fun put(account: String, value: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(value)
        // -U updates an existing entry, -s service, -a account, -w password.
        // The encoded value is ASCII Base64, safe to pass on the command line.
        val result = run(
            "add-generic-password",
            "-U",
            "-s", SECRET_STORE_SERVICE,
            "-a", account,
            "-w", encoded
        )
        if (result.exitCode != 0) {
            throw IllegalStateException("security add-generic-password failed: ${result.stderr.trim()}")
        }
    }

    override fun delete(account: String) {
        // Best-effort; the CLI returns a non-zero exit code when the entry
        // doesn't exist, which is fine.
        run("delete-generic-password", "-s", SECRET_STORE_SERVICE, "-a", account)
    }

    private fun run(vararg args: String): CommandResult {
        val process = ProcessBuilder("security", *args)
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("security CLI timed out")
        }
        return CommandResult(process.exitValue(), stdout, stderr)
    }

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)
}
