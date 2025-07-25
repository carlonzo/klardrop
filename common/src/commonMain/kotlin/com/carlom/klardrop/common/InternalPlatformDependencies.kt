package com.carlom.klardrop.common

import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.trust.db.DatabaseDriverFactory
import com.carlom.klardrop.common.trust.storage.SecureKeyStorageFactory
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import kotlinx.io.files.Path

expect class InternalPlatformDependencies {
  fun getDownloadStoragePath(): Path
  fun serviceDiscoveryMdns(): ServiceDiscoveryMdns
  fun clipboardReaderWriter(): ClipboardReaderWriter
  fun databaseDriverFactory(): DatabaseDriverFactory
  fun secureKeyStorageFactory(): SecureKeyStorageFactory
}

expect object CommonPlatformDependencies {
  fun osType(): OsType
  fun deviceType(): DeviceType
  fun getDeviceName(): String
}