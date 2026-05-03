package com.carlom.klardrop.common.features

import com.carlom.klardrop.common.communication.message.ConnectionInfoMessage

/**
 * Platform-specific handler for "accept" on an incoming [ConnectionInfoMessage].
 *
 * Android uses `WifiNetworkSuggestion` to propose the network so the user can one-tap join.
 * iOS uses `NEHotspotConfigurationManager`. macOS / Linux / Windows JVM fall back to copying
 * the password to the clipboard and surfacing the SSID in the UI — the user joins manually.
 *
 * Return value is `true` if the system natively offered to join the network, `false` if
 * the clipboard fallback was used (caller should show a toast / snackbar indicating the
 * password is now on the clipboard).
 */
interface ConnectionInfoJoiner {
  suspend fun tryJoin(message: ConnectionInfoMessage): Boolean
}
