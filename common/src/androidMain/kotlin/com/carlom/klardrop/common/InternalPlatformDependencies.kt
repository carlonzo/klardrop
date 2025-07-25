package com.carlom.klardrop.common

import android.content.Context
import android.os.Environment
import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.trust.db.DatabaseDriverFactory
import com.carlom.klardrop.common.trust.storage.SecureKeyStorageFactory
import kotlinx.io.files.Path

actual class InternalPlatformDependencies(private val context: Context) {

  actual fun getDownloadStoragePath(): Path {
    return Path(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
  }

  actual fun serviceDiscoveryMdns(): ServiceDiscoveryMdns {
    return ServiceDiscoveryMdns(context)
  }

  actual fun clipboardReaderWriter(): ClipboardReaderWriter {
    return ClipboardReaderWriter(context)
  }
  
  actual fun databaseDriverFactory(): DatabaseDriverFactory {
    return DatabaseDriverFactory(context)
  }
  
  actual fun secureKeyStorageFactory(): SecureKeyStorageFactory {
    return SecureKeyStorageFactory()
  }

}