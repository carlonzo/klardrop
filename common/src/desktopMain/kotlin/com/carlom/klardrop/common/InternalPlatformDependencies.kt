package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.FileResolver
import okio.BufferedSource
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.source
import java.io.File

actual class InternalPlatformDependencies {

  private val homeFolder = (System.getenv("HOME"))

  actual fun getRootPath(): String {
    return ("$homeFolder/")
  }

  actual fun getDeviceName(): String {
    return ProcessBuilder("hostname").start().inputStream.use { it.bufferedReader().readText().trim() }
  }

  actual fun deviceType(): DeviceType {
    return DeviceType.DESKTOP
  }

  actual fun getStoragePath(): Path {
    return ("$homeFolder/Downloads/").toPath()
  }

  actual fun fileResolver(): FileResolver {
    return FileResolver()
  }

}