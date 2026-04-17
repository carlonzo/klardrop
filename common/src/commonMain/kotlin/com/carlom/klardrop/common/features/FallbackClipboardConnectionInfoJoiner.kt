package com.carlom.klardrop.common.features

import com.carlom.klardrop.common.communication.message.ConnectionInfoMessage

/**
 * Platform-agnostic fallback [ConnectionInfoJoiner] that puts the password on the
 * clipboard and always reports `false` (caller should show a toast indicating the
 * user needs to join manually via system Wi-Fi settings).
 *
 * Used by macOS / Linux / Windows JVM builds where there is no direct system API to
 * propose a Wi-Fi join, and as a temporary fallback on iOS until NEHotspotConfiguration
 * lands.
 */
class FallbackClipboardConnectionInfoJoiner(
  private val clipboard: ClipboardReaderWriter,
) : ConnectionInfoJoiner {
  override suspend fun tryJoin(message: ConnectionInfoMessage): Boolean {
    message.password?.let { clipboard.write(it) }
    return false
  }
}
