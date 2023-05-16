package com.carlom.klardrop

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.carlom.klardrop.common.utils.DeviceType

@Composable
actual fun DeviceDiscovery(deviceUi: DeviceUi, onDeviceActionListener: OnDeviceActionListener) {
  DeviceSmall(deviceUi, onDeviceActionListener)
}