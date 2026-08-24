package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import java.io.File
import java.net.InetAddress

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

  /**
   * The machine's hostname, without spawning a process.
   *
   * This used to be `hostname`(1) via ProcessBuilder, which threw
   * `IOException: Cannot run program "hostname"` whenever the app was launched with a
   * stripped PATH (packaged .desktop launch on Linux). That throw happened inside
   * `CurrentDevice.deviceInfoFlow`'s map, so the device never advertised itself at all.
   * Resolved once and cached: the flow re-collects on every property change.
   */
  actual fun getDeviceName(): String = hostname
}

private val hostname: String by lazy {
  val name = System.getenv("COMPUTERNAME")?.trim()?.ifEmpty { null }
    ?: File("/etc/hostname").takeIf { it.canRead() }?.let {
      runCatching { it.readText().trim() }.getOrNull()?.ifEmpty { null }
    }
    ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.trim()?.ifEmpty { null }
    ?: "Desktop"
  name.removeSuffix(".local")
}
