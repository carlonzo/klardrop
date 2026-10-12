package com.carlom.klardrop.control

/**
 * The control plane's wire contract, shared by every writer of `control.json` and by the
 * clients that read it (the Kotlin CLI, the native Rust CLI/TUI, the Qt and Omarchy UIs).
 *
 * [API_VERSION] is bumped whenever an existing route changes shape or a route is removed.
 * New routes and new fields may be added without a bump — that is what [PRODUCTION_CAPABILITIES]
 * advertises.
 */
const val API_VERSION = 1

/** Routes usable in every build. Each entry is a stable client-facing identifier. */
val PRODUCTION_CAPABILITIES: List<String> = listOf(
  "health",
  "state",
  "capabilities",
  "history",
  "send-text",
  "send-file",
  "send-clipboard",
  "share",
  "transfers",
  "pair",
  "unpair",
  "accept-pair",
  "reject-pair",
  "accept-incoming",
  "reject-incoming",
  "retry",
  "rename-device",
  "settings",
  "qr-share",
  "update",
)

/**
 * Routes the server answers with 403 outside debug builds. Advertised only when the host is a
 * debug build, so a client never learns it can rely on a route it cannot call.
 */
val DEBUG_ONLY_CAPABILITIES: List<String> = listOf(
  "logs",
  "window",
  "reset-identity",
)

/**
 * Production routes this platform genuinely cannot serve, whatever the build type.
 *
 * A capability a host advertises but cannot answer is worse than a missing one: a client is
 * entitled to draw a control for it, and the user only finds out when the request fails. The
 * native macOS host is the only non-empty case today — its `qr-share` entry is explained in
 * `QrMatrixRenderer.macos.kt`.
 */
internal expect val platformUnavailableCapabilities: Set<String>

/** The capability list a client should treat as available for this build. */
fun forBuild(isDebug: Boolean): List<String> {
  val available = PRODUCTION_CAPABILITIES.filterNot { it in platformUnavailableCapabilities }
  return if (isDebug) available + DEBUG_ONLY_CAPABILITIES else available
}

/**
 * Encodes the `control.json` body published by every platform's `writeControlFile`.
 *
 * Written by hand (no serialization runtime) so the JVM and native files are byte-identical:
 * clients parse the same document regardless of which host published it. [token] is the 32
 * lowercase hex string generated per run, which is also why it is safe to send in an
 * `Authorization` header.
 */
fun controlFileJson(port: Int, token: String, capabilities: List<String>): String =
  buildString {
    append("""{"port":""").append(port)
    append(""","token":"""").append(token).append('"')
    append(""","apiVersion":""").append(API_VERSION)
    append(""","capabilities":[""").append(capabilities.joinToString(",") { "\"$it\"" }).append("]}")
  }