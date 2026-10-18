package com.carlom.klardrop.common

import android.os.Build
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType

public actual object CommonPlatformDependencies {
  public actual fun osType(): OsType {
    return OsType.ANDROID
  }

  public actual fun deviceType(): DeviceType {
    return DeviceType.MOBILE
  }

  public actual fun getDeviceName(): String {
    return Build.MODEL ?: "Unknown model" // is null during tests. it should not be null
  }
}