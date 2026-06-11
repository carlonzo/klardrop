package com.carlom.klardrop

interface OnDeviceActionListener {
  fun onDeviceClick(deviceUi: DeviceUi) {
    throw IllegalStateException("Not implemented")
  }

  fun onSendData(deviceUi: DeviceUi, onDataToSend: OnDataToSend) {
    throw IllegalStateException("Not implemented")
  }

  fun onAddToTrusted(deviceUi: DeviceUi) {
    throw IllegalStateException("Not implemented")
  }

  /**
   * User confirmed "Forget this device" from the trusted-row overflow menu. Implementations
   * should both remove the local trust entry AND notify the peer (signed
   * TrustRevocationMessage) so the relationship is dissolved symmetrically.
   */
  fun onForgetDevice(deviceUi: DeviceUi) {
    throw IllegalStateException("Not implemented")
  }

  /** User tapped Dismiss on a notification banner. */
  fun onNotificationDismissed(notificationId: Int) {
    throw IllegalStateException("Not implemented")
  }

  /** User tapped Pair on a "peer revoked trust" notification — re-initiate pairing. */
  fun onNotificationPair(notificationId: Int) {
    throw IllegalStateException("Not implemented")
  }
}
