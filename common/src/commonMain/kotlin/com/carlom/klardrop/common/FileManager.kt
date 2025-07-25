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
}

interface FileTransfer {
  val bufferedSink: Sink
  suspend fun onTransferCompleted()
  suspend fun onTransferFailed()
}

fun getAvailableFilePath(parentPath: Path, requestedFileName: String, fileSystem: FileSystem): Path {
  val resolvedParent = fileSystem.resolve(parentPath)
  var destinationPath = Path(resolvedParent, requestedFileName)

  while (fileSystem.exists(destinationPath)) {
    destinationPath = generateNewFilePath(resolvedParent, destinationPath.name)
    log("FileManagerImpl", "File already exists, generated new path: $destinationPath")
  }

  return destinationPath
}

private fun generateNewFilePath(parentPath: Path, requestedFileName: String): Path {

  val regex = ".+\\((\\d+)\\)".toRegex() // "file (1).txt"
  val extension = requestedFileName.substringAfterLast(".", "").let {
    if (it.isEmpty()) "" else ".$it"
  }
  val fileName = requestedFileName.substring(0, requestedFileName.length - extension.length)

  val match = regex.find(fileName)

  return if (match == null) {
    Path(parentPath, "$fileName (1)$extension")
  } else {
    match.groups[1]?.value?.toInt()?.let {
      val newNumber = it + 1
      val newFileName = fileName.replace("($it)", "($newNumber)")
      Path(parentPath, "$newFileName$extension")
    } ?: Path(parentPath, "$fileName (1)$extension")
  }

}
