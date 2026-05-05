package com.carlom.klardrop.common.utils

import com.carlom.klardrop.common.CommonPlatformDependencies
import com.carlom.klardrop.common.InternalPlatformDependencies
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.extension
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.saveImageToGallery
import io.github.vinceglb.filekit.size
import io.github.vinceglb.filekit.source
import io.github.vinceglb.filekit.toKotlinxIoPath
import kotlinx.coroutines.withContext
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

interface PlatformFileSystem {
  fun getReadStreamFrom(platformFile: PlatformFile): RawSource

  fun getWriteStreamTo(path: Path): RawSink

  fun getResolvedFileData(platformFile: PlatformFile): ResolvedFileData

  suspend fun delete(path: Path)

  suspend fun moveToStorage(path: Path, mimeType: String): Path?

  fun getTempStoragePath(): Path

  fun getInternalStoragePath(): Path

  suspend fun openFile(filePath: String): Boolean
}

internal class PlatformFileSystemImpl(
  private val platformDependencies: InternalPlatformDependencies,
  private val coroutines: Coroutines
) : PlatformFileSystem {

  override fun getReadStreamFrom(platformFile: PlatformFile): RawSource {
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

  override suspend fun moveToStorage(path: Path, mimeType: String): Path? {
    val platformFile = PlatformFile(path)

    val deviceType = CommonPlatformDependencies.deviceType()

    return if (deviceType != DeviceType.DESKTOP && (FileTypeUtils.isImageMimeType(mimeType) || FileTypeUtils.isVideoMimeType(mimeType))) {
      // Prefer a platform impl that returns a usable path/URI (Android MediaStore). When
      // the platform exposes one, persist it so the chat bubble can render a preview and
      // tap-to-open can target the media directly. When it doesn't (iOS today), fall back
      // to FileKit's gallery save which discards the path — UI then has no target to open.
      val galleryLocation = platformDependencies.saveMediaToGallery(path, mimeType, path.name)
      if (galleryLocation != null) {
        Path(galleryLocation)
      } else {
        FileKit.saveImageToGallery(platformFile)
        null
      }
    } else {
      withContext(coroutines.ioDispatcher) {
        val storagePath = platformDependencies.getDownloadStoragePath()
        val destinationPath = Path(storagePath, path.name)

        SystemFileSystem.atomicMove(path, destinationPath)
        destinationPath
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

internal fun PlatformFile.mimeTypeFromExtension(): String {
  return FileTypeUtils.getMimeTypeFromExtension(extension)
}
