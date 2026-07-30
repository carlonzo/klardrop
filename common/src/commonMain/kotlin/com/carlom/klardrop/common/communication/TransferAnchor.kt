package com.carlom.klardrop.common.communication

/**
 * Platform hook that keeps the host process — and with it the open socket carrying the bytes —
 * alive and awake for the duration of a file transfer, in either direction.
 *
 * A file transfer is not a quick round trip: it first waits for the receiver to accept (which can
 * take as long as the user takes to look at their phone), then streams for as long as the file is
 * big — a multi-GB send over Wi-Fi is minutes, not seconds. That whole window is exactly when a
 * mobile OS is most likely to intervene: Android freezes or kills a process whose Activity just
 * went away, both platforms let the screen sleep and the Wi-Fi radio drop into power-save, and iOS
 * suspends a backgrounded app outright. The transfer itself already runs in a process-lifetime
 * scope, so nothing in the *app* cancels it; only the platform does. This hook is where a platform
 * gets to say "not yet".
 *
 * Callers [begin] before a transfer starts, feed [progress] as bytes move, and always call [end] —
 * success, failure, or cancellation. Implementations must therefore be cheap, thread-safe (calls
 * arrive on IO scopes) and must never throw: a platform that can't anchor right now should degrade
 * to doing nothing rather than fail the transfer.
 *
 * Anchoring is deliberately reference-counted by transfer id rather than a single boolean, because
 * transfers overlap — a device can be receiving one file while sending another, and the process
 * must stay anchored until the *last* of them finishes.
 *
 * Platform implementations:
 * - **Android** — a `dataSync` foreground service holding a WifiLock and a partial wake lock, so
 *   the transfer survives backgrounding and keeps running with the screen off
 *   (`AndroidTransferAnchor`).
 * - **iOS/iPadOS** — [IosTransferAnchor]: disables the idle timer so the screen stays on for the
 *   duration (iOS offers a backgrounded app no way to keep a socket streaming), plus a background
 *   task so a brief app switch doesn't suspend the process instantly.
 * - **macOS** — [MacTransferAnchor]: an `NSProcessInfo` activity that blocks idle system sleep.
 * - **Desktop JVM** — [None]; desktop processes aren't killed for being idle.
 */
interface TransferAnchor {

  /** Which way the bytes are moving. Platforms use it for notification wording and icons. */
  enum class Direction { OUTGOING, INCOMING }

  /**
   * A transfer identified by [transferId] is starting. [label] is a human-readable name for the
   * payload (usually the file name) that platforms may surface in a progress notification.
   *
   * [transferId] must be unique across both directions for the lifetime of the transfer; callers
   * build it from the peer's device id, the direction and the message id.
   */
  fun begin(transferId: String, label: String, direction: Direction)

  /** Best-effort progress for an in-flight [transferId]; [percentage] is 0..100. */
  fun progress(transferId: String, percentage: Int)

  /** The transfer reached a terminal state. Safe to call more than once for the same id. */
  fun end(transferId: String)

  companion object {
    /** No-op anchor for platforms that don't need (or can't provide) one. */
    val None: TransferAnchor = object : TransferAnchor {
      override fun begin(transferId: String, label: String, direction: Direction) = Unit
      override fun progress(transferId: String, percentage: Int) = Unit
      override fun end(transferId: String) = Unit
    }
  }
}

/**
 * The anchor this platform can provide on its own, with no help from the app module.
 *
 * Apple targets return a real one here because everything they need (`UIApplication`,
 * `NSProcessInfo`) is reachable from `common`. Android's needs a foreground service declared in
 * the app manifest, so it returns [TransferAnchor.None] and the app passes the real implementation
 * to [com.carlom.klardrop.common.Klardrop] explicitly.
 */
expect fun platformTransferAnchor(): TransferAnchor
