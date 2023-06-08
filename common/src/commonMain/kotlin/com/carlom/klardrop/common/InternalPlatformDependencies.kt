package com.carlom.klardrop.common

import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.PlatformFileSystem
import okio.Path

expect class InternalPlatformDependencies {
  fun getRootPath(): String
  fun getDeviceName(): String
  fun deviceType(): DeviceType
  fun getStoragePath(): Path
  fun getTempStoragePath(): Path
  fun platformFileSystem(): PlatformFileSystem
  fun serviceDiscoveryMdns(): ServiceDiscoveryMdns
}