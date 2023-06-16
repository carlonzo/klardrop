package com.carlom.klardrop.common

import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.PlatformFileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.*
import platform.UIKit.UIDevice

actual class InternalPlatformDependencies {
  actual fun getRootPath(): String {
    return NSHomeDirectory()
  }

  actual fun getDeviceName(): String {
    return UIDevice.currentDevice.name
  }

  actual fun deviceType(): DeviceType {
    return DeviceType.MOBILE
  }

  actual fun getStoragePath(): Path {
    val downloadDirectory = NSFileManager.defaultManager.URLForDirectory(
      directory = NSDownloadsDirectory,
      inDomain = NSUserDomainMask,
      appropriateForURL = null,
      create = false,
      error = null
    )

    return requireNotNull(downloadDirectory).path?.toPath()?.resolve("Klardrop")!!
  }
  actual fun getTempStoragePath(): Path {
    return NSTemporaryDirectory().toPath()
  }

  actual fun platformFileSystem(): PlatformFileSystem {
    return PlatformFileSystem()
  }

  actual fun serviceDiscoveryMdns(): ServiceDiscoveryMdns {
    return ServiceDiscoveryMdns()
  }

  actual fun osType(): OsType {
    return OsType.APPLE
  }

}