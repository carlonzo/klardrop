package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.PlatformFileSystem
import okio.Path
import okio.Path.Companion.toPath

actual class InternalPlatformDependencies {

  private val homeFolder = (System.getenv("HOME"))

  actual fun getRootPath(): String {
    return "$homeFolder/"
  }

  actual fun getDeviceName(): String {
    return ProcessBuilder("hostname").start().inputStream.use { it.bufferedReader().readText().trim() }
  }

  actual fun deviceType(): DeviceType {
    return DeviceType.DESKTOP
  }

  actual fun getStoragePath(): Path {
    return "$homeFolder/Downloads/".toPath()
  }

  actual fun getTempStoragePath(): Path {
    return System.getenv("TMPDIR").toPath()
  }

  actual fun platformFileSystem(): PlatformFileSystem {
    return PlatformFileSystem(this)
  }

}