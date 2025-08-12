package com.carlom.klardrop.common.trust


/**
 * Interface for checking device trust status.
 * Used by Messenger to determine if messages should be wrapped as trusted.
 */
interface TrustChecker {
  suspend fun isTrusted(deviceId: String): Boolean
}