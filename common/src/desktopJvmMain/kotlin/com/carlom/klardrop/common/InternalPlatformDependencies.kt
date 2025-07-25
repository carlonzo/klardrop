package com.carlom.klardrop.common

import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.trust.db.DatabaseDriverFactory
import com.carlom.klardrop.common.trust.storage.SecureKeyStorageFactory
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.downloadDir
import io.github.vinceglb.filekit.toKotlinxIoPath
import kotlinx.io.files.Path


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
  
  actual fun databaseDriverFactory(): DatabaseDriverFactory {
    return DatabaseDriverFactory()
  }
  
  actual fun secureKeyStorageFactory(): SecureKeyStorageFactory {
    return SecureKeyStorageFactory()
  }

}