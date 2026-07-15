package com.carlom.klardrop.common.utils

/**
 * Classifies exceptions that are part of the protocol's normal life cycle and should
 * not be reported to Bugsnag. Two contributors converge here so both stay in lockstep:
 *
 *  1. Call sites that *expect* a network-shaped failure (peer dial failed, peer closed
 *     mid-read, BLE disconnect during handshake) call [logLocal] directly — no
 *     classifier check needed.
 *  2. The catch-all `CoroutineExceptionHandler` and the platform Bugsnag onError /
 *     callback hook use this predicate as a last-line filter for anything that slipped
 *     through and was about to be uploaded.
 *
 * Classification is by class simpleName + message text rather than `is` checks, so the
 * common-source-set version compiles for every target (JVM-only `java.net.*` types are
 * unavailable in iOS/Apple). Platform-specific overrides can wrap this with extra
 * `is`-checks for compile-time precision if needed.
 */
fun Throwable.isExpectedNetworkNoise(): Boolean {
  // Walk the cause chain — Ktor wraps lower-level IOException in ClosedByteChannelException
  // and Bugsnag's grouping latches onto whichever frame is closest to user code.
  var current: Throwable? = this
  var depth = 0
  while (current != null && depth < 8) {
    if (current.matchesKnownNoise()) return true
    current = current.cause?.takeIf { it !== current }
    depth++
  }
  return false
}

private fun Throwable.matchesKnownNoise(): Boolean {
  val name = this::class.simpleName ?: return false
  val msg = message.orEmpty()
  return when (name) {
    // Coroutine cancelled because the parent scope/connection closed — expected lifecycle,
    // not a product bug. Bugsnag was flooded with "StandaloneCoroutine was cancelled"
    // (ConnectionMessenger read loop after heartbeat close / explicit close).
    "CancellationException",
    "JobCancellationException" -> true

    // Peer closed the channel / OS aborted the connection.
    "ClosedByteChannelException",
    "ClosedWriteChannelException",
    "ClosedSendChannelException",
    "ClosedReceiveChannelException" -> true

    // Connect-time failures from the Klardrop client dialing a peer that's gone.
    "ConnectException",
    "SocketTimeoutException" -> true

    "SocketException" ->
      msg.contains("Software caused connection abort", ignoreCase = true) ||
        msg.contains("Connection reset", ignoreCase = true) ||
        msg.contains("Broken pipe", ignoreCase = true)

    // Read pump observed the peer hang up.
    "EOFException" ->
      msg.contains("Channel is already closed", ignoreCase = true) ||
        msg.isEmpty()

    // iOS-side ktor/kotlinx.io connect failures wrap into kotlinx.io.IOException.
    "IOException" ->
      msg.contains("Failed to connect to InetSocketAddress", ignoreCase = true) ||
        msg.contains("Software caused connection abort", ignoreCase = true) ||
        msg.contains("Connection reset", ignoreCase = true) ||
        msg.contains("Channel is already closed", ignoreCase = true) ||
        msg.contains("ECONNRESET", ignoreCase = true) ||
        msg.contains("ECONNREFUSED", ignoreCase = true)

    // BLE disconnects observed during the handshake window — see BleTransport.apple.kt.
    // Also covers the analogous `connect failed:` path. Same class is used by ACK
    // timeouts, but those carry a distinct prefix and we want those reported in prod.
    "IllegalStateException" ->
      msg.startsWith("disconnected during handshake", ignoreCase = true) ||
        msg.startsWith("connect failed:", ignoreCase = true)

    // Dial-on-open / chat open when no transport is ready — UX failure, not a crash.
    "IllegalArgumentException" ->
      msg.contains("No Klardrop TCP or BLE connection is available", ignoreCase = true)

    else -> false
  }
}
