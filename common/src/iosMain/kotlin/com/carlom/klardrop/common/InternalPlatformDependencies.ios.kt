package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import platform.UIKit.UIDevice

public actual object CommonPlatformDependencies {
  public actual fun osType(): OsType {
    return OsType.APPLE
  }

  public actual fun deviceType(): DeviceType {
    return DeviceType.MOBILE
  }

  public actual fun getDeviceName(): String {
    return UIDevice.currentDevice.name
  }
}