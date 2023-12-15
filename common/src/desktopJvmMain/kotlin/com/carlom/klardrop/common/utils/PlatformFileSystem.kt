package com.carlom.klardrop.common.utils

import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.getAvailableFilePath
import com.carlom.klardrop.common.persistence.CurrentFileSystem
import okio.*
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files

actual class PlatformFileSystem(
  private val platformDependencies: InternalPlatformDependencies
) {
  private val fileSystem = CurrentFileSystem

  actual fun getReadStreamFromUri(uri: String): BufferedSource {
    return uri.toFile.inputStream().source().buffer()
  }

  @Suppress("NewApi")
  actual fun getResolvedFileData(uri: String): ResolvedFileData {
    uri.toFile.let { file ->
      val mimeType = Files.probeContentType(file.toPath())
        ?: getMimeTypeFromExtension(file.extension)

      return ResolvedFileData(
        fileName = file.name,
        mimeType = mimeType,
        fileSize = file.length()
      )
    }
  }

  private val String.toFile: File
    get() = toPath(normalize = true).toFile()

  actual fun getWriteStreamFromUri(uri: String): BufferedSink {
    return uri.toFile.outputStream().sink().buffer()
  }

  actual fun delete(uri: String) {
    uri.toFile.delete()
  }

  actual suspend fun moveToStorage(filePath: String, mimeType: String) {
    val sourcePath = filePath.toPath()
    val destinationPath = getAvailableFilePath(platformDependencies.getStoragePath(), sourcePath.name, fileSystem)

    fileSystem.atomicMove(sourcePath, destinationPath)
  }

  actual fun getTempStoragePath(): Path {
    return System.getenv("TMPDIR").toPath()
  }
}