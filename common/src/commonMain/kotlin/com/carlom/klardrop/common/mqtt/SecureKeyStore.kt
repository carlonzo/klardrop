package com.carlom.klardrop.common.mqtt

/**
 * Persistent home for **the device's own** Ed25519 keypair — the one used to
 * sign every outgoing `SignedEnvelope`. Receivers verify with the public key
 * we register at enrollment, so this private key MUST survive app restarts
 * and never leak.
 *
 * Implementations are platform-specific:
 *  - JVM/desktop: `FileSystemSecureKeyStore` writes a 0600-protected file
 *    under the user's config dir.
 *  - Android: a Keystore-backed AES-GCM-wrapped record in DataStore (TODO).
 *  - iOS: a Keychain item (TODO).
 *
 * The interface is deliberately minimal: load-or-generate is a single call
 * with at-most-once semantics, so a concurrent first-launch race can't end
 * up with two keys.
 */
interface SecureKeyStore {
    /**
     * Returns the locally-stored device keypair, generating and persisting
     * a fresh one on first call. Subsequent calls return the same pair.
     *
     * `init` is intentionally synchronous: bringing up MQTT cannot proceed
     * without a key, so making the caller suspend would just push the
     * latency off-thread without making it any faster.
     */
    fun loadOrGenerate(): Ed25519KeyPair

    /**
     * Wipe the stored key. Used during sign-out, factory-reset, or after a
     * server-side revocation has invalidated this device's enrollment so we
     * generate a new identity for any re-pairing.
     */
    fun clear()
}

/**
 * Single-process, ephemeral. Only suitable for tests and short-lived CLI
 * tools; production paths must use a persistent platform store.
 */
class InMemorySecureKeyStore : SecureKeyStore {
    @Volatile
    private var pair: Ed25519KeyPair? = null
    private val lock = Any()

    override fun loadOrGenerate(): Ed25519KeyPair {
        pair?.let { return it }
        synchronized(lock) {
            pair?.let { return it }
            val fresh = generateEd25519KeyPair()
            pair = fresh
            return fresh
        }
    }

    override fun clear() = synchronized(lock) { pair = null }
}
