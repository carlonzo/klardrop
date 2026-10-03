package com.carlom.klardrop.common

import com.carlom.klardrop.common.ble.BleTransport
import com.carlom.klardrop.common.database.DriverFactory
import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.features.ConnectionInfoJoiner
import com.carlom.klardrop.common.features.FallbackClipboardConnectionInfoJoiner
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.network.NetworkLifecycleMonitor
import com.carlom.klardrop.common.notifications.ForegroundState
import com.carlom.klardrop.common.notifications.Notifier
import com.carlom.klardrop.common.connectivity.ConnectivityRestrictionMonitor
import com.carlom.klardrop.common.permissions.PermissionsMonitor
import com.carlom.klardrop.common.trust.LinuxTrustStorage
import com.carlom.klardrop.common.trust.TrustStorage
import com.carlom.klardrop.common.utils.LinuxPaths
import com.carlom.klardrop.common.utils.ensureDirectory
import com.carlom.klardrop.common.utils.execProcess
import kotlinx.io.files.Path
import platform.posix.F_OK
import platform.posix.access

actual class InternalPlatformDependencies(private val applicationInfo: ApplicationInfo) {

  actual fun getDownloadStoragePath(): Path {
    // The JVM host gets this for free: FileKit's `downloadDir` creates the
    // directory before returning it. The native engine resolves the path itself,
    // so without this a receiver whose ~/Downloads does not exist yet fails at
    // finalize with ACK_REJECTED and the sender is told the transfer failed for a
    // reason that has nothing to do with the peer. Creation is not chmod: an
    // existing user-owned directory keeps the mode the user gave it.
    val dir = LinuxPaths.downloadDir
    ensureDirectory(dir)
    return Path(dir)
  }

  actual fun serviceDiscoveryMdns(): ServiceDiscoveryMdns = createServiceDiscoveryMdns()

  private val networkLifecycleMonitor by lazy { NetworkLifecycleMonitor() }
  actual fun networkLifecycleMonitor(): NetworkLifecycleMonitor = networkLifecycleMonitor

  actual fun lanAddressSelector(): com.carlom.klardrop.common.qrshare.LanAddressSelector =
    com.carlom.klardrop.common.qrshare.PlatformLanAddressSelector()

  private val permissionsMonitor by lazy { PermissionsMonitor() }
  actual fun permissionsMonitor(): PermissionsMonitor = permissionsMonitor

  private val connectivityRestrictionMonitor by lazy { ConnectivityRestrictionMonitor() }
  actual fun connectivityRestrictionMonitor(): ConnectivityRestrictionMonitor = connectivityRestrictionMonitor

  private val notifier by lazy { Notifier() }
  actual fun notifier(): Notifier = notifier

  private val foregroundState by lazy { ForegroundState() }
  actual fun foregroundState(): ForegroundState = foregroundState

  private val bleTransport by lazy { BleTransport() }
  actual fun bleTransport(): BleTransport = bleTransport

  actual fun clipboardReaderWriter(): ClipboardReaderWriter = ClipboardReaderWriter()

  actual fun connectionInfoJoiner(): ConnectionInfoJoiner =
    FallbackClipboardConnectionInfoJoiner(clipboardReaderWriter())

  actual fun driverFactory(): DriverFactory =
    DriverFactory(Path(LinuxPaths.databasesDir), applicationInfo.disablePersistence)

  private val trustStorageInstance by lazy { LinuxTrustStorage(LinuxPaths.trustDir) }
  actual fun trustStorage(): TrustStorage = trustStorageInstance

  actual suspend fun openFile(filePath: String): Boolean {
    if (access(filePath, F_OK) != 0) return false
    return try {
      val result = execProcess(listOf("xdg-open", filePath))
      result.exitCode == 0
    } catch (_: Exception) {
      false
    }
  }

  actual suspend fun openUrl(url: String): Boolean {
    return try {
      val result = execProcess(listOf("xdg-open", url))
      result.exitCode == 0
    } catch (_: Exception) {
      false
    }
  }

  actual suspend fun saveMediaToGallery(
    tempPath: Path,
    mimeType: String,
    displayName: String,
  ): String? = null
}
