package com.carlom.klardrop.common

import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.PlatformFileSystem
import kotlinx.io.files.Path

expect class InternalPlatformDependencies {
  fun getDownloadStoragePath(): Path
  fun getPrivateAppStoragePath(): Path
  fun platformFileSystem(): PlatformFileSystem
  fun serviceDiscoveryMdns(): ServiceDiscoveryMdns
  fun clipboardReaderWriter(): ClipboardReaderWriter
}

expect object CommonPlatformDependencies {
  fun osType(): OsType
  fun deviceType(): DeviceType
  fun getDeviceName(): String
}