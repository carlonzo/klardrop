package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.utils.log
import platform.Foundation.NSActivityUserInitiated
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.darwin.NSObjectProtocol

/**
 * macOS [TransferAnchor]: holds an `NSProcessInfo` activity assertion so the Mac doesn't idle-sleep
 * out from under a long transfer.
 *
 * A Mac won't kill the process for being backgrounded the way iOS does, but it will happily suspend
 * the whole machine after the idle-sleep timeout — which drops every socket mid-stream. The
 * assertion tells the OS "a user-initiated job is running, don't idle-sleep"; the *display* is
 * still free to turn off, which is what we want (this is exactly the "screen off, transfer keeps
 * going" behaviour the Android foreground service gives).
 *
 * Reference-counted by transfer id, and released as soon as the last transfer finishes so an
 * interrupted transfer can't leave the machine unable to sleep. Mutations are hopped onto the main
 * queue so [active] and [assertion] stay confined to one thread — the anchor is driven from IO
 * scopes.
 */
class MacTransferAnchor : TransferAnchor {

  /** Ids of in-flight transfers. Touched only from the main queue — see [onMain]. */
  private val active = mutableSetOf<String>()

  /** Non-null while we hold the activity assertion. Main-queue confined, like [active]. */
  private var assertion: NSObjectProtocol? = null

  override fun begin(transferId: String, label: String, direction: TransferAnchor.Direction) {
    onMain {
      if (!active.add(transferId)) return@onMain
      if (active.size == 1) {
        // NSActivityUserInitiated already folds in NSActivityIdleSystemSleepDisabled; the display
        // is left free to sleep, which is what we want — screen off, transfer still running.
        assertion = NSProcessInfo.processInfo.beginActivityWithOptions(
          NSActivityUserInitiated,
          "Klardrop file transfer in progress",
        )
        log("MacTransferAnchor", "Transfer started; blocking idle system sleep")
      }
    }
  }

  override fun progress(transferId: String, percentage: Int) {
    // Nothing to do: the assertion is a plain on/off lever with no progress dimension.
  }

  override fun end(transferId: String) {
    onMain {
      if (!active.remove(transferId)) return@onMain
      if (active.isEmpty()) {
        assertion?.let { NSProcessInfo.processInfo.endActivity(it) }
        assertion = null
        log("MacTransferAnchor", "All transfers finished; idle sleep allowed again")
      }
    }
  }

  private fun onMain(block: () -> Unit) {
    NSOperationQueue.mainQueue.addOperationWithBlock {
      runCatching(block).onFailure { log("MacTransferAnchor", "Anchor update failed", it) }
    }
  }
}
