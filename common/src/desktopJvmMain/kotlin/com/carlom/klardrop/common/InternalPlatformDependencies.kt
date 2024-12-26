package com.carlom.klardrop.common

import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.persistence.CurrentFileSystem
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.PlatformFileSystem
import okio.Path
import okio.Path.Companion.toPath


actual class InternalPlatformDependencies {

  private val homeFolder = (System.getenv("HOME"))

  actual fun getRootPath(): Path {
    return homeFolder.toPath()
  }

  actual fun getStoragePath(): Path {
    return "$homeFolder/Downloads/".toPath()
  }

  actual fun platformFileSystem(): PlatformFileSystem {
    return PlatformFileSystem(this)
  }

  actual fun serviceDiscoveryMdns(): ServiceDiscoveryMdns {
    return ServiceDiscoveryMdns()
  }

  actual fun clipboardReaderWriter(): ClipboardReaderWriter {
    return ClipboardReaderWriter()
  }
}