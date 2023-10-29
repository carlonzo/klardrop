package com.carlom.klardrop.common.utils

import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.getAvailableFilePath
import com.carlom.klardrop.common.persistence.CurrentFileSystem
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import okio.BufferedSink
import okio.BufferedSource
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.pathExtension
import platform.UIKit.UIImage
import platform.UIKit.UIImageWriteToSavedPhotosAlbum
import platform.UIKit.UISaveVideoAtPathToSavedPhotosAlbum

actual class PlatformFileSystem(
  private val platformDependencies: InternalPlatformDependencies
) {
  private val fileSystem = CurrentFileSystem

  actual fun getReadStreamFromUri(uri: String): BufferedSource {
    return fileSystem.source(uri.toPath()).buffer()
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

  actual fun getWriteStreamFromUri(uri: String): BufferedSink {
    return CurrentFileSystem.sink(uri.toPath(), mustCreate = true).buffer()
  }

  actual fun delete(uri: String) {
    NSFileManager.defaultManager.removeItemAtPath(uri, null)
  }

  actual suspend fun moveToStorage(filePath: String, mimeType: String) {
    val sourcePath = filePath.toPath()
    val destinationPath = getAvailableFilePath(platformDependencies.getStoragePath(), sourcePath.name, fileSystem)
    log("iOS-PlatformFileSystem", "moveToStorage $filePath destination $destinationPath  $mimeType")

    fileSystem.atomicMove(sourcePath, destinationPath)

    if (mimeType.startsWith("image/")) {
      UIImageWriteToSavedPhotosAlbum(UIImage(destinationPath.toString()), null, null, null)
    } else if (mimeType.startsWith("video/")) {
      UISaveVideoAtPathToSavedPhotosAlbum(destinationPath.toString(), null, null, null)
    }

  }

  actual fun getTempStoragePath(): Path {
    return NSTemporaryDirectory().toPath()
  }

}