package com.carlom.klardrop

public interface OnDeviceActionListener {
  public fun onDeviceClick(deviceUi: DeviceUi): Unit {
    throw IllegalStateException("Not implemented")
  }

  public fun onSendData(deviceUi: DeviceUi, onDataToSend: OnDataToSend): Unit {
    throw IllegalStateException("Not implemented")
  }

  public fun onAddToTrusted(deviceUi: DeviceUi): Unit {
    throw IllegalStateException("Not implemented")
  }

  /**
   * User confirmed "Forget this device" from the trusted-row overflow menu. Implementations
   * should both remove the local trust entry AND notify the peer (signed
   * TrustRevocationMessage) so the relationship is dissolved symmetrically.
   */
  public fun onForgetDevice(deviceUi: DeviceUi): Unit {
    throw IllegalStateException("Not implemented")
  }

  /** User tapped Dismiss on a notification banner. */
  public fun onNotificationDismissed(notificationId: Int): Unit {
    throw IllegalStateException("Not implemented")
  }

  /** User tapped Pair on a "peer revoked trust" notification — re-initiate pairing. */
  public fun onNotificationPair(notificationId: Int): Unit {
    throw IllegalStateException("Not implemented")
  }
}
