package com.carlom.klardrop.common.utils

import com.carlom.klardrop.common.CommonPlatformDependencies
import com.carlom.klardrop.common.InternalPlatformDependencies
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.atomicMove
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.extension
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.projectDir
import io.github.vinceglb.filekit.saveImageToGallery
import io.github.vinceglb.filekit.size
import io.github.vinceglb.filekit.source
import io.github.vinceglb.filekit.toKotlinxIoPath
import kotlinx.coroutines.withContext
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.sink

interface PlatformFileSystem {
  fun getReadStreamFrom(platformFile: PlatformFile): RawSource

  fun getWriteStreamTo(path: Path): RawSink

  fun getResolvedFileData(platformFile: PlatformFile): ResolvedFileData

  suspend fun delete(path: Path)

  suspend fun moveToStorage(path: Path, mimeType: String)

  fun getTempStoragePath(): Path

  fun getInternalStoragePath(): Path

  suspend fun openFile(filePath: String): Boolean
}

internal class PlatformFileSystemImpl(
  private val platformDependencies: InternalPlatformDependencies,
  private val coroutines: Coroutines
) : PlatformFileSystem {

  override fun getReadStreamFrom(platformFile: PlatformFile): RawSource{
    return platformFile.source()
  }

  override fun getWriteStreamTo(path: Path): RawSink {
    return SystemFileSystem.sink(path, append = false)
  }

  override fun getResolvedFileData(platformFile: PlatformFile): ResolvedFileData {
    return ResolvedFileData(
      fileName = platformFile.name,
      mimeType = platformFile.mimeType(),
      fileSize = platformFile.size()
    )
  }

  override suspend fun delete(path: Path) {
    PlatformFile(path).delete()
  }

  override suspend fun moveToStorage(path: Path, mimeType: String) {
    val platformFile = PlatformFile(path)

    val deviceType = CommonPlatformDependencies.deviceType()

    if (deviceType != DeviceType.DESKTOP && mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
      FileKit.saveImageToGallery(platformFile)
    } else {
      withContext(coroutines.ioDispatcher) {
        val storagePath = platformDependencies.getDownloadStoragePath()
        val destinationPath = Path(storagePath, path.name)

        SystemFileSystem.atomicMove(path, destinationPath)
      }
    }

  }

  override fun getTempStoragePath(): Path {
    return FileKit.cacheDir.toKotlinxIoPath()
  }

  override fun getInternalStoragePath(): Path {
    return FileKit.filesDir.toKotlinxIoPath()
  }

  override suspend fun openFile(filePath: String): Boolean {
    return platformDependencies.openFile(filePath)
  }

}

internal expect fun PlatformFile.mimeType(): String

data class ResolvedFileData(
  val fileName: String,
  val mimeType: String,
  val fileSize: Long
)

internal fun PlatformFile.mimeTypeFromExtension(): String{
  return getMimeTypeFromExtension(extension)
}

internal fun getMimeTypeFromExtension(extension: String?): String {
  if (extension == null) return DEFAULT_MIME_TYPE

  return mimeTypes[extension] ?: run {
    log("PlatformFileSystemImpl", "Unknown mime type for extension $extension", IllegalArgumentException("Unknown mime type for extension $extension"))
    DEFAULT_MIME_TYPE
  }
}

private val mimeTypes = mapOf(

  "html" to "text/html",
  "htm" to "text/html",
  "shtml" to "text/html",
  "css" to "text/css",
  "xml" to "text/xml",
  "gif" to "image/gif",
  "jpeg" to "image/jpeg",
  "jpg" to "image/jpeg",
  "mml" to "text/mathml",
  "txt" to "text/plain",
  "wml" to "text/vnd.wap.wml",
  "htc" to "text/x-component",
  "png" to "image/png",
  "tif" to "image/tiff",
  "tiff" to "image/tiff",
  "ico" to "image/x-icon",
  "jng" to "image/x-jng",
  "bmp" to "image/x-ms-bmp",
  "svg" to "image/svg+xml",
  "svgz" to "image/svg+xml",
  "webp" to "image/webp",
  "mid" to "audio/midi",
  "midi" to "audio/midi",
  "kar" to "audio/midi",
  "mp3" to "audio/mpeg",
  "ogg" to "audio/ogg",
  "m4a" to "audio/x-m4a",
  "3gpp" to "video/3gpp",
  "3gp" to "video/3gpp",
  "ts" to "video/mp2t",
  "mp4" to "video/mp4",
  "mpeg" to "video/mpeg",
  "mpg" to "video/mpeg",
  "mov" to "video/quicktime",
  "webm" to "video/webm",
  "flv" to "video/x-flv",
  "m4v" to "video/x-m4v",
  "mng" to "video/x-mng",
  "asx" to "video/x-ms-asf",
  "asf" to "video/x-ms-asf",
  "wmv" to "video/x-ms-wmv",
  "avi" to "video/x-msvideo",
  "apk" to "application/vnd.android.package-archive"

)

internal const val DEFAULT_MIME_TYPE = "application/octet-stream"