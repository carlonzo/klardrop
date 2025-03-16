package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType

actual object CommonPlatformDependencies {
  actual fun osType(): OsType {
    val os = System.getProperty("os.name", "generic").lowercase()
    return when {
      os.contains("mac") || os.contains("darwin") -> OsType.APPLE

      os.contains("win") -> OsType.WINDOWS

      os.contains("nix") ||
          os.contains("nux") ||
          os.contains("aix") -> OsType.LINUX

      else -> OsType.LINUX
    }
  }

  actual fun deviceType(): DeviceType {
    return DeviceType.DESKTOP
  }

  actual fun getDeviceName(): String {
    return readFromBash("hostname").removeSuffix(".local")
  }

}

internal fun readFromBash(vararg command: String): String {
  return ProcessBuilder(*command).start().inputStream.use { it.bufferedReader().readText().trim() }
}