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

  fun onRemoveTrust(deviceUi: DeviceUi) {
    throw IllegalStateException("Not implemented")
  }
}
