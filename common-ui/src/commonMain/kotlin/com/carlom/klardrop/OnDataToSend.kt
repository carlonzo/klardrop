package com.carlom.klardrop

import com.carlom.klardrop.common.communication.message.ConnectionKind
import io.github.vinceglb.filekit.PlatformFile

sealed interface OnDataToSend {
  data class Text(val text: String) : OnDataToSend
  data class FilesList(val files: List<PlatformFile>) : OnDataToSend

  /**
   * Send a Wi-Fi (or future network) credential bundle so the receiving device can auto-join.
   * Delivered as `ConnectionInfoMessage` on the wire.
   */
  data class WifiCredentials(
    val ssid: String,
    val password: String?,
    val kind: ConnectionKind,
    val hidden: Boolean = false,
  ) : OnDataToSend
}