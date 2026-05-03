package com.carlom.klardrop.common.features

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import com.carlom.klardrop.common.communication.message.ConnectionInfoMessage
import com.carlom.klardrop.common.communication.message.ConnectionKind
import com.carlom.klardrop.common.utils.log

/**
 * Android implementation: on Android 10 (API 29) and above we submit a
 * [WifiNetworkSuggestion] for the received credentials, so the OS prompts the user with
 * a one-tap join notification. On older devices we fall back to copying the password to
 * the clipboard and opening the Wi-Fi settings screen — user pastes and joins manually.
 *
 * Requires `ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE` permissions (declared in
 * androidMain/AndroidManifest.xml).
 */
class AndroidConnectionInfoJoiner(
  private val context: Context,
  private val clipboardReaderWriter: ClipboardReaderWriter,
) : ConnectionInfoJoiner {

  override suspend fun tryJoin(message: ConnectionInfoMessage): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      return fallbackToClipboard(message)
    }
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
      ?: return fallbackToClipboard(message)

    val suggestion = WifiNetworkSuggestion.Builder()
      .setSsid(message.ssid)
      .apply {
        when (message.kind) {
          ConnectionKind.WIFI_OPEN -> { /* no credentials */ }
          ConnectionKind.WIFI_WEP,
          ConnectionKind.WIFI_WPA,
          ConnectionKind.WIFI_WPA2 -> message.password?.let { setWpa2Passphrase(it) }
          ConnectionKind.WIFI_WPA3 -> message.password?.let { setWpa3Passphrase(it) }
          ConnectionKind.WIFI_WPA_ENTERPRISE -> return fallbackToClipboard(message)
        }
      }
      .setIsHiddenSsid(message.hidden)
      .build()

    val result = wifiManager.addNetworkSuggestions(listOf(suggestion))
    return if (result == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
      log(TAG, "WifiNetworkSuggestion added for ssid=${message.ssid}; user will see a prompt")
      true
    } else {
      log(TAG, "addNetworkSuggestions returned $result; falling back to clipboard")
      fallbackToClipboard(message)
    }
  }

  private fun fallbackToClipboard(message: ConnectionInfoMessage): Boolean {
    message.password?.let { clipboardReaderWriter.write(it) }
    runCatching {
      val intent = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.applicationContext.startActivity(intent)
    }
    return false
  }

  private companion object {
    const val TAG = "AndroidConnectionInfoJoiner"
  }
}
