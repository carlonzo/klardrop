@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.readFileText
import kotlinx.cinterop.*
import platform.posix.*

public actual object CommonPlatformDependencies {
  public actual fun osType(): OsType = OsType.LINUX

  public actual fun deviceType(): DeviceType = DeviceType.DESKTOP

  public actual fun getDeviceName(): String = hostname
}

private val hostname: String by lazy {
  val name = getenv("COMPUTERNAME")?.toKString()?.trim()?.ifEmpty { null }
    ?: readFileText("/etc/hostname")?.trim()?.ifEmpty { null }
    ?: getPosixHostname()
    ?: "Desktop"
  name.removeSuffix(".local")
}

private fun getPosixHostname(): String? = memScoped {
  val buf = allocArray<ByteVar>(256)
  if (gethostname(buf, 256u) == 0) {
    buf.toKString().trim().ifEmpty { null }
  } else {
    null
  }
}
