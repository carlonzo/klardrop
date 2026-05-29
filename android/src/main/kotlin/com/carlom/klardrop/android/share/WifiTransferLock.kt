package com.carlom.klardrop.android.share

import android.content.Context
import android.net.wifi.WifiManager
import com.carlom.klardrop.common.utils.log

/**
 * Holds a [WifiManager.WifiLock] for the duration of a file transfer so WiFi power-save doesn't
 * throttle or drop the connection mid-stream.
 *
 * Reference-counted: balanced [acquire]/[release] pairs nest safely (the OS lock stays held while
 * the count is > 0). Every public method is synchronized and swallows exceptions, so an unbalanced
 * call can never crash a transfer or leak a "WifiLock under-locked" — callers MUST still pair every
 * [acquire] with a [release] in a `finally`, but a stray extra [release] is harmless.
 *
 * Note: this only keeps the *radio* awake. Keeping the whole process alive while backgrounded is a
 * separate concern (the opt-in discovery foreground service); a WifiLock alone can't un-freeze a
 * Doze-suspended process.
 */
class WifiTransferLock(context: Context) {

  private val lock: WifiManager.WifiLock =
    (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
      // We don't need low latency — only that WiFi doesn't power down mid-transfer. HIGH_PERF keeps
      // the radio fully awake regardless of foreground state, unlike LOW_LATENCY which only engages
      // while the app is foregrounded (and our transfer outlives the share sheet).
      .createWifiLock(@Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF, "klardrop:transfer")
      .apply { setReferenceCounted(true) }

  @Synchronized
  fun acquire() {
    runCatching { lock.acquire() }
      .onFailure { log("WifiTransferLock", "acquire failed", it) }
  }

  @Synchronized
  fun release() {
    // Guard on isHeld so an unbalanced release can't throw "WifiLock under-locked".
    runCatching { if (lock.isHeld) lock.release() }
      .onFailure { log("WifiTransferLock", "release failed", it) }
  }
}
