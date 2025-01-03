package com.carlom.klardrop.common

import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.utils.PlatformFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.*

actual class InternalPlatformDependencies {

  private val documentsDirectory: Path by lazy {
    val directory = NSFileManager.defaultManager.URLForDirectory(
      directory = NSDocumentDirectory,
      inDomain = NSUserDomainMask,
      appropriateForURL = null,
      create = false,
      error = null
    )

    Path(requireNotNull(directory?.path))
  }

  private val appDirectory : Path by lazy {
    val directory = NSFileManager.defaultManager.URLForDirectory(
      directory = NSApplicationSupportDirectory,
      inDomain = NSUserDomainMask,
      appropriateForURL = null,
      create = false,
      error = null
    )

    Path(requireNotNull(directory?.path))
  }

  actual fun getDownloadStoragePath(): Path {
    val klardropStoragePath = Path(documentsDirectory, "Klardrop")

    if (!SystemFileSystem.exists(klardropStoragePath)) {
      SystemFileSystem.createDirectories(klardropStoragePath, mustCreate = true)
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

  actual fun getPrivateAppStoragePath(): Path {
    return appDirectory
  }
}