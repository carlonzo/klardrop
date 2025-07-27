package com.carlom.klardrop.common

import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.database.DriverFactory
import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
<<<<<<< HEAD
import com.carlom.klardrop.common.trust.storage.SecureKeyStorageFactory
=======
import com.carlom.klardrop.common.trust.db.DatabaseDriverFactory
>>>>>>> 5e89d39 (refactor(trust/storage): remove SecureKeyStorageFactory and use PlatformSecureKeyStorage directly)
import com.carlom.klardrop.common.trust.storage.PlatformSecureKeyStorage
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.downloadDir
import io.github.vinceglb.filekit.toKotlinxIoPath
import kotlinx.io.files.Path
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf


actual class InternalPlatformDependencies {

  actual fun getDownloadStoragePath(): Path {
    return FileKit.downloadDir.toKotlinxIoPath()
  }

  actual fun serviceDiscoveryMdns(): ServiceDiscoveryMdns {
    return ServiceDiscoveryMdns()
  }

  actual fun clipboardReaderWriter(): ClipboardReaderWriter {
    return ClipboardReaderWriter()
  }
  
  actual fun platformSecureKeyStorage(): PlatformSecureKeyStorage {
    return PlatformSecureKeyStorage()
  }

  actual fun driverFactory(): DriverFactory {
    return DriverFactory()
  }

  actual suspend fun openFile(filePath: String): Boolean {
    return try {
      val file = java.io.File(filePath)
      if (file.exists()) {
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
          java.awt.Desktop.getDesktop().open(file)
          true
        } else {
          false
        }
      } else {
        false
      }
    } catch (e: Exception) {
      false
    }
  }
}