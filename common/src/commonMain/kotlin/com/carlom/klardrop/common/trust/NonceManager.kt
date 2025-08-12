package com.carlom.klardrop.common.trust

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Manages nonce validation to prevent replay attacks.
 * Tracks seen nonces per sender device to ensure uniqueness within the time window.
 */
class NonceManager(
  // Make lifetime slightly longer than the message time window to handle clock skew
  private val nonceLifetime: Duration = 6.minutes
) {
  // Cache: senderId -> Map<nonce, expiryTimestamp>
  // Using Kotlin multiplatform compatible collections

  private val seenNonces = mutableMapOf<String, MutableMap<String, Instant>>()
  private val mutex = Mutex() // For thread safety across platforms

  // Cleanup mechanism to prevent top-level memory leaks
  private var globalCleanupCounter = 0
  private val globalCleanupInterval = 500 // Clean up device maps every 500 calls

  /**
   * Atomically checks if a nonce is valid and marks it as seen.
   * @param senderId Device ID of the sender
   * @param nonceBytes Nonce bytes converted to hex string for comparison
   * @return `true` if the nonce is new and valid, `false` if it's a replay or invalid.
   */
  suspend fun isNonceValid(senderId: String, nonceBytes: ByteArray): Boolean {
    val nonce = nonceBytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') } // Convert to hex string
    val now = Clock.System.now()

    return mutex.withLock {
      // Global cleanup to remove empty/expired device maps
      if (++globalCleanupCounter >= globalCleanupInterval) {
        val devicesToRemove = mutableListOf<String>()
        seenNonces.forEach { (deviceId, deviceNonces) ->
          // Remove expired nonces from this device
          val expiredKeys = deviceNonces.filterValues { it < now }.keys
          expiredKeys.forEach { deviceNonces.remove(it) }

          // If device map is now empty, mark for removal
          if (deviceNonces.isEmpty()) {
            devicesToRemove.add(deviceId)
          }
        }
        // Remove empty device maps to prevent top-level memory leak
        devicesToRemove.forEach { seenNonces.remove(it) }
        globalCleanupCounter = 0
      }

      val deviceNonces = seenNonces.getOrPut(senderId) { mutableMapOf() }

      // Check if nonce already exists
      if (deviceNonces.containsKey(nonce)) {
        // Nonce has been seen before - replay attack
        return@withLock false
      }

      // Add the new nonce
      deviceNonces[nonce] = now + nonceLifetime

      // Cleanup expired nonces for this device
      val expiredKeys = deviceNonces.filterValues { it < now }.keys
      expiredKeys.forEach { deviceNonces.remove(it) }

      return@withLock true
    }
  }

}