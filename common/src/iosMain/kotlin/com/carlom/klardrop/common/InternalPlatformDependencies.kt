package com.carlom.klardrop.common

import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.persistence.CurrentFileSystem
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.PlatformFileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.*
import platform.UIKit.UIDevice

actual class InternalPlatformDependencies {

  private val documentsDirectory: Path by lazy {
    val directory = NSFileManager.defaultManager.URLForDirectory(
      directory = NSDocumentDirectory,
      inDomain = NSUserDomainMask,
      appropriateForURL = null,
      create = false,
      error = null
    )

    requireNotNull(directory).path!!.toPath()
  }

  actual fun getRootPath(): Path {
    return documentsDirectory
  }

  actual fun getStoragePath(): Path {
    val klardropStoragePath = documentsDirectory.resolve("Klardrop")

    if (!CurrentFileSystem.exists(klardropStoragePath)) {
      CurrentFileSystem.createDirectory(klardropStoragePath, mustCreate = true)
    }

    return klardropStoragePath
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