package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import platform.Foundation.NSHost

public actual object CommonPlatformDependencies {
  public actual fun osType(): OsType {
    return OsType.APPLE
  }

  public actual fun deviceType(): DeviceType {
    return DeviceType.DESKTOP
  }

  public actual fun getDeviceName(): String {
    return NSHost.currentHost().localizedName ?: "Mac"
  }
}
