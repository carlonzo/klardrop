package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.log
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem


class FileManagerImpl(
  private val platformFileSystem: PlatformFileSystem
) : FileManager {
  override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer {
    val tempStorage = platformFileSystem.getTempStoragePath()
    val tempAvailableFilePath = getAvailableFilePath(tempStorage, fileName, SystemFileSystem)

    log("FileManagerImpl", "Preparing file transfer to $tempAvailableFilePath with mimeType $mimeType")

    return FileTransferImpl(tempAvailableFilePath, mimeType)
  }

  override fun getReadStreamFrom(file: PlatformFile): RawSource {
    return platformFileSystem.getReadStreamFrom(file)
  }

  override suspend fun openFile(filePath: String): Boolean {
    return platformFileSystem.openFile(filePath)
  }

  inner class FileTransferImpl(
    private val destinationPath: Path,
    private val mimeType: String
  ) : FileTransfer {
    override val bufferedSink: Sink by lazy { platformFileSystem.getWriteStreamTo(destinationPath).buffered() }

    override suspend fun onTransferCompleted() {
      platformFileSystem.moveToStorage(destinationPath, mimeType)
    }

    override suspend fun onTransferFailed() {
      platformFileSystem.delete(destinationPath)
    }

  }
}