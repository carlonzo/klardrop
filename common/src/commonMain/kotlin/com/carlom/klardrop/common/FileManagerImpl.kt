package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.log
import kotlinx.io.Sink
import kotlinx.io.Source
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

  override fun getReadStreamFromUri(fileName: String): Source {
    return platformFileSystem.getReadStreamFromUri(fileName)
  }

  inner class FileTransferImpl(
    private val destinationPath: Path,
    private val mimeType: String
  ) : FileTransfer {
    override val bufferedSink: Sink by lazy { platformFileSystem.getWriteStreamFromUri(destinationPath.toString()) }

    override suspend fun onTransferCompleted() {
      // should have been closed already
      bufferedSink.close()

      platformFileSystem.moveToStorage(destinationPath.toString(), mimeType)
    }

    override suspend fun onTransferFailed() {
      // should have been closed already
      bufferedSink.close()

      platformFileSystem.delete(destinationPath.toString())
    }

  }
}