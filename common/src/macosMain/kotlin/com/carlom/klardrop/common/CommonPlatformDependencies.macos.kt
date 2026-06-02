package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import platform.Foundation.NSHost

actual object CommonPlatformDependencies {
  actual fun osType(): OsType {
    return OsType.APPLE
  }

  actual fun deviceType(): DeviceType {
    return DeviceType.DESKTOP
  }

  actual fun getDeviceName(): String {
    return NSHost.currentHost().localizedName ?: "Mac"
  }
}
