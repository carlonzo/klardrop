package com.carlom.klardrop.common

import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.PlatformFileSystem
import okio.Path
import okio.Path.Companion.toPath


actual class InternalPlatformDependencies {

  private val homeFolder = (System.getenv("HOME"))

  actual fun getRootPath(): String {
    return "$homeFolder/"
  }

  actual fun getDeviceName(): String {
    return readFromBash("hostname").removeSuffix(".local")
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

  actual fun serviceDiscoveryMdns(): ServiceDiscoveryMdns {
    return ServiceDiscoveryMdns()
  }

  actual fun osType(): OsType {
    val os = System.getProperty("os.name", "generic").lowercase()
    return when {
      os.contains("mac") || os.contains("darwin") -> OsType.APPLE
      os.contains("win") -> OsType.WINDOWS
      os.contains("nux") -> OsType.LINUX
      else -> OsType.UNKNOWN
    }
  }

  private fun readFromBash(vararg command: String): String {
    return ProcessBuilder(*command).start().inputStream.use { it.bufferedReader().readText().trim() }
  }
}