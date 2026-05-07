package com.carlom.klardrop.common

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.carlom.klardrop.common.ble.BleTransport
import com.carlom.klardrop.common.database.DriverFactory
import com.carlom.klardrop.common.features.AndroidConnectionInfoJoiner
import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.features.ConnectionInfoJoiner
import com.carlom.klardrop.common.mdns.ServiceDiscoveryMdns
import com.carlom.klardrop.common.network.NetworkLifecycleMonitor
import com.carlom.klardrop.common.notifications.ForegroundState
import com.carlom.klardrop.common.notifications.Notifier
import com.carlom.klardrop.common.permissions.PermissionsMonitor
import com.carlom.klardrop.common.trust.AndroidTrustStorage
import com.carlom.klardrop.common.trust.TrustStorage
import com.carlom.klardrop.common.utils.FileTypeUtils
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.File

actual class InternalPlatformDependencies(private val context: Context, private val applicationInfo: ApplicationInfo) {

  private val bleTransport by lazy { BleTransport(context) }

  actual fun getDownloadStoragePath(): Path {
    return Path(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
  }

  actual fun serviceDiscoveryMdns(): ServiceDiscoveryMdns {
    return ServiceDiscoveryMdns(context)
  }

  private val networkLifecycleMonitor by lazy { NetworkLifecycleMonitor(context) }

  actual fun networkLifecycleMonitor(): NetworkLifecycleMonitor = networkLifecycleMonitor

  private val permissionsMonitor by lazy { PermissionsMonitor(context) }

  actual fun permissionsMonitor(): PermissionsMonitor = permissionsMonitor

  private val notifier by lazy { Notifier(context) }

  actual fun notifier(): Notifier = notifier

  private val foregroundState by lazy { ForegroundState(context) }

  actual fun foregroundState(): ForegroundState = foregroundState

  actual fun bleTransport(): BleTransport = bleTransport

  actual fun clipboardReaderWriter(): ClipboardReaderWriter {
    return ClipboardReaderWriter(context)
  }

  actual fun connectionInfoJoiner(): ConnectionInfoJoiner {
    return AndroidConnectionInfoJoiner(context, ClipboardReaderWriter(context))
  }

  actual fun driverFactory(): DriverFactory {
    return DriverFactory(context)
  }

  actual fun trustStorage(): TrustStorage {
    return AndroidTrustStorage(context)
  }

  actual suspend fun openFile(filePath: String): Boolean {
    return try {
      // file_path can be either a content:// URI (for media saved via MediaStore) or a
      // regular filesystem path (for everything else). Content URIs go straight into the
      // intent; filesystem paths are wrapped via FileProvider so the receiving app gets
      // a grantable URI it can read.
      val uri: Uri = if (filePath.startsWith("content://")) {
        Uri.parse(filePath)
      } else {
        val file = File(filePath)
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
      }

      val mime = context.contentResolver.getType(uri) ?: "*/*"
      val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
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
      log("InternalPlatformDependencies", "openFile($filePath) failed: ${e.message}", e)
      false
    }
  }

  actual suspend fun saveMediaToGallery(
    tempPath: Path,
    mimeType: String,
    displayName: String,
  ): String? = withContext(Dispatchers.IO) {
    val isImage = FileTypeUtils.isImageMimeType(mimeType)
    val isVideo = FileTypeUtils.isVideoMimeType(mimeType)
    if (!isImage && !isVideo) return@withContext null

    val collection = if (isImage) {
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    } else {
      MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
      put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val relative = if (isImage) "Pictures/Klardrop" else "Movies/Klardrop"
        put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
    }

    val resolver = context.contentResolver
    val uri: Uri = try {
      resolver.insert(collection, values)
    } catch (t: Throwable) {
      log("InternalPlatformDependencies", "MediaStore insert failed: ${t.message}", t)
      null
    } ?: return@withContext null

    val tempFile = File(tempPath.toString())
    val ok = try {
      resolver.openOutputStream(uri)?.use { out ->
        tempFile.inputStream().use { input -> input.copyTo(out) }
        true
      } ?: false
    } catch (t: Throwable) {
      log("InternalPlatformDependencies", "MediaStore write failed for $uri: ${t.message}", t)
      false
    }

    if (!ok) {
      runCatching { resolver.delete(uri, null, null) }
      return@withContext null
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val finishValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
      runCatching { resolver.update(uri, finishValues, null, null) }
    }

    runCatching { SystemFileSystem.delete(tempPath) }
    uri.toString()
  }
}
