package com.carlom.klardrop.common

import android.os.Build
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType

actual object CommonPlatformDependencies {
  actual fun osType(): OsType {
    return OsType.ANDROID
  }

  actual fun deviceType(): DeviceType {
    return DeviceType.MOBILE
  }

  actual fun getDeviceName(): String {
    return Build.MODEL
  }
}