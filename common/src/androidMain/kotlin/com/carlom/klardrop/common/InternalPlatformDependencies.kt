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
import com.carlom.klardrop.common.connectivity.ConnectivityRestrictionMonitor
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

public actual class InternalPlatformDependencies(private val context: Context, private val applicationInfo: ApplicationInfo) {

  private val bleTransport by lazy { BleTransport(context) }

  internal actual fun getDownloadStoragePath(): Path {
    return Path(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
  }

  internal actual fun serviceDiscoveryMdns(): ServiceDiscoveryMdns {
    return ServiceDiscoveryMdns(context)
  }

  private val networkLifecycleMonitor by lazy { NetworkLifecycleMonitor(context) }

  internal actual fun networkLifecycleMonitor(): NetworkLifecycleMonitor = networkLifecycleMonitor

  internal actual fun lanAddressSelector(): com.carlom.klardrop.common.qrshare.LanAddressSelector =
    com.carlom.klardrop.common.qrshare.PlatformLanAddressSelector(context)

  // Eager, not lazy: ForegroundState counts Activity starts from the moment it registers, and
  // this class is built in Application.onCreate — before any Activity starts. Created lazily, the
  // first read could land after MainActivity.onStart, leaving isForeground stuck false and
  // discovery OFF for the whole session (KLARDROP-KA).
  private val foregroundState = ForegroundState(context)

  internal actual fun foregroundState(): ForegroundState = foregroundState

  private val permissionsMonitor by lazy { PermissionsMonitor(context, foregroundState) }

  internal actual fun permissionsMonitor(): PermissionsMonitor = permissionsMonitor

  private val connectivityRestrictionMonitor by lazy {
    ConnectivityRestrictionMonitor(context, foregroundState)
  }

  internal actual fun connectivityRestrictionMonitor(): ConnectivityRestrictionMonitor = connectivityRestrictionMonitor

  private val notifier by lazy { Notifier(context) }

  internal actual fun notifier(): Notifier = notifier

  internal actual fun bleTransport(): BleTransport = bleTransport

  internal actual fun clipboardReaderWriter(): ClipboardReaderWriter {
    return ClipboardReaderWriter(context)
  }

  internal actual fun connectionInfoJoiner(): ConnectionInfoJoiner {
    return AndroidConnectionInfoJoiner(context, ClipboardReaderWriter(context))
  }

  internal actual fun driverFactory(): DriverFactory {
    return DriverFactory(context)
  }

  internal actual fun trustStorage(): TrustStorage {
    return AndroidTrustStorage(context)
  }

  internal actual suspend fun openFile(filePath: String): Boolean {
    return try {
      // file_path can be either a content:// URI (for media saved via MediaStore) or a
      // regular filesystem path (for everything else). Content URIs go straight into the
      // intent; filesystem paths are wrapped via FileProvider so the receiving app gets
      // a grantable URI it can read.
      val uri: Uri = when {
        filePath.startsWith("content://") -> Uri.parse(filePath)
        // MediaStore URIs are persisted by round-tripping through kotlinx.io Path, which on
        // Android is backed by java.io.File. File collapses the "//" in "content://" to a
        // single slash, so the stored value comes back as "content:/media/...". Restore the
        // scheme separator before parsing instead of treating it as a filesystem path.
        filePath.startsWith("content:/") ->
          Uri.parse("content://" + filePath.removePrefix("content:/"))
        else -> {
          val file = File(filePath)
          FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }
      }

      val mime = context.contentResolver.getType(uri) ?: "*/*"
      val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }

      // Don't pre-check with resolveActivity(): on Android 11+ package-visibility rules
      // make it return null even when capable apps exist, unless we declare every viewer
      // intent in <queries>. startActivity() itself isn't gated by package visibility,
      // so just dispatch and catch ActivityNotFoundException for the genuinely-no-handler
      // case. This means "Cant open this file type" only fires when the dispatch actually
      // fails, not when our visibility allowlist is incomplete.
      try {
        context.startActivity(intent)
        true
      } catch (notFound: android.content.ActivityNotFoundException) {
        log("InternalPlatformDependencies", "openFile($filePath): no handler for mime=$mime")
        false
      }
    } catch (e: Exception) {
      log("InternalPlatformDependencies", "openFile($filePath) failed: ${e.message}", e)
      false
    }
  }

  internal actual suspend fun openUrl(url: String): Boolean {
    return try {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      true
    } catch (notFound: android.content.ActivityNotFoundException) {
      log("InternalPlatformDependencies", "openUrl($url): no browser/handler available")
      false
    } catch (e: Exception) {
      log("InternalPlatformDependencies", "openUrl($url) failed: ${e.message}", e)
      false
    }
  }

  internal actual suspend fun saveMediaToGallery(
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
