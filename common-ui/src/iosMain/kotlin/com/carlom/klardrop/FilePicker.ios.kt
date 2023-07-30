package com.carlom.klardrop

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeMovie
import platform.darwin.NSObject

actual class FilePicker(
  private val rootController: UIViewController
) {

  private lateinit var onFilesPicked: (List<String>) -> Unit

  private val filePickerDelegate = object : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {

      if (didFinishPicking.isEmpty()) {
        return
      }

      val results = didFinishPicking as List<PHPickerResult>

      val loadingJobs = mutableListOf<CompletableDeferred<String>>()

      results.forEach {
        val itemProvider = it.itemProvider

        val loading = CompletableDeferred<String>()
        if (itemProvider.hasItemConformingToTypeIdentifier(UTTypeImage.identifier)) {

          loadingJobs.add(loading)
          itemProvider.loadFileRepresentationForTypeIdentifier(UTTypeImage.identifier) { url, error ->

            if (error == null) {
              loading.complete(url.toString())
            } else {
              loading.completeExceptionally(RuntimeException("Error loading image: $error"))
            }
            println("image url: $url")
            println("image error: $error")
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
        loadingJobs.awaitAll()
        picker.dismissViewControllerAnimated(true, null)
        onFilesPicked(loadingJobs.map { it.getCompleted() })
      }

    }
  }

  actual fun openFilePicker() {
    val configuration = PHPickerConfiguration()

    val filePickerController = PHPickerViewController(configuration = configuration)

    rootController.presentViewController(filePickerController, true) {
      filePickerController.delegate = filePickerDelegate
    }
  }

  @Composable
  actual fun registerPicker(onFilesPicked: (List<String>) -> Unit) {
    this.onFilesPicked = onFilesPicked
  }

}