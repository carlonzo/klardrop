package com.carlom.klardrop.common

import com.carlom.klardrop.common.persistence.CurrentFileSystem
import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.log
import okio.BufferedSink
import okio.BufferedSource
import okio.Path

class FileManagerImpl(
  private val platformFileSystem: PlatformFileSystem,
  private val internalPlatformDependencies: InternalPlatformDependencies
) : FileManager {
  override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer {
    val tempStorage = internalPlatformDependencies.getTempStoragePath()
    val tempAvailableFilePath = getAvailableFilePath(tempStorage, fileName, CurrentFileSystem)

    log("FileManagerImpl", "Preparing file transfer to $tempAvailableFilePath with mimeType $mimeType")

    return FileTransferImpl(tempAvailableFilePath, mimeType)
  }

  override fun getReadStreamFromUri(fileName: String): BufferedSource {
    return platformFileSystem.getReadStreamFromUri(fileName)
  }

  inner class FileTransferImpl(
    private val destinationPath: Path,
    private val mimeType: String
  ) : FileTransfer {
    override val bufferedSink: BufferedSink by lazy { platformFileSystem.getWriteStreamFromUri(destinationPath.toString()) }

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