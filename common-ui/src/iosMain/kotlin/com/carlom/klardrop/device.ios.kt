package com.carlom.klardrop

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun Modifier.deviceAdditions(
  deviceUi: DeviceUi,
  onDeviceActionListener: OnDeviceActionListener
): Modifier {
  return this
}

@Composable
internal actual fun BoxScope.DeviceContent(
  deviceUi: DeviceUi,
  onDeviceActionListener: OnDeviceActionListener
) {
}