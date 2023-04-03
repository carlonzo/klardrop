package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.DeviceType
import okio.Path

actual class InternalPlatformDependencies {
  actual fun getRootPath(): String {
    TODO("Not yet implemented")
  }

  actual fun getDeviceName(): String {
    TODO("Not yet implemented")
  }

  actual fun deviceType(): DeviceType {
    TODO("Not yet implemented")
  }

  actual fun getStoragePath(): Path {
    TODO("Not yet implemented")
  }

}