package com.carlom.klardrop.common

import android.content.Context
import android.os.Environment
import com.carlom.klardrop.common.database.DriverFactory
import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.trust.storage.PlatformSecureKeyStorage
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
  
  actual fun platformSecureKeyStorage(): PlatformSecureKeyStorage {
    return PlatformSecureKeyStorage()
  }

  actual fun driverFactory(): DriverFactory {
    return DriverFactory(context)
  }

  actual fun databaseDriverFactory(): DriverFactory {
    return DriverFactory(context)
  }

  actual suspend fun openFile(filePath: String): Boolean {
    return try {
      val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        val file = java.io.File(filePath)
        val uri = androidx.core.content.FileProvider.getUriForFile(
          context, 
          "${context.packageName}.provider", 
          file
        )
        setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      
      if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
        true
      } else {
        false
      }
    } catch (e: Exception) {
      false
    }
  }
}