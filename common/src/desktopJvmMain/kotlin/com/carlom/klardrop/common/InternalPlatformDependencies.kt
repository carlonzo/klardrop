package com.carlom.klardrop.common

import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.utils.PlatformFileSystem
import kotlinx.io.files.Path


actual class InternalPlatformDependencies {

  private val homeFolder = (System.getenv("HOME"))

  actual fun getDownloadStoragePath(): Path {
    return Path("$homeFolder/Downloads/")
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

  actual fun getPrivateAppStoragePath(): Path {
    return Path("$homeFolder/.klardrop/")
  }
}