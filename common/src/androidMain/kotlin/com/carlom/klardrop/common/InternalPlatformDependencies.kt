package com.carlom.klardrop.common

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import com.carlom.klardrop.common.ble.BleTransport
import com.carlom.klardrop.common.database.DriverFactory
import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.trust.AndroidTrustStorage
import com.carlom.klardrop.common.trust.TrustStorage
import kotlinx.io.files.Path
import java.io.File

actual class InternalPlatformDependencies(private val context: Context, private val applicationInfo: ApplicationInfo) {

  private val bleTransport by lazy { BleTransport(context) }

  actual fun getDownloadStoragePath(): Path {
    return Path(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
  }

  actual fun serviceDiscoveryMdns(): ServiceDiscoveryMdns {
    return ServiceDiscoveryMdns(context)
  }

  actual fun bleTransport(): BleTransport = bleTransport

  actual fun clipboardReaderWriter(): ClipboardReaderWriter {
    return ClipboardReaderWriter(context)
  }

  actual fun driverFactory(): DriverFactory {
    return DriverFactory(context)
  }

  actual fun trustStorage(): TrustStorage {
    return AndroidTrustStorage(context)
  }

  actual suspend fun openFile(filePath: String): Boolean {
    return try {
      val intent = Intent(Intent.ACTION_VIEW).apply {
        val file = File(filePath)
        val uri = FileProvider.getUriForFile(
          context,
          "${context.packageName}.provider",
          file
        )
        setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
