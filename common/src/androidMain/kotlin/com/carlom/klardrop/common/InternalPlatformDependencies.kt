package com.carlom.klardrop.common

import android.content.Context
import android.os.Build
import com.carlom.klardrop.common.utils.DeviceType

actual class InternalPlatformDependencies(private val context: Context) {

  actual fun getRootPath(): String {
    return context.filesDir.absolutePath
  }

  actual fun getDeviceName(): String {
    return Build.MODEL
  }

  actual fun deviceType(): DeviceType {
    return DeviceType.MOBILE
  }

}