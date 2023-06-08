package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.utils.DeviceType

data class CurrentDevice(
  val deviceId: String,
  val deviceName: String,
  val deviceType: DeviceType,
  val nearbyShareName: String,
)