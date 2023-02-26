package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.DeviceType

actual class InternalPlatformDependencies {
  actual fun getRootPath(): String {
    return System.getenv("HOME") + "/" ?: "~"
  }

  actual fun getDeviceName(): String {
    return ProcessBuilder("hostname").start().inputStream.use { it.bufferedReader().readText() }
  }

  actual fun deviceType(): DeviceType {
    return DeviceType.DESKTOP
  }

}