package com.carlom.klardrop.android.share

import android.content.Context
import android.os.PowerManager
import com.carlom.klardrop.common.utils.log
import kotlin.time.Duration.Companion.hours

/**
 * Holds a `PARTIAL_WAKE_LOCK` for the duration of a file transfer so the CPU keeps running with the
 * screen off.
 *
 * A foreground service stops the process being *killed*, and [WifiTransferLock] stops the radio
 * powering down — but neither keeps the CPU out of suspend. Once the screen turns off and the
 * device suspends, our socket reads and writes simply stop making progress until something else
 * wakes the SoC, which turns a 10-minute transfer into an indefinite one and usually ends in the
 * peer's heartbeat timing out. A partial wake lock is the piece that lets the user lock the phone
 * and put it in a pocket while a large file finishes, which is the whole point of running the
 * transfer in a service.
 *
 * Timed rather than indefinite: [MAX_HOLD] is a backstop so a lock leaked by some path that never
 * reaches its `release` drains the battery for an hour, not until reboot. It's far longer than any
 * realistic LAN transfer (an hour moves hundreds of GB over Wi-Fi), so it never cuts a live one
 * short. Reference-counted, and every method swallows exceptions — a wake-lock problem must never
 * take down a transfer.
 */
class TransferCpuLock(context: Context) {

  private val lock: PowerManager.WakeLock =
    (context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "klardrop:transfer")
      .apply { setReferenceCounted(true) }

  @Synchronized
  fun acquire() {
    runCatching { lock.acquire(MAX_HOLD) }
      .onFailure { log("TransferCpuLock", "acquire failed", it) }
  }

  @Synchronized
  fun release() {
    // Guard on isHeld so an unbalanced release can't throw "WakeLock under-locked", and so a lock
    // that already timed out on its own doesn't blow up on the way down.
    runCatching { if (lock.isHeld) lock.release() }
      .onFailure { log("TransferCpuLock", "release failed", it) }
  }

  private companion object {
    val MAX_HOLD = 1.hours.inWholeMilliseconds
  }
}
