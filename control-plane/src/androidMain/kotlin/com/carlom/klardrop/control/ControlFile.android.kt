package com.carlom.klardrop.control

// Android has no local clients to publish a control file for (adb forward reaches the app's
// loopback port directly), so nothing is written and no token is generated.
internal actual fun writeControlFile(port: Int, token: String, capabilities: List<String>) = Unit

internal actual fun deleteControlFile(expectedToken: String?) = Unit

actual fun resolveControlFilePath(): String? = null

/**
 * No local user to hand a token to: the Android harness reaches this port over `adb forward`,
 * which is already an authenticated channel, so publishing nothing is correct here.
 */
internal actual val controlFileOptional: Boolean = true

/** Every production route works on Android; nothing is withheld. */
internal actual val platformUnavailableCapabilities: Set<String> = emptySet()