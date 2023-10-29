package com.carlom.klardrop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.interop.LocalUIViewController
import com.carlom.klardrop.common.persistence.CurrentFileSystem
import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.log
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import okio.use
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeMovie
import platform.darwin.NSObject

actual class FilePicker(
  private val rootController: UIViewController,
  private val platformFileSystem: PlatformFileSystem
) {

  private lateinit var onFilesPicked: (DeviceUi, List<String>) -> Unit

  private class FilePickerDelegate(
    private val deviceUi: DeviceUi,
    private val onFilesPicked: (DeviceUi, List<String>) -> Unit,
    private val platformFileSystem: PlatformFileSystem
  ) : NSObject(), PHPickerViewControllerDelegateProtocol {
    @OptIn(ExperimentalForeignApi::class)
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {

      log("picker callback ${didFinishPicking.map { it.toString() }} ")

      val results = didFinishPicking as List<PHPickerResult>

      val loadingJobs = mutableListOf<CompletableDeferred<String>>()

      results.forEach {
        val itemProvider = it.itemProvider

        itemProvider.loadItemForTypeIdentifier(UTTypeImage.identifier, null) { data, error ->

          println("image data: ${(data as? NSURL).toString()}}")
          println("image error: $error")
        }

        val loading = CompletableDeferred<String>()
        if (itemProvider.hasItemConformingToTypeIdentifier(UTTypeImage.identifier)) {

          loadingJobs.add(loading)
          itemProvider.loadFileRepresentationForTypeIdentifier(UTTypeImage.identifier) { url, error ->

            if (error == null) {
              loading.complete(url.toString())
            } else {
              loading.completeExceptionally(RuntimeException("Error loading image: $error"))
            }
            println("image url: $url - ${url.toString().toPath()} ${CurrentFileSystem.exists(url.toString().toPath())}")
            println("image error: $error")

            val fileName = url.toString().toPath().name

            val tmpFile = platformFileSystem.getTempStoragePath().resolve(fileName)

            log("FilePicker", "Storing temp file from $url to $tmpFile")
            NSFileManager.defaultManager.copyItemAtPath(url.toString(), tmpFile.toString(), null)
            log("FilePicker", "Copy completed in $tmpFile")

          }
        } else if (itemProvider.hasItemConformingToTypeIdentifier(UTTypeMovie.identifier)) {
          loadingJobs.add(loading)

          itemProvider.loadFileRepresentationForTypeIdentifier(UTTypeMovie.identifier) { url, error ->

            if (error == null) {
              loading.complete(url.toString())
            } else {
              loading.completeExceptionally(RuntimeException("Error loading video: $error"))
            }

            println("video url: $url")
            println("video error: $error")
          }
        } else {
          println("unkown type")
          itemProvider.registeredTypeIdentifiers.forEach { identifier ->
            println("unkown type $identifier")
          }
        }


      }

      GlobalScope.launch(Dispatchers.Main) {
        log("FilePicker", "loading images async ${loadingJobs.size} ")

        loadingJobs.awaitAll()

        val paths = loadingJobs.map { it.getCompleted() }

       val newPaths = paths.map {
          log("FilePicker", "loading images async $it ")

         it.toPath().also {
           log("exists ${CurrentFileSystem.exists(it)}")

         }

          val fileName = it.toPath().name

          val readStreamFromUri = platformFileSystem.getReadStreamFromUri(it)

          val tmpFile = platformFileSystem.getTempStoragePath().resolve(fileName)

          log("FilePicker", "Storing temp file in $tmpFile")

          platformFileSystem.getWriteStreamFromUri(tmpFile.toString()).use { sink ->
            sink.writeAll(readStreamFromUri)
          }

          tmpFile
        }.map { it.toString() }

        picker.dismissViewControllerAnimated(true, null)
        onFilesPicked(deviceUi, newPaths)
      }

    }
  }

  actual fun openFilePicker(deviceUi: DeviceUi) {
    val configuration = PHPickerConfiguration()

    val filePickerController = PHPickerViewController(configuration = configuration)

    rootController.presentViewController(filePickerController, true) {
      filePickerController.delegate = FilePickerDelegate(deviceUi, onFilesPicked, platformFileSystem)
    }
  }

  @Composable
  actual fun registerPicker(onFilesPicked: (DeviceUi, List<String>) -> Unit) {
    this.onFilesPicked = onFilesPicked

  }

}

actual class FilePickerFactory(private val platformFileSystem: PlatformFileSystem) {
  @Composable
  actual fun createPicker(): FilePicker {
    val uiViewController = LocalUIViewController.current
    return remember { FilePicker(uiViewController, platformFileSystem) }
  }
}