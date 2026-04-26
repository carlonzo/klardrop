package com.carlom.klardrop.common.mqtt

/**
 * In-memory cache of recently-seen `(senderDeviceId, nonce)` pairs.
 *
 * `consume(...)` returns true the first time a nonce is seen and false on
 * any later attempt within the TTL window. Stale entries are evicted lazily
 * on each call — there is no background sweep, so the data structure stays
 * cheap to instantiate per-connection.
 *
 * Capacity bounds are intentional: replay protection only needs to hold the
 * window, not the entire history. When the cap is hit the oldest half of
 * the buffer is discarded; an attacker who could force eviction would still
 * have to land their replay within the same window.
 *
 * Not thread-safe — callers serialize access via a single inbound-message
 * coroutine (the MQTT handler dispatch is naturally sequential).
 */
class ReplayProtector(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: Clock
) {
    private data class Entry(val key: String, val seenAtMs: Long)

    private val seen = ArrayDeque<Entry>(maxEntries.coerceAtMost(1024))
    private val keyIndex = HashSet<String>()
    // Start at 0 (not Long.MIN_VALUE): the cooldown subtract `now - last`
    // would otherwise overflow on the first call and silently disable eviction.
    private var lastEvictAtMs: Long = 0L

    /**
     * Returns true if `(senderDeviceId, nonce)` has not been seen within the
     * TTL window. Returns false if it's a replay.
     */
    fun consume(senderDeviceId: String, nonce: ByteArray): Boolean {
        val key = composeKey(senderDeviceId, nonce)
        val now = clock.nowMs()
        evictExpired(now)
        if (key in keyIndex) return false
        if (seen.size >= maxEntries) compactHalf()
        seen.addLast(Entry(key, now))
        keyIndex.add(key)
        return true
    }

    private fun evictExpired(now: Long) {
        // Cheap fast-path: don't sweep more than once per second.
        if (now - lastEvictAtMs < 1_000) return
        lastEvictAtMs = now
        while (seen.isNotEmpty() && now - seen.first().seenAtMs > ttlMs) {
            keyIndex.remove(seen.removeFirst().key)
        }
    }

    private fun compactHalf() {
        val drop = seen.size / 2
        repeat(drop) { keyIndex.remove(seen.removeFirst().key) }
    }

    private fun composeKey(senderDeviceId: String, nonce: ByteArray): String {
        val hex = StringBuilder(senderDeviceId.length + 1 + nonce.size * 2)
        hex.append(senderDeviceId).append('|')
        for (b in nonce) {
            hex.append(HEX[(b.toInt() ushr 4) and 0xF])
            hex.append(HEX[b.toInt() and 0xF])
        }
        return hex.toString()
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 60_000L * 5         // 5 min
        const val DEFAULT_MAX_ENTRIES: Int = 4_096

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
