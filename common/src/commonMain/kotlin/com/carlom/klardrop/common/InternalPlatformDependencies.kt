package com.carlom.klardrop.common

import com.carlom.klardrop.common.ble.BleTransport
import com.carlom.klardrop.common.database.DriverFactory
import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.features.ConnectionInfoJoiner
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
  fun connectionInfoJoiner(): ConnectionInfoJoiner
  fun driverFactory(): DriverFactory
  fun trustStorage(): TrustStorage
  suspend fun openFile(filePath: String): Boolean

  /**
   * Save the file at [tempPath] into the platform's media gallery and return a string the
   * caller can persist as the file's location (a `content://` URI on Android, a file path
   * elsewhere). When the platform doesn't expose a usable identifier — e.g. older
   * `FileKit.saveImageToGallery` paths — this returns null and the caller falls back to
   * its existing strategy (gallery save without a tracked path, etc.).
   *
   * On success the temp file SHOULD be removed by the implementation.
   */
  suspend fun saveMediaToGallery(tempPath: Path, mimeType: String, displayName: String): String?
}

expect object CommonPlatformDependencies {
  fun osType(): OsType
  fun deviceType(): DeviceType
  fun getDeviceName(): String
}
