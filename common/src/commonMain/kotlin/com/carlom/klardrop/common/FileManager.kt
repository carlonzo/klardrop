package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.log
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path

interface FileManager {
  fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer
  fun getReadStreamFrom(file: PlatformFile): RawSource
  suspend fun openFile(filePath: String): Boolean // New method
  suspend fun openUrl(url: String): Boolean
}

interface FileTransfer {
  val bufferedSink: Sink
  suspend fun onTransferCompleted(): Path?
  suspend fun onTransferFailed()
}

fun getAvailableFilePath(parentPath: Path, requestedFileName: String, fileSystem: FileSystem): Path {
  val resolvedParent = fileSystem.resolve(parentPath)
  val firstChoice = Path(resolvedParent, requestedFileName)

  if (!fileSystem.exists(firstChoice)) {
    return firstChoice
  }

  // Split "dog.jpeg" into base "dog" and extension ".jpeg" so the counter goes before the
  // extension: dog-1.jpeg, dog-2.jpeg, … rather than dog.jpeg-1.
  val extension = requestedFileName.substringAfterLast(".", "").let {
    if (it.isEmpty()) "" else ".$it"
  }
  val baseName = requestedFileName.substring(0, requestedFileName.length - extension.length)

  var counter = 1
  var destinationPath = Path(resolvedParent, "$baseName-$counter$extension")
  while (fileSystem.exists(destinationPath)) {
    counter++
    destinationPath = Path(resolvedParent, "$baseName-$counter$extension")
  }

  log("FileManagerImpl", "File '$requestedFileName' already exists, saving as: ${destinationPath.name}")
  return destinationPath
}
