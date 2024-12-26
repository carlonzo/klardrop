package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import platform.UIKit.UIDevice

actual object CommonPlatformDependencies {
  actual fun osType(): OsType {
    return OsType.APPLE
  }

  actual fun deviceType(): DeviceType {
    return DeviceType.MOBILE
  }

  actual fun getDeviceName(): String {
    return UIDevice.currentDevice.name
  }
}