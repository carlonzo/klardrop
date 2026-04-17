package com.carlom.klardrop.common

import com.carlom.klardrop.common.ble.BleTransport
import com.carlom.klardrop.common.database.DriverFactory
import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.trust.TrustStorage
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import kotlinx.io.files.Path

expect class InternalPlatformDependencies {
  fun getDownloadStoragePath(): Path
  fun serviceDiscoveryMdns(): ServiceDiscoveryMdns
  fun bleTransport(): BleTransport
  fun clipboardReaderWriter(): ClipboardReaderWriter
  fun driverFactory(): DriverFactory
  fun trustStorage(): TrustStorage
  suspend fun openFile(filePath: String): Boolean
}

expect object CommonPlatformDependencies {
  fun osType(): OsType
  fun deviceType(): DeviceType
  fun getDeviceName(): String
}
