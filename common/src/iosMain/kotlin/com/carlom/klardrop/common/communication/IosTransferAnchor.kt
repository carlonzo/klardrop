package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.utils.log
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication

/**
 * iOS/iPadOS [TransferAnchor]: keeps the screen awake — and the app in the foreground — for as long
 * as any file transfer is in flight.
 *
 * iOS gives a plain socket app no background execution mode: once the process is suspended the
 * connection is torn down and a half-streamed 2 GB file is lost. There is no `dataSync` equivalent
 * to ask for, and none of the declared `UIBackgroundModes` legitimately covers "keep my TCP socket
 * running". The only lever that actually works is to stop the device going to sleep in the first
 * place, so a long transfer the user started stays on screen and running until it finishes.
 *
 * Two levers, both released the moment the last transfer ends:
 *  - [UIApplication.idleTimerDisabled] — the display never auto-locks while transferring. Without
 *    it a 10-minute send dies at whatever the user's auto-lock is set to (30 s by default).
 *  - `beginBackgroundTask` — buys the standard grace window (~30 s) if the user *does* switch away
 *    mid-transfer. It can't carry a long transfer to completion, but it stops a momentary app
 *    switch (glancing at a notification) from killing one instantly, and it lets short transfers
 *    finish in the background rather than being suspended at 90%.
 *
 * All UIKit calls are hopped onto the main queue, which also serializes [active]: the anchor is
 * driven from the messenger's IO scope, so [begin]/[progress]/[end] arrive on arbitrary threads.
 * Reference-counted by transfer id so overlapping transfers (send one file, receive another) only
 * release the screen when the last of them is done.
 */
class IosTransferAnchor : TransferAnchor {

  /** Ids of in-flight transfers. Touched only from the main queue — see [onMain]. */
  private val active = mutableSetOf<String>()

  /** Non-null while we hold a background task assertion. Main-queue confined, like [active]. */
  private var backgroundTaskId: ULong? = null

  override fun begin(transferId: String, label: String, direction: TransferAnchor.Direction) {
    onMain {
      if (!active.add(transferId)) return@onMain
      if (active.size == 1) acquire()
    }
  }

  override fun progress(transferId: String, percentage: Int) {
    // Nothing to do: the screen is already held awake and iOS surfaces transfer progress in-app.
  }

  override fun end(transferId: String) {
    onMain {
      if (!active.remove(transferId)) return@onMain
      if (active.isEmpty()) release()
    }
  }

  private fun acquire() {
    val application = UIApplication.sharedApplication
    application.idleTimerDisabled = true
    // Named so it's identifiable in a suspension/energy trace. The expiration handler MUST end the
    // task — iOS kills the app outright if a background task outlives its allowance.
    backgroundTaskId = application.beginBackgroundTaskWithName("klardrop.transfer") {
      log("IosTransferAnchor", "Background task expired with transfers still in flight")
      endBackgroundTask()
    }
    log("IosTransferAnchor", "Transfer started; holding the screen awake")
  }

  private fun release() {
    UIApplication.sharedApplication.idleTimerDisabled = false
    endBackgroundTask()
    log("IosTransferAnchor", "All transfers finished; screen may sleep again")
  }

  private fun endBackgroundTask() {
    val taskId = backgroundTaskId ?: return
    backgroundTaskId = null
    UIApplication.sharedApplication.endBackgroundTask(taskId)
  }

  /**
   * UIKit is main-thread-only, and the anchor is called from IO threads. Hopping every mutation
   * onto the main queue keeps [active] and [backgroundTaskId] confined to a single thread, so
   * neither needs its own lock.
   */
  private fun onMain(block: () -> Unit) {
    NSOperationQueue.mainQueue.addOperationWithBlock {
      runCatching(block).onFailure { log("IosTransferAnchor", "Anchor update failed", it) }
    }
  }
}
