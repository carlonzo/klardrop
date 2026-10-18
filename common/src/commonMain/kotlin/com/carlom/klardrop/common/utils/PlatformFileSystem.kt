package com.carlom.klardrop.common.utils

import com.carlom.klardrop.common.CommonPlatformDependencies
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.getAvailableFilePath
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
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

public interface PlatformFileSystem {
  public fun getReadStreamFrom(platformFile: PlatformFile): RawSource

  public fun getWriteStreamTo(path: Path): RawSink

  public fun getResolvedFileData(platformFile: PlatformFile): ResolvedFileData

  /**
   * Copy [platformFile] into a stable, app-private cache file and return a handle to the copy
   * plus the resolved metadata (with [ResolvedFileData.fileSize] set to the exact number of bytes
   * actually copied).
   *
   * Must be called while the caller still holds read access to the source. On Android a
   * `content://` URI handed in via a share Intent is only readable for the lifetime of the
   * receiving Activity — once it finishes, re-opening the URI throws SecurityException. Since a
   * file transfer only streams its bytes *after* the remote peer accepts (well after the share
   * sheet is gone), we materialize a local copy up-front. The returned file is backed by a real
   * on-disk path, so it streams fine after the grant is gone and survives transfer retries.
   */
  public suspend fun prepareFileForSending(platformFile: PlatformFile): PreparedSendFile

  public suspend fun delete(path: Path): Unit

  public suspend fun moveToStorage(path: Path, mimeType: String): Path?

  public fun getTempStoragePath(): Path

  public fun getInternalStoragePath(): Path

  public suspend fun openFile(filePath: String): Boolean

  public suspend fun openUrl(url: String): Boolean
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

  override suspend fun prepareFileForSending(platformFile: PlatformFile): PreparedSendFile =
    withContext(coroutines.ioDispatcher) {
      val data = getResolvedFileData(platformFile)
      val destination = getAvailableFilePath(getTempStoragePath(), data.fileName, SystemFileSystem)

      // Open the source stream here, while the caller still holds the read grant. Once the
      // descriptor is acquired the copy keeps working even if the grant is revoked mid-copy.
      val copied = getReadStreamFrom(platformFile).buffered().use { source ->
        getWriteStreamTo(destination).buffered().use { sink -> source.transferTo(sink) }
      }

      log("PlatformFileSystem", "Cached ${data.fileName} ($copied bytes) for sending at $destination")
      // Trust the byte count we actually copied over the source's reported size — the latter can
      // be stale/unknown (-1) for content providers, and the streaming loop keys off fileSize.
      PreparedSendFile(file = PlatformFile(destination), data = data.copy(fileSize = copied))
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
        // Resolve collisions against the *destination* dir, not the temp cache the file was
        // staged in. The staging dir can be empty (fresh/cleared cache) while Downloads already
        // holds a file with this name — without this check the move below would overwrite it,
        // which is exactly how three same-named files clobbered each other.
        val destinationPath = getAvailableFilePath(storagePath, path.name, SystemFileSystem)

        // Try atomicMove first — same-filesystem (rename) is fastest. On Android the temp
        // file lives in app cache (`/data/data/...`) and the destination is the user's
        // Downloads (`/storage/emulated/0/Download`), which sit on different mount points;
        // ATOMIC_MOVE refuses cross-device renames with AtomicMoveNotSupportedException.
        // Fall back to a copy-and-delete so the file still lands in Downloads.
        runCatching { SystemFileSystem.atomicMove(path, destinationPath) }
          .recoverCatching {
            log("PlatformFileSystem", "atomicMove failed (${it::class.simpleName}: ${it.message}); copying then deleting")
            SystemFileSystem.source(path).buffered().use { src ->
              SystemFileSystem.sink(destinationPath).buffered().use { dst ->
                src.transferTo(dst)
              }
            }
            SystemFileSystem.delete(path)
          }
          .getOrThrow()
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

  override suspend fun openUrl(url: String): Boolean {
    return platformDependencies.openUrl(url)
  }

}

internal expect fun PlatformFile.mimeType(): String

public data class ResolvedFileData(
  public val fileName: String,
  public val mimeType: String,
  public val fileSize: Long,
)

/** A shared file copied to app-private storage, ready to stream regardless of the original
 *  source's lifecycle. See [PlatformFileSystem.prepareFileForSending]. */
public data class PreparedSendFile(
  public val file: PlatformFile,
  public val data: ResolvedFileData,
)

internal fun PlatformFile.mimeTypeFromExtension(): String {
  return FileTypeUtils.getMimeTypeFromExtension(extension)
}
