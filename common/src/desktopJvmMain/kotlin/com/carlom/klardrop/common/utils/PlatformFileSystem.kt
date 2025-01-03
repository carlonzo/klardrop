package com.carlom.klardrop.common.utils

import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.getAvailableFilePath
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.File
import java.nio.file.Files

actual class PlatformFileSystem(
  private val platformDependencies: InternalPlatformDependencies
) {

  actual fun getReadStreamFromUri(uri: String): Source {
    return getReadStreamFromUri(Path(uri))
  }

  actual fun getReadStreamFromUri(path: Path): Source {
    return SystemFileSystem.source(path).buffered()
  }

  @Suppress("NewApi")
  actual fun getResolvedFileData(uri: String): ResolvedFileData {
    val file = File(uri)

    val mimeType = Files.probeContentType(file.toPath())
      ?: getMimeTypeFromExtension(file.extension)

    return ResolvedFileData(
      fileName = file.name,
      mimeType = mimeType,
      fileSize = file.length()
    )

  }

  actual fun getWriteStreamFromUri(uri: String): Sink {
    return SystemFileSystem.sink(Path(uri)).buffered()
  }

  actual fun delete(uri: String) {
    SystemFileSystem.delete(Path(uri))
  }

  actual suspend fun moveToStorage(filePath: String, mimeType: String) {
    val sourcePath = Path(filePath)
    val destinationPath = getAvailableFilePath(platformDependencies.getDownloadStoragePath(), sourcePath.name, SystemFileSystem)

    SystemFileSystem.atomicMove(sourcePath, destinationPath)
  }

  actual fun getTempStoragePath(): Path {
    return Path(System.getenv("TMPDIR"))
  }

}