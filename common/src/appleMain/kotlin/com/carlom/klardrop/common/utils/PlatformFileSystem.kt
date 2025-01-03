package com.carlom.klardrop.common.utils

import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.getAvailableFilePath
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIImage
import platform.UIKit.UIImageWriteToSavedPhotosAlbum
import platform.UIKit.UISaveVideoAtPathToSavedPhotosAlbum

actual class PlatformFileSystem(
  private val platformDependencies: InternalPlatformDependencies
) {
  private val fileSystem = SystemFileSystem

  actual fun getReadStreamFromUri(uri: String): Source {
    return getReadStreamFromUri(Path(uri))
  }

  actual fun getReadStreamFromUri(path: Path): Source {
    return fileSystem.source(path).buffered()
  }

  actual fun getResolvedFileData(uri: String): ResolvedFileData {

    log("getResolvedFileData $uri")

    val nsFileManager = NSFileManager.defaultManager
    memScoped {
      val startError = alloc<ObjCObjectVar<NSError?>>()

      val attributesFile = nsFileManager.attributesOfItemAtPath(uri, startError.ptr)

      log("attributesFile $attributesFile")

      val error = startError.value
      if (error != null) {
        throw IllegalArgumentException("Got error in getResolvedFileData $error")
      }

      val fileSize = attributesFile?.get(NSFileSize) as? Long ?: 0L
      log("fileSize $fileSize")
      val fileName = nsFileManager.displayNameAtPath(uri)
      log("fileName $fileName")
      val fileURL = NSURL.fileURLWithPath(uri)
      log("fileURL $fileURL")
      val mimeType = getMimeTypeFromExtension(fileURL.pathExtension)
      log("mimeType $mimeType")

      return ResolvedFileData(
        fileName = fileName,
        mimeType = mimeType,
        fileSize = fileSize
      ).also { log("ResolvedFileData $it") }
    }

  }

  actual fun getWriteStreamFromUri(uri: String): Sink {
    return SystemFileSystem.sink(Path(uri)).buffered()
  }

  actual fun delete(uri: String) {
    NSFileManager.defaultManager.removeItemAtPath(uri, null)
  }

  actual suspend fun moveToStorage(filePath: String, mimeType: String) {
    val sourcePath = Path(filePath)
    val destinationPath = getAvailableFilePath(platformDependencies.getDownloadStoragePath(), sourcePath.name, fileSystem)
    log("iOS-PlatformFileSystem", "moveToStorage $filePath destination $destinationPath  $mimeType")

    fileSystem.atomicMove(sourcePath, destinationPath)

    if (mimeType.startsWith("image/")) {
      UIImageWriteToSavedPhotosAlbum(UIImage(destinationPath.toString()), null, null, null)
    } else if (mimeType.startsWith("video/")) {
      UISaveVideoAtPathToSavedPhotosAlbum(destinationPath.toString(), null, null, null)
    }

  }

  actual fun getTempStoragePath(): Path {
    return Path(NSTemporaryDirectory())
  }

}