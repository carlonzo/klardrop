package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.log
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path

interface FileManager {
  fun prepareSaveFile(fileName: String, mimeType: String? = null): FileTransfer
  fun getReadStreamFromUri(fileName: String): BufferedSource

}

interface FileTransfer {
  val bufferedSink: BufferedSink
  suspend fun onTransferCompleted()
  suspend fun onTransferFailed()
}

fun getAvailableFilePath(parentPath: Path, requestedFileName: String, fileSystem: FileSystem): Path {
  var destinationPath = parentPath.resolve(requestedFileName)

  while (fileSystem.exists(destinationPath)) {
    destinationPath = generateNewFilePath(parentPath, destinationPath.name)
    log("FileManagerImpl", "File already exists, generated new path: $destinationPath")
  }

  return destinationPath
}

private fun generateNewFilePath(parentPath: Path, requestedFileName: String): Path {

  val regex = ".+\\((\\d+)\\)".toRegex() // "file (1).txt"
  val extension = requestedFileName.substringAfterLast(".", "")
  val fileName = requestedFileName.removeSuffix(".$extension")

  val match = regex.find(fileName)

  if (match == null) {
    return parentPath.resolve("$fileName (1).$extension")
  } else {
    match.groups[1]?.value?.toInt()?.let {
      val newNumber = it + 1
      val newFileName = fileName.replace("($it)", "($newNumber)")
      return parentPath.resolve("$newFileName.$extension")
    } ?: run {
      return parentPath.resolve("$fileName (1).$extension")
    }
  }

}
